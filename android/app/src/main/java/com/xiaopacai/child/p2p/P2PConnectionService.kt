package com.xiaopacai.child.p2p

import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.util.KeyStoreManager
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.*
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * [TASK-D1-04] P2P 连接服务
 *
 * 管理与家长端的 TCP + TLS 1.3 安全连接。
 * 负责：
 * - 建立 TLS 双向认证连接
 * - 发送/接收 JSON 消息帧
 * - 心跳维持与断线重连
 * - 证书指纹校验（防中间人攻击）
 */

/** 连接状态 */
enum class P2PConnectionState {
    DISCONNECTED,   // 未连接
    CONNECTING,     // 连接中（TCP 握手 / TLS 握手）
    HANDSHAKING,    // 协议握手（设备信息交换）
    CONNECTED,      // 已连接，可收发消息
    RECONNECTING    // 断线重连中
}

/** P2P 消息类型 */
enum class MessageType(val value: String) {
    HANDSHAKE("handshake"),
    USAGE_REPORT("usage_report"),
    STATUS_UPDATE("status_update"),
    POLICY_ACK("policy_ack"),
    ANNOUNCEMENT_ACK("announcement_ack"),
    // [TASK-OPT-12-P1] 故障诊断信息上报（儿童→家长，复用 P2P 链路）
    DIAGNOSTICS_REPORT("diagnostics_report"),
    HEARTBEAT("heartbeat"),
    ERROR("error")
}

/**
 * [TASK-PRELAUNCH-FIX-SCAN] 握手确定性拒绝信息
 * @param code 错误码（unpaired / revoked / device_owned_by_other /
 *        fingerprint_mismatch / invalid_pairing_code / missing_device_id 等）
 * @param reason 服务端给出的可读原因（可为空）
 */
data class HandshakeRejection(val code: String, val reason: String)

/**
 * [TASK-PRELAUNCH-FIX-SCAN] 确定性拒绝错误码集合：此类拒绝重试无意义
 * （需家长端操作或重新扫码），儿童端必须停止自动重连，回配对界面提示原因。
 * 其余错误（网络异常、服务端关闭、限速）按临时性失败处理，继续退避重连。
 */
internal val DETERMINISTIC_REJECTION_CODES = setOf(
    "unpaired",                // 设备无归属且无有效配对码
    "revoked",                 // 已被家长端解绑
    "device_owned_by_other",   // 归属其他账号（换绑被拒）
    "fingerprint_mismatch",    // 证书指纹不匹配
    "invalid_pairing_code",    // 配对码无效/过期（重试同一旧码必然再失败）
    "missing_device_id"        // Windows 家长端：设备信息缺失
)

/** 从握手后的首个响应帧解析拒绝信息；非拒绝帧（policy_update 等）返回 null */
internal fun parseHandshakeRejection(message: P2PMessage): HandshakeRejection? {
    return when (message.type) {
        "handshake_rejected" -> {
            // Web 服务端：error_code 为错误码，error 为可读原因（均为顶层字段，fromJson 已并入 payload）。
            // [TASK-PRELAUNCH-FIX-RATELIMIT] 空 error_code 防御性映射为 ip_rate_limited：
            // 旧服务端所有拒绝分支中唯一不带码的是 K3 限速分支（122 信自锁闭环根因），
            // 新服务端已补齐显式码；空白码不可能来自其他拒绝路径。
            val code = (message.payload["error_code"] as? String ?: "").ifBlank { "ip_rate_limited" }
            HandshakeRejection(code, message.payload["error"] as? String ?: "连接被拒绝")
        }
        "error" -> {
            // Windows 家长端：payload.message 即错误码（fingerprint_mismatch / missing_device_id 等）
            val code = message.payload["message"] as? String ?: ""
            HandshakeRejection(code, message.payload["message"] as? String ?: "连接被拒绝")
        }
        else -> null
    }
}

internal fun isDeterministicRejectionCode(code: String): Boolean =
    code.isNotBlank() && code in DETERMINISTIC_REJECTION_CODES

/**
 * [TASK-PRELAUNCH-FIX-RATELIMIT] IP 级限速拒绝：临时性失败，允许自动重连，
 * 但必须长指数退避（禁止 1s 短退避把服务端 5 分钟限速窗口反复打满）。
 */
internal fun isRateLimitedRejectionCode(code: String): Boolean = code == "ip_rate_limited"

/** 限速退避基础/上限延迟（首 60s，指数翻倍，上限 10 分钟） */
internal const val RATE_LIMIT_BASE_DELAY_MS = 60_000L
internal const val RATE_LIMIT_MAX_DELAY_MS = 600_000L

/**
 * [TASK-PRELAUNCH-FIX-RATELIMIT] 限速退避延迟：首 60s，指数翻倍，上限 10 分钟
 * （服务端窗口 5 分钟；10 分钟封顶保证窗口过期后必然放行，闭环自愈）
 */
internal fun rateLimitBackoffDelayMs(step: Int): Long =
    minOf(RATE_LIMIT_BASE_DELAY_MS * (1L shl step.coerceIn(0, 30)), RATE_LIMIT_MAX_DELAY_MS)

/** 拒绝原因 → 儿童端配对界面可读文案（code 未知时回退服务端原文） */
internal fun rejectionHintText(code: String, serverReason: String?): String = when (code) {
    "unpaired" -> "设备尚未配对，请用家长端生成配对二维码后重新扫码"
    "revoked" -> "设备已被家长端解绑，请重新扫码配对"
    "device_owned_by_other" -> "设备已被其他账号绑定，请联系原账号解绑后重试"
    "fingerprint_mismatch" -> "证书指纹不匹配，请重新扫码配对"
    "invalid_pairing_code" -> "配对码无效或已过期，请在家长端刷新二维码后重新扫码"
    "missing_device_id" -> "设备信息缺失，请重新扫码配对"
    "ip_rate_limited" -> "尝试次数过多，请稍后自动重试"
    else -> serverReason?.takeIf { it.isNotBlank() } ?: "连接被拒绝，请重新配对"
}

class P2PConnectionService {

    companion object {
        private const val TAG = "P2PConnection"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_TIMEOUT_COUNT = 3
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L

        /**
         * [SEC-K1] 获取客户端身份证书指纹（SHA-256 十六进制，64 字符）。
         * 该指纹即 TLS 握手提交的客户端证书指纹，服务端以此固定设备身份；
         * 家长端中继注册（/api/relay/register）也提交同一指纹完成绑定。
         */
        @JvmStatic
        fun getClientCertificateFingerprint(): String {
            val (_, certChain) = getOrCreateClientCertificate()
            return computeSha256Hex(certChain[0].encoded)
        }

        private fun computeSha256Hex(derEncoded: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(derEncoded)
            return hash.joinToString("") { "%02x".format(it) }
        }

        /**
         * [SEC-K1] 获取或生成客户端身份证书（EC P-256 + BouncyCastle 自签名，PKCS12 落盘）。
         *
         * 不使用 AndroidKeyStore（同 ParentP2PListenerService 原因：Conscrypt 握手
         * 签名路径与 AndroidKeyStore 密钥不兼容）。证书持久化保证重启后指纹稳定，
         * 服务端 TOFU/固定比对不因重装外原因失效。
         */
        private fun getOrCreateClientCertificate(): Pair<PrivateKey, Array<X509Certificate>> {
            val pfxFile = File(XiaopacaiApp.instance.filesDir, "p2p_client.pfx")
            val pwdFile = File(XiaopacaiApp.instance.filesDir, "p2p_client.pwd")

            // 1) 已有持久化证书：加载
            if (pfxFile.exists() && pwdFile.exists()) {
                try {
                    val pwd = pwdFile.readText().trim()
                    val ks = KeyStore.getInstance("PKCS12")
                    FileInputStream(pfxFile).use { ks.load(it, pwd.toCharArray()) }
                    val entry = ks.getEntry("p2p_client", KeyStore.PasswordProtection(pwd.toCharArray()))
                        as KeyStore.PrivateKeyEntry
                    return Pair(entry.privateKey, entry.certificateChain as Array<X509Certificate>)
                } catch (e: Exception) {
                    Log.w(TAG, "加载客户端身份证书失败，重新生成: ${e.message}")
                }
            }

            // 2) 生成 EC P-256 密钥 + 自签名客户端证书（clientAuth EKU）
            val keyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val cert = generateSelfSignedClientCertificate(keyPair)

            // 3) 持久化 PKCS12
            try {
                val pwd = java.util.UUID.randomUUID().toString()
                val ks = KeyStore.getInstance("PKCS12")
                ks.load(null, null)
                ks.setKeyEntry("p2p_client", keyPair.private, pwd.toCharArray(), arrayOf(cert))
                FileOutputStream(pfxFile).use { ks.store(it, pwd.toCharArray()) }
                pwdFile.writeText(pwd)
            } catch (e: Exception) {
                Log.w(TAG, "持久化客户端身份证书失败: ${e.message}")
            }
            Log.i(TAG, "已生成客户端身份证书: ${computeSha256Hex(cert.encoded)}")
            return Pair(keyPair.private, arrayOf(cert))
        }

        private fun generateSelfSignedClientCertificate(keyPair: KeyPair): X509Certificate {
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=Xiaopacai Client")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                java.util.Date(now - 86400_000L),
                java.util.Date(now + 10L * 365 * 24 * 3600 * 1000),
                subject,
                keyPair.public
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature)
            )
            builder.addExtension(Extension.extendedKeyUsage, true, ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }
    }

    /** 连接状态 */
    private val _connectionState = MutableStateFlow(P2PConnectionState.DISCONNECTED)
    val connectionState: StateFlow<P2PConnectionState> = _connectionState

    /** 收到的消息流 */
    private val _receivedMessages = MutableStateFlow<List<P2PMessage>>(emptyList())
    val receivedMessages: StateFlow<List<P2PMessage>> = _receivedMessages

    // [TASK-PRELAUNCH-FIX-SCAN] 最近一次确定性握手拒绝（null=无）；UI 据此提示原因。
    // 新连接开始时清空，确定性拒绝时写入，成功后保持 null
    private val _handshakeRejection = MutableStateFlow<HandshakeRejection?>(null)
    val handshakeRejection: StateFlow<HandshakeRejection?> = _handshakeRejection

    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    private var expectedFingerprint: String? = null  // 期望的证书指纹
    private var connectedFingerprint: String? = null  // 实际连接的证书指纹
    private var heartbeatMissCount = 0
    private var reconnectAttempt = 0
    // [TASK-PRELAUNCH-FIX-RATELIMIT] 限速退避步数（成功连接后归零）
    private var rateLimitBackoffStep = 0
    private var _host: String = ""
    private var _port: Int = 9527
    private var _deviceId: String = ""
    private var _deviceName: String = ""
    private var _pairingCode: String? = null
    private var _isRelay: Boolean = false  // 是否通过 Web 云端中继连接
    // [SEC-P1] 是否允许空指纹 TOFU 首连（仅扫码引导流程；见 connect() 注释）
    private var _allowTofu: Boolean = false
    // [SEC-K2] 中继会话令牌（家长端握手凭据，注册时签发，重连时重复使用）
    private var _sessionToken: String? = null

    /**
     * 连接到家长端
     * @param host 家长端 IP 地址
     * @param port 家长端端口
     * @param expectedFingerprint 期望的证书指纹（首次配对时可为 null）
     * @param deviceId 本机设备 ID
     * @param deviceName 本机设备名称
     * @param pairingCode 配对码（6 位数字，家长端要求时提供）
     * @param sessionToken [SEC-K2] 中继会话令牌（家长端经 /api/relay/register 获得，握手必带）
     * @param allowTofu [SEC-P1] 是否允许空指纹首连（TOFU）。仅扫码引导流程允许
     *        （二维码本身携带可信指纹时也应传入非空 expectedFingerprint 固定比对）；
     *        手动 IP/发现/自动重连等非扫码路径必须传 false，空指纹直接拒绝。
     */
    suspend fun connect(
        host: String,
        port: Int,
        expectedFingerprint: String?,
        deviceId: String,
        deviceName: String,
        pairingCode: String? = null,
        isRelay: Boolean = false,
        sessionToken: String? = null,
        allowTofu: Boolean = false,
        scope: CoroutineScope
    ) {
        this.expectedFingerprint = expectedFingerprint
        this._host = host
        this._port = port
        this._deviceId = deviceId
        this._deviceName = deviceName
        this._pairingCode = pairingCode
        this._isRelay = isRelay
        this._sessionToken = sessionToken
        this._allowTofu = allowTofu
        this.reconnectAttempt = 0
        // [TASK-PRELAUNCH-FIX-SCAN] 新连接开始即清除上一次的拒绝提示
        _handshakeRejection.value = null

        // [REQ] 持久化连接配置，供服务/应用重启后自动重连（局域网与公网中继通用）
        persistConnectionConfig()

        disconnect()  // 断开旧连接

        connectionJob = scope.launch(Dispatchers.IO) {
            performConnect(host, port)
        }
    }

    /** 持久化连接目标（宿主/端口/配对码/中继模式/指纹/设备ID/会话令牌） */
    private fun persistConnectionConfig() {
        try {
            val prefs = XiaopacaiApp.instance
                .getSharedPreferences("guardian_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString("relay_host", _host)
                .putInt("relay_port", _port)
                // [SEC-P1] 配对码/会话令牌为握手凭据，经 AndroidKeyStore AES-GCM 加密后落盘（红线 R4.1）
                .putString("relay_pairing_code",
                    _pairingCode?.takeIf { it.isNotBlank() }?.let { KeyStoreManager.encryptPrefsValue(it) } ?: "")
                .putBoolean("relay_mode", _isRelay)
                .putString("relay_fingerprint", expectedFingerprint ?: "")
                .putString("device_id", _deviceId)
                // [SEC-K2][SEC-P1] 会话令牌随配置持久化（加密存储），服务/应用重启后重连仍可携带
                .putString("relay_session_token",
                    _sessionToken?.takeIf { it.isNotBlank() }?.let { KeyStoreManager.encryptPrefsValue(it) } ?: "")
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "持久化连接配置失败: ${e.message}")
        }
    }

    /**
     * 执行 TCP + TLS 连接
     */
    private suspend fun performConnect(host: String, port: Int) {
        _connectionState.value = P2PConnectionState.CONNECTING

        try {
            // 1. 创建 TCP 连接
            socket = Socket()
            socket!!.connect(InetSocketAddress(host, port), 10_000)  // 10 秒超时
            socket!!.soTimeout = 0

            // 2. 建立 TLS 连接（双向认证）
            val sslSocket = createTlsSocket(socket!!)
            sslSocket.startHandshake()

            // 3. 校验证书指纹
            if (!verifyCertificateFingerprint(sslSocket)) {
                _connectionState.value = P2PConnectionState.DISCONNECTED
                Log.e(TAG, "证书指纹不匹配，连接拒绝")
                return
            }

            // 4. 获取 IO 流
            outputStream = DataOutputStream(BufferedOutputStream(sslSocket.outputStream))
            inputStream = DataInputStream(BufferedInputStream(sslSocket.inputStream))

            // 5. 发送握手消息
            _connectionState.value = P2PConnectionState.HANDSHAKING
            sendHandshake()

            // [TASK-PRELAUNCH-FIX-SCAN] 6. 等待握手结果（首个响应帧，约 5 秒）：
            // - 确定性拒绝（handshake_rejected / error + 已知错误码）→ 清配对码、
            //   停止自动重连、回配对界面提示原因（生产事故根因：旧码重试风暴打满 K3 限速）
            // - 成功帧（policy_update 等）→ 按原流程 CONNECTED，帧补入消息流供 SyncManager 处理
            // - 超时/空帧（旧版家长端无握手回执）→ 按原流程 CONNECTED
            socket!!.soTimeout = 5_000
            val firstFrame = try {
                readMessage()
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "握手响应超时，按成功处理（旧版家长端可能无回执）")
                null
            } catch (e: Exception) {
                Log.d(TAG, "读取握手响应失败，按成功处理: ${e.message}")
                null
            }
            socket!!.soTimeout = 0

            if (firstFrame != null) {
                val rejection = parseHandshakeRejection(firstFrame)
                // [TASK-PRELAUNCH-FIX-RATELIMIT] 限速是临时性的：清配对码（旧码多为
                // 失败根源）、保留连接配置，进入长指数退避自动重连——若按确定性拒绝
                // 停止重连，用户需手动重扫码；若走 1s 短退避，会把服务端限速窗口
                // 反复打满形成永久自锁（122 信闭环根因）
                if (rejection != null && isRateLimitedRejectionCode(rejection.code)) {
                    handleRateLimitedRejection(rejection, host, port)
                    return
                }
                if (rejection != null && isDeterministicRejectionCode(rejection.code)) {
                    handleDeterministicRejection(rejection)
                    return  // 不进入 receiveLoop，不 scheduleReconnect
                }
                // 成功帧：与 receiveLoop 同路径处理（heartbeat_ack 重置计数，其余补入消息流）
                if (firstFrame.type == "heartbeat_ack") heartbeatMissCount = 0
                _receivedMessages.value = _receivedMessages.value + firstFrame
            }

            // [TASK-PRELAUNCH-FIX-SCAN] D3 根因消除：握手成功后清除配对码（含加密持久化副本），
            // 之后的重连/自动重连不再携带旧配对码，仅凭证书指纹与中继会话令牌放行
            _pairingCode = null
            clearPersistedPairingCode()

            // 7. 启动心跳
            startHeartbeat()

            _connectionState.value = P2PConnectionState.CONNECTED
            reconnectAttempt = 0  // 重置重连计数
            rateLimitBackoffStep = 0  // [TASK-PRELAUNCH-FIX-RATELIMIT] 成功即归零退避步数

            // 8. 开始接收消息循环
            receiveLoop()

        } catch (e: Exception) {
            Log.e(TAG, "连接失败: ${e.message}", e)
            _connectionState.value = P2PConnectionState.DISCONNECTED
            scheduleReconnect(host, port)
        }
    }

    /**
     * 创建 TLS Socket（双向认证）
     * 由于是自签名证书，信任所有服务器证书，但记录指纹用于配对校验。
     * [SEC-K1] 同时提交本机客户端身份证书（mTLS），服务端以证书指纹固定设备身份。
     */
    private fun createTlsSocket(socket: Socket): SSLSocket {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        // [SEC-K1] 客户端身份证书（持久化，指纹稳定）
        val (privateKey, certChain) = getOrCreateClientCertificate()
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("p2p_client", privateKey, charArrayOf(), certChain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, charArrayOf())

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())

        val sslSocket = sslContext.socketFactory.createSocket(
            socket,
            socket.inetAddress.hostAddress,
            socket.port,
            true  // autoClose
        ) as SSLSocket

        // 启用 TLS 1.3 优先、TLS 1.2 回退（部分 Windows/Schannel 环境不支持 TLS 1.3）
        sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        sslSocket.enabledCipherSuites = sslSocket.enabledCipherSuites
            .filter {
                it.startsWith("TLS_AES") || it.startsWith("TLS_CHACHA") ||
                    (it.startsWith("TLS_ECDHE") && it.contains("AES") && it.contains("GCM"))
            }
            .toTypedArray()

        return sslSocket
    }

    /**
     * 验证 TLS 证书指纹
     * 首次配对时记录指纹；后续连接比对已记录的指纹
     */
    private fun verifyCertificateFingerprint(sslSocket: SSLSocket): Boolean {
        val certs = sslSocket.session.peerCertificates
        if (certs.isEmpty()) return false

        val cert = certs[0] as X509Certificate
        val actualFingerprint = computeSha256Fingerprint(cert.encoded)

        connectedFingerprint = actualFingerprint

        return when {
            // [SEC-P1] 首次连接（无期望指纹）：
            // - 扫码引导（allowTofu=true，且旧服务端二维码未携带指纹）允许 TOFU 并立即持久化；
            // - 手动 IP / 发现 / 自动重连等非扫码路径一律拒绝，防首连中间人（红线 R3.x）。
            expectedFingerprint.isNullOrEmpty() -> {
                if (!_allowTofu) {
                    Log.e(TAG, "未提供期望指纹且非扫码引导流程，拒绝首连（防中间人）")
                    return false
                }
                Log.i(TAG, "扫码引导首连（TOFU），证书指纹: $actualFingerprint")
                expectedFingerprint = actualFingerprint
                // [SEC-R3.3] TOFU 采纳后立即持久化，重启/下次连接继续固定比对
                persistServerFingerprint(actualFingerprint)
                true
            }
            // 后续连接：比对指纹
            else -> {
                val match = actualFingerprint == expectedFingerprint
                if (!match) {
                    Log.e(TAG, "证书指纹不匹配！期望: $expectedFingerprint, 实际: $actualFingerprint")
                }
                match
            }
        }
    }

    /** [SEC-R3.3] 持久化服务端证书指纹（TOFU 采纳时调用，与 relay_fingerprint 键一致） */
    private fun persistServerFingerprint(fingerprint: String) {
        try {
            XiaopacaiApp.instance
                .getSharedPreferences("guardian_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("relay_fingerprint", fingerprint).apply()
        } catch (e: Exception) {
            Log.w(TAG, "持久化服务端证书指纹失败: ${e.message}")
        }
    }

    /** 计算证书 SHA-256 指纹 */
    private fun computeSha256Fingerprint(derEncoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(derEncoded)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // === 消息收发 ===

    /** 发送握手消息 */
    private fun sendHandshake() {
        val payload = mutableMapOf<String, Any>(
            "version" to "1.0",
            "deviceId" to _deviceId,
            "deviceName" to _deviceName,
            "deviceType" to "android",
            "timestamp" to System.currentTimeMillis() / 1000
        )
        // 仅在提供了非空配对码时携带，避免空串触发家长端校验
        _pairingCode?.takeIf { it.isNotBlank() }?.let { payload["pairingCode"] = it }
        // [TASK-OPT-12-P4-DEEPEN] Web 云端中继模式：携带 relay 标志
        if (_isRelay) {
            payload["relay"] = true
        }
        // [SEC-K2] 家长端中继握手凭据（服务端与 relay_sessions 比对）
        _sessionToken?.let { payload["sessionToken"] = it }
        val message = P2PMessage(
            type = MessageType.HANDSHAKE.value,
            payload = payload
        )
        sendMessage(message)
    }

    /** 发送 JSON 消息（长度前缀帧），返回是否成功 */
    fun sendMessage(message: P2PMessage): Boolean {
        return try {
            // P2P-FIX: 检查连接状态，未连接时拒绝发送
            if (_connectionState.value != P2PConnectionState.CONNECTED &&
                _connectionState.value != P2PConnectionState.HANDSHAKING) {
                Log.w(TAG, "未连接，丢弃消息: ${message.type}")
                return false
            }

            val jsonBytes = message.toJsonBytes()
            // 4 字节大端长度 + JSON 消息体
            val frame = ByteBuffer.allocate(4 + jsonBytes.size)
                .putInt(jsonBytes.size)
                .put(jsonBytes)
                .array()

            outputStream?.write(frame)
            outputStream?.flush()

            Log.d(TAG, "已发送: ${message.type}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送失败: ${message.type}", e)
            false
        }
    }

    /** 接收消息循环（阻塞当前协程） */
    private suspend fun receiveLoop() {
        while (currentCoroutineContext().isActive && _connectionState.value == P2PConnectionState.CONNECTED) {
            try {
                val message = readMessage() ?: continue

                Log.d(TAG, "收到消息: ${message.type}")
                _receivedMessages.value = _receivedMessages.value + message

                // 处理心跳响应
                if (message.type == "heartbeat_ack") {
                    heartbeatMissCount = 0
                }

            } catch (e: EOFException) {
                Log.w(TAG, "连接已关闭")
                break
            } catch (e: Exception) {
                Log.e(TAG, "接收消息异常: ${e.message}")
                break
            }
        }

        // 接收循环结束 → 断线
        if (_connectionState.value == P2PConnectionState.CONNECTED) {
            _connectionState.value = P2PConnectionState.DISCONNECTED
            // 触发重连
            socket?.inetAddress?.hostAddress?.let { host ->
                socket?.port?.let { port ->
                    scheduleReconnect(host, port)
                }
            }
        }
    }

    /** 从输入流读取一个完整消息帧 */
    private fun readMessage(): P2PMessage? {
        val input = inputStream ?: return null

        // 读取 4 字节长度
        val lengthBytes = ByteArray(4)
        input.readFully(lengthBytes)
        val length = ByteBuffer.wrap(lengthBytes).int

        if (length <= 0 || length > 1_048_576) {  // 最大 1MB
            Log.w(TAG, "无效的消息长度: $length")
            return null
        }

        // 读取消息体
        val bodyBytes = ByteArray(length)
        input.readFully(bodyBytes)
        val json = String(bodyBytes, Charsets.UTF_8)

        return P2PMessage.fromJson(json)
    }

    // === 心跳机制 ===

    /** 启动心跳定时器 */
    private fun startHeartbeat() {
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)

                if (_connectionState.value == P2PConnectionState.CONNECTED) {
                    sendMessage(P2PMessage(
                        type = MessageType.HEARTBEAT.value,
                        payload = mapOf("timestamp" to System.currentTimeMillis() / 1000)
                    ))
                    heartbeatMissCount++

                    // 连续无响应 → 断线
                    if (heartbeatMissCount >= HEARTBEAT_TIMEOUT_COUNT) {
                        Log.w(TAG, "心跳超时（${heartbeatMissCount}次无响应），断开连接")
                        disconnect()
                    }
                }
            }
        }
    }

    // === 自动重连 ===

    /** 调度断线重连（指数退避） */
    private fun scheduleReconnect(host: String, port: Int) {
        if (_connectionState.value == P2PConnectionState.RECONNECTING) return

        val delay = minOf(
            RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempt),
            RECONNECT_MAX_DELAY_MS
        )
        reconnectAttempt++

        _connectionState.value = P2PConnectionState.RECONNECTING
        Log.i(TAG, "将在 ${delay}ms 后重连（第 ${reconnectAttempt} 次）")

        CoroutineScope(Dispatchers.IO).launch {
            delay(delay)
            if (_connectionState.value == P2PConnectionState.RECONNECTING) {
                performConnect(host, port)
            }
        }
    }

    // === 生命周期 ===

    /**
     * [TASK-PRELAUNCH-FIX-SCAN] 确定性握手拒绝处理：
     * 清除配对码（含持久化副本）、关闭连接、写入拒绝状态供 UI 提示，且不调度重连。
     * 用户需在家长端操作（解绑/生成新配对码）后重新扫码，重试旧凭据无意义。
     */
    private fun handleDeterministicRejection(rejection: HandshakeRejection) {
        Log.w(TAG, "握手被拒（确定性，code=${rejection.code}）：${rejection.reason}，停止自动重连")
        _pairingCode = null
        clearPersistedPairingCode()
        _handshakeRejection.value = rejection
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        _connectionState.value = P2PConnectionState.DISCONNECTED
    }

    /**
     * [TASK-PRELAUNCH-FIX-RATELIMIT] 限速拒绝处理：
     * 清配对码（含持久化副本，旧码多为失败根源）、保留连接配置与自动重连，
     * 写入拒绝状态供 UI 显示「稍后自动重试」，随后进入长指数退避（首 60s、上限 10 分钟）。
     * 与确定性拒绝的区别：不停重连、不落 ERROR 终态（冷却后应自动恢复）。
     */
    private fun handleRateLimitedRejection(rejection: HandshakeRejection, host: String, port: Int) {
        Log.w(TAG, "握手被限速（code=${rejection.code}）：${rejection.reason}，进入长退避自动重连")
        _pairingCode = null
        clearPersistedPairingCode()
        _handshakeRejection.value = rejection
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        scheduleRateLimitReconnect(host, port)
    }

    /** [TASK-PRELAUNCH-FIX-RATELIMIT] 限速长退避重连（指数：60s→120s→240s→…→600s 封顶） */
    private fun scheduleRateLimitReconnect(host: String, port: Int) {
        val delay = rateLimitBackoffDelayMs(rateLimitBackoffStep)
        rateLimitBackoffStep++

        _connectionState.value = P2PConnectionState.RECONNECTING
        Log.i(TAG, "限速退避：将在 ${delay}ms 后重连（第 ${rateLimitBackoffStep} 次）")

        CoroutineScope(Dispatchers.IO).launch {
            delay(delay)
            if (_connectionState.value == P2PConnectionState.RECONNECTING) {
                performConnect(host, port)
            }
        }
    }

    /** [TASK-PRELAUNCH-FIX-SCAN] 清除加密持久化的配对码（不触碰其余连接配置） */
    private fun clearPersistedPairingCode() {
        try {
            XiaopacaiApp.instance
                .getSharedPreferences("guardian_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("relay_pairing_code", "").apply()
        } catch (e: Exception) {
            Log.w(TAG, "清除持久化配对码失败: ${e.message}")
        }
    }

    /** 断开连接 */
    fun disconnect() {
        connectionJob?.cancel()
        heartbeatJob?.cancel()
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        _connectionState.value = P2PConnectionState.DISCONNECTED
        heartbeatMissCount = 0
    }

    /** 获取当前连接的证书指纹（配对确认用） */
    fun getConnectedFingerprint(): String? = connectedFingerprint
}
