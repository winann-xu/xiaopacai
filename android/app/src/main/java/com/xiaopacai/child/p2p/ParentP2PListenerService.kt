package com.xiaopacai.child.p2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.XiaopacaiApp
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
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
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

/**
 * [TASK-ROLE-P1] 家长端 P2P 入站监听服务
 *
 * Android 前台服务，接受儿童端的 TLS 连接：
 * - TCP 监听端口 9527
 * - TLS 1.3/1.2（自签名证书，KeyStore 持久化，指纹稳定）
 * - 4 字节大端长度前缀 + JSON 帧协议（与儿童端 P2PConnectionService 兼容）
 * - 消息处理：handshake / usage_report / heartbeat / announcement_push
 *   [TASK-OPT-12-P1] + diagnostics_report（诊断上报）/ announcement_ack（公告确认回执）
 * - 6 位配对码生成与校验
 *
 * 对标 Windows 端 P2PListenerService，协议完全兼容。
 */
class ParentP2PListenerService : Service() {

    companion object {
        private const val TAG = "ParentP2PListener"
        const val DEFAULT_PORT = 9527
        private const val CERT_ALIAS = "xiaopacai_p2p_server"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "channel_parent_p2p"
        private const val READ_TIMEOUT_MS = 60_000

        // 配对码
        private const val PAIRING_CODE_LENGTH = 6
        private const val MAX_PAIRING_ATTEMPTS = 5
        private const val PAIRING_LOCKOUT_MS = 5 * 60 * 1000L

        // [SEC-P1] 并发连接上限：防未认证连接耗尽线程/内存（红线 R5.x 资源保护）
        private const val MAX_CONNECTIONS = 20

        @Volatile
        var instance: ParentP2PListenerService? = null

        /** 是否正在运行 */
        val isRunning: Boolean get() = instance != null

        /**
         * 启动监听服务
         */
        fun start(context: Context) {
            val intent = Intent(context, ParentP2PListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止监听服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, ParentP2PListenerService::class.java)
            context.stopService(intent)
        }
    }

    // === 运行时状态 ===
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** 已连接的儿童端设备信息 */
    private val connectedDevices = ConcurrentHashMap<String, ChildDeviceInfo>()
    /** 设备 ID -> 输出流（用于主动推送公告等下行消息） */
    private val deviceStreams = ConcurrentHashMap<String, DataOutputStream>()
    /** 儿童端连接任务（每个连接一个协程） */
    private val clientJobs = ConcurrentHashMap<String, Job>()
    /** [SEC-P1] 当前活动连接数（含未完成握手的连接），用于连接上限控制 */
    @Volatile private var activeConnections = 0

    /** 当前配对码 */
    @Volatile private var currentPairingCode: String? = null
    @Volatile private var pairingCodeGeneratedAt: Long = 0
    private var failedPairingAttempts = 0
    private var pairingLockoutUntil: Long = 0

    /** 已加载的 SSLContext（单例，避免重复加载） */
    @Volatile private var sslContext: SSLContext? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "P2P 监听服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("P2P 监听已启动", "等待儿童端连接...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        instance = null
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "P2P 监听服务已销毁")
    }

    // ==================== 证书管理 ====================

    /**
     * 获取或生成 P2P 服务端证书（普通 EC 密钥 + PKCS12 持久化）
     *
     * 不使用 AndroidKeyStore：Conscrypt TLS 握手签名走原始摘要路径（NONEwithECDSA），
     * AndroidKeyStore 密钥不支持 → Incompatible digest 握手失败。
     * 普通密钥 + 自签名证书 + PKCS12 落盘（app 私有目录），重启后指纹稳定（对标 Windows LEGACY-e）。
     */
    private fun getOrCreateCertificate(): Pair<PrivateKey, Array<X509Certificate>> {
        val pfxFile = File(filesDir, "p2p_server.pfx")
        val pwdFile = File(filesDir, "p2p_server.pwd")

        // 1) 已有持久化证书：加载
        if (pfxFile.exists() && pwdFile.exists()) {
            try {
                val pwd = pwdFile.readText().trim()
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(pfxFile).use { ks.load(it, pwd.toCharArray()) }
                val entry = ks.getEntry("p2p", KeyStore.PasswordProtection(pwd.toCharArray())) as KeyStore.PrivateKeyEntry
                Log.i(TAG, "已加载持久化 P2P 证书: ${computeFingerprint(entry.certificate as X509Certificate)}")
                return Pair(entry.privateKey, entry.certificateChain as Array<X509Certificate>)
            } catch (e: Exception) {
                Log.w(TAG, "加载 P2P 证书失败，重新生成: ${e.message}")
            }
        }

        // 2) 生成普通 EC P-256 密钥 + BouncyCastle 自签名证书
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val cert = generateSelfSignedCertificate(keyPair)

        // 3) 持久化 PKCS12（密码文件同目录）
        try {
            val pwd = java.util.UUID.randomUUID().toString()
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry("p2p", keyPair.private, pwd.toCharArray(), arrayOf(cert))
            FileOutputStream(pfxFile).use { ks.store(it, pwd.toCharArray()) }
            pwdFile.writeText(pwd)
        } catch (e: Exception) {
            Log.w(TAG, "持久化 P2P 证书失败: ${e.message}")
        }
        Log.i(TAG, "已生成新的 P2P 服务端证书: ${computeFingerprint(cert)}")
        return Pair(keyPair.private, arrayOf(cert))
    }

    /**
     * 用 BouncyCastle 生成自签名证书（SHA256withECDSA，10 年，serverAuth EKU）
     */
    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Xiaopacai P2P Server")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            java.math.BigInteger.valueOf(now),
            java.util.Date(now - 86400_000L),
            java.util.Date(now + 10L * 365 * 24 * 3600 * 1000),
            subject,
            keyPair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        builder.addExtension(Extension.extendedKeyUsage, true, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /**
     * 获取服务端 SSLContext
     */
    private fun getServerSslContext(): SSLContext {
        return sslContext ?: synchronized(this) {
            sslContext ?: run {
                val (privateKey, certChain) = getOrCreateCertificate()

                // 信任所有客户端证书（自签名环境，指纹校验在应用层完成）
                val trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }

                val keyStore = KeyStore.getInstance("PKCS12")
                keyStore.load(null, null)
                keyStore.setKeyEntry("p2p", privateKey, charArrayOf(), certChain)

                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keyStore, charArrayOf())

                val ctx = SSLContext.getInstance("TLSv1.3")
                ctx.init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
                sslContext = ctx
                ctx
            }
        }
    }

    /**
     * 计算证书 SHA-256 指纹（与儿童端 P2PConnectionService 兼容）
     */
    fun getCertificateFingerprint(): String {
        val (_, certChain) = getOrCreateCertificate()
        return computeFingerprint(certChain[0])
    }

    private fun computeFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ==================== TCP 监听 ====================

    private fun startListening() {
        acceptJob = scope.launch {
            try {
                val sslCtx = getServerSslContext()
                serverSocket = ServerSocket()
                serverSocket!!.reuseAddress = true
                serverSocket!!.bind(InetSocketAddress(DEFAULT_PORT))
                Log.i(TAG, "P2P 监听已启动: 0.0.0.0:$DEFAULT_PORT")

                updateNotification("监听中", "端口 $DEFAULT_PORT | 已连接 ${connectedDevices.size} 台设备")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        Log.i(TAG, "新连接: ${clientSocket.inetAddress.hostAddress}")

                        // [SEC-P1] 连接上限：超过则立即关闭，防连接耗尽
                        if (activeConnections >= MAX_CONNECTIONS) {
                            Log.w(TAG, "连接数已达上限($MAX_CONNECTIONS)，拒绝新连接: ${clientSocket.inetAddress.hostAddress}")
                            try { clientSocket.close() } catch (_: Exception) {}
                            continue
                        }

                        launch {
                            handleClient(clientSocket, sslCtx)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "接受连接异常: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "监听启动失败: ${e.message}", e)
            }
        }
    }

    private fun stopListening() {
        acceptJob?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        // 关闭所有客户端连接
        clientJobs.values.forEach { it.cancel() }
        clientJobs.clear()
        connectedDevices.clear()
    }

    // ==================== 客户端处理 ====================

    private suspend fun handleClient(socket: Socket, sslCtx: SSLContext) {
        var clientId = "unknown-${System.currentTimeMillis()}"
        val clientJob = coroutineContext[Job]
        activeConnections++
        // [SEC-P1] 握手门控：handshake 认证通过前，忽略其他一切消息
        var handshakeAccepted = false

        try {
            socket.soTimeout = READ_TIMEOUT_MS

            // 1. TLS 握手（服务端模式）
            val sslSocket = sslCtx.socketFactory.createSocket(
                socket,
                socket.inetAddress.hostAddress,
                socket.port,
                true  // auto close
            ) as SSLSocket

            sslSocket.useClientMode = false
            sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
            sslSocket.enabledCipherSuites = sslSocket.enabledCipherSuites
                .filter {
                    it.startsWith("TLS_AES") || it.startsWith("TLS_CHACHA") ||
                        (it.startsWith("TLS_ECDHE") && it.contains("AES") && it.contains("GCM"))
                }
                .toTypedArray()

            sslSocket.startHandshake()
            Log.i(TAG, "TLS 握手完成: ${socket.inetAddress.hostAddress}")

            // 记录客户端证书指纹
            val clientCertFingerprint = getPeerFingerprint(sslSocket)

            // 2. IO 流
            val input = DataInputStream(BufferedInputStream(sslSocket.inputStream))
            val output = DataOutputStream(BufferedOutputStream(sslSocket.outputStream))

            // 3. 消息循环
            while (currentCoroutineContext().isActive && !sslSocket.isClosed) {
                try {
                    val message = readMessage(input) ?: break

                    Log.d(TAG, "收到: ${message.type} 来自 ${socket.inetAddress.hostAddress}")

                    // [SEC-P1] 握手认证通过前拒绝处理任何非握手消息（红线 R3.x 零认证窗口）
                    if (message.type != "handshake" && !handshakeAccepted) {
                        Log.w(TAG, "握手未完成，忽略消息: ${message.type} (${socket.inetAddress.hostAddress})")
                        continue
                    }

                    val response: P2PMessage?
                    if (message.type == "handshake") {
                        val (resp, accepted) = handleHandshake(message, clientId, clientCertFingerprint)
                        handshakeAccepted = accepted
                        response = resp
                    } else {
                        response = handleMessage(message, clientId, clientCertFingerprint)
                    }
                    if (response != null) {
                        sendMessage(output, response)
                    }

                    // 更新 clientId（handshake 消息中获取）
                    if (message.type == "handshake" && handshakeAccepted) {
                        clientId = message.payload["deviceId"]?.toString() ?: clientId
                        deviceStreams[clientId] = output
                        // 记录设备
                        connectedDevices[clientId] = ChildDeviceInfo(
                            deviceId = clientId,
                            deviceName = message.payload["deviceName"]?.toString() ?: "未知设备",
                            ip = socket.inetAddress.hostAddress ?: "unknown",
                            certFingerprint = clientCertFingerprint,
                            lastSeen = System.currentTimeMillis()
                        )
                        updateNotification("监听中", "端口 $DEFAULT_PORT | 已连接 ${connectedDevices.size} 台设备")
                    }

                } catch (e: EOFException) {
                    Log.i(TAG, "客户端 $clientId 断开连接")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "读取消息异常($clientId): ${e.message}")
                    break
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "客户端处理异常: ${e.message}")
        } finally {
            activeConnections--
            // 标记设备离线
            connectedDevices.remove(clientId)
            deviceStreams.remove(clientId)
            clientJobs.remove(clientId)
            updateNotification("监听中", "端口 $DEFAULT_PORT | 已连接 ${connectedDevices.size} 台设备")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ==================== 消息帧读写 ====================

    /**
     * 读取一个 4 字节大端长度前缀 + JSON 体消息帧
     */
    private fun readMessage(input: DataInputStream): P2PMessage? {
        val lengthBytes = ByteArray(4)
        input.readFully(lengthBytes)
        val length = ByteBuffer.wrap(lengthBytes).int

        if (length <= 0 || length > 1_048_576) {  // 最大 1MB
            Log.w(TAG, "无效消息长度: $length")
            return null
        }

        val bodyBytes = ByteArray(length)
        input.readFully(bodyBytes)
        val json = String(bodyBytes, Charsets.UTF_8)

        return P2PMessage.fromJson(json)
    }

    /**
     * 发送 4 字节大端长度前缀 + JSON 体消息帧
     */
    private fun sendMessage(output: DataOutputStream, message: P2PMessage): Boolean {
        return try {
            val jsonBytes = message.toJsonBytes()
            val frame = ByteBuffer.allocate(4 + jsonBytes.size)
                .putInt(jsonBytes.size)
                .put(jsonBytes)
                .array()

            // DataOutputStream 非线程安全：心跳与公告推送可能并发写同一流，需同步
            synchronized(output) {
                output.write(frame)
                try {
                    output.flush()
                } catch (e: Exception) {
                    // flush 竞态（如对端同时断开）：数据已交给内核，不判定为发送失败
                    Log.w(TAG, "flush 异常（忽略）: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败: ${e.message}")
            false
        }
    }

    // ==================== 消息处理 ====================

    /**
     * 处理收到的 P2P 消息，返回响应消息（null 表示无响应）
     */
    private fun handleMessage(
        message: P2PMessage,
        deviceId: String,
        certFingerprint: String
    ): P2PMessage? {
        return when (message.type) {
            "handshake" -> handleHandshake(message, deviceId, certFingerprint).first
            "usage_report" -> handleUsageReport(message, deviceId)
            "announcement_push" -> handleAnnouncementFromChild(message, deviceId)
            "heartbeat" -> handleHeartbeat()
            "heartbeat_ack" -> null  // 由心跳机制处理
            // [TASK-OPT-12-P1] 协议扩展：故障诊断上报 / 紧急公告确认回执
            "diagnostics_report" -> handleDiagnosticsReport(message, deviceId)
            "announcement_ack" -> handleAnnouncementAck(message, deviceId)
            else -> {
                Log.d(TAG, "未处理的消息类型: ${message.type}")
                null
            }
        }
    }

    /**
     * 处理设备握手（[SEC-P1] 认证门控，红线 R3.x）
     *
     * 认证规则：
     * 1. 已注册设备：本次连接证书指纹必须与注册指纹一致（设备身份以证书指纹为锚点，
     *    防伪造设备冒用他人 deviceId 劫持策略/数据流）；
     * 2. 新设备：首次连接必须携带家长端生成的有效配对码（配对窗口外拒绝一切未认证连接，
     *    消除"无配对码时任意客户端可接入"的零认证窗口）；
     * 3. 认证通过后注册/更新设备并下发策略。
     *
     * @return (响应消息, 是否认证通过)
     */
    private fun handleHandshake(
        message: P2PMessage,
        deviceId: String,
        certFingerprint: String
    ): Pair<P2PMessage, Boolean> {
        val payload = message.payload
        val deviceName = payload["deviceName"]?.toString() ?: "未知设备"
        // 用握手包中的真实设备 ID（参数 deviceId 是连接临时 ID，会导致每次重连重复注册）
        val realDeviceId = payload["deviceId"]?.toString()?.takeIf { it.isNotBlank() } ?: deviceId
        val pairingCode = payload["pairingCode"]?.toString()

        fun reject(reason: String): Pair<P2PMessage, Boolean> {
            Log.w(TAG, "握手拒绝: 设备=$realDeviceId, 原因=$reason")
            return Pair(
                P2PMessage(
                    type = "handshake_response",
                    payload = mapOf(
                        "status" to "rejected",
                        "reason" to reason
                    )
                ),
                false
            )
        }

        // [SEC-P1] 规则 1/2：已注册设备指纹比对，新设备必须有效配对码
        val registeredFingerprint = getRegisteredFingerprint(realDeviceId)
        if (registeredFingerprint != null) {
            if (!MessageDigest.isEqual(
                    registeredFingerprint.toByteArray(Charsets.UTF_8),
                    certFingerprint.toByteArray(Charsets.UTF_8)
                )
            ) {
                return reject("设备身份指纹与已注册值不符")
            }
        } else {
            if (pairingCode.isNullOrBlank() || !verifyPairingCode(pairingCode)) {
                return reject(
                    if (pairingCode.isNullOrBlank()) "首次连接需要家长端生成的配对码"
                    else "配对码错误或已过期"
                )
            }
        }

        // 注册/更新设备
        connectedDevices[realDeviceId] = ChildDeviceInfo(
            deviceId = realDeviceId,
            deviceName = deviceName,
            ip = connectedDevices[realDeviceId]?.ip ?: "unknown",
            certFingerprint = certFingerprint,
            lastSeen = System.currentTimeMillis()
        )

        // 持久化到数据库（按真实 ID 去重），并清理历史遗留的临时 ID 记录
        persistDevice(realDeviceId, deviceName, certFingerprint)
        cleanupUnknownDevices()

        // 响应：下发当前策略
        val policies = getActivePolicies(realDeviceId)

        Log.i(TAG, "设备握手成功: $realDeviceId ($deviceName)")
        updateNotification("监听中", "端口 $DEFAULT_PORT | 已连接 ${connectedDevices.size} 台设备")

        return Pair(
            P2PMessage(
                type = "policy_update",
                payload = mapOf(
                    "deviceId" to realDeviceId,
                    "policies" to policies.toString(),
                    "status" to "accepted",
                    "timestamp" to (System.currentTimeMillis() / 1000)
                )
            ),
            true
        )
    }

    /**
     * 清理历史遗留的临时 ID（unknown-*）设备记录，避免重复条目
     */
    private fun cleanupUnknownDevices() {
        try {
            val db = XiaopacaiApp.instance.database.getWritable(getPassphrase())
            try {
                val removed = db.delete("device_registry", "device_id LIKE 'unknown-%'", null)
                if (removed > 0) Log.i(TAG, "已清理 $removed 条临时设备记录")
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理临时设备记录失败: ${e.message}")
        }
    }

    /**
     * 处理使用时长报告
     * 写入本地 SQLCipher 数据库。
     */
    private fun handleUsageReport(message: P2PMessage, deviceId: String): P2PMessage? {
        try {
            val recordsStr = message.payload["records"]?.toString() ?: return null
            val recordsArray = org.json.JSONArray(recordsStr)
            val db = XiaopacaiApp.instance.database.getWritable(getPassphrase())
            try {
                db.beginTransaction()
                try {
                    for (i in 0 until recordsArray.length()) {
                        val obj = recordsArray.getJSONObject(i)
                        val packageName = obj.optString("packageName", "")
                        val appName = obj.optString("appName", "")
                        val date = obj.optString("date", "")
                        val totalMinutes = obj.optLong("totalMinutes", 0)
                        val category = obj.optString("category", "other")

                        db.execSQL("""
                            INSERT OR REPLACE INTO parent_usage_summary
                            (device_id, package_name, app_name, date, total_minutes, category, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(), arrayOf(
                            deviceId, packageName, appName, date,
                            totalMinutes.toString(), category,
                            (System.currentTimeMillis() / 1000).toString()
                        ))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.close()
            }

            Log.i(TAG, "已接收 $deviceId 的 ${recordsArray.length()} 条使用记录")

            return P2PMessage(
                type = "sync_ack",
                payload = mapOf(
                    "deviceId" to deviceId,
                    "syncedCount" to recordsArray.length(),
                    "timestamp" to (System.currentTimeMillis() / 1000)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "处理使用报告失败: ${e.message}", e)
            return null
        }
    }

    /**
     * 处理来自儿童端的公告确认
     */
    private fun handleAnnouncementFromChild(message: P2PMessage, deviceId: String): P2PMessage? {
        val announcementId = message.payload["announcementId"]?.toString() ?: return null
        val isRead = (message.payload["isRead"] as? Boolean) ?: true
        Log.d(TAG, "公告确认: $announcementId, device=$deviceId, read=$isRead")
        return null // 无响应
    }

    /**
     * [TASK-OPT-12-P1] 处理儿童端故障诊断信息上报（diagnostics_report）
     *
     * 消息格式：{type, deviceId, diagnostics: {appVersion, androidVersion, deviceModel,
     * manufacturer, permissionStatus, serviceStatus, recentCrashes, p2pHistory,
     * dbSizeBytes, networkType}}
     *
     * P1 仅做协议解析与日志记录（供排障）；完整落库/转发由 P3 家长端、
     * P4 Web 3.0 诊断模块实现。当前无响应消息（儿童端按连接状态重传）。
     */
    private fun handleDiagnosticsReport(message: P2PMessage, deviceId: String): P2PMessage? {
        val diagnostics = message.payload["diagnostics"]?.toString()
            ?: return null
        return try {
            val obj = org.json.JSONObject(diagnostics)
            Log.i(TAG, "收到诊断信息: device=$deviceId, " +
                "appVersion=${obj.optString("appVersion", "?")}, " +
                "androidVersion=${obj.optString("androidVersion", "?")}, " +
                "deviceModel=${obj.optString("deviceModel", "?")}, " +
                "manufacturer=${obj.optString("manufacturer", "?")}, " +
                "networkType=${obj.optString("networkType", "?")}, " +
                "dbSizeBytes=${obj.optLong("dbSizeBytes", 0)}, " +
                "recentCrashes=${obj.optJSONArray("recentCrashes")?.length() ?: 0}")
            null // 无响应
        } catch (e: Exception) {
            Log.e(TAG, "解析诊断信息失败: ${e.message}")
            null
        }
    }

    /**
     * [TASK-OPT-12-P1] 处理儿童端紧急公告确认回执（announcement_ack）
     *
     * 消息格式：{type, announcementId, deviceId, acknowledgedAt}
     * 家长端收到回执后仅记录日志（公告回执状态展示由 P3 家长端 UI 实现）。
     */
    private fun handleAnnouncementAck(message: P2PMessage, deviceId: String): P2PMessage? {
        val announcementId = message.payload["announcementId"]?.toString() ?: return null
        val acknowledgedAt = (message.payload["acknowledgedAt"] as? Number)?.toLong()
            ?: (System.currentTimeMillis() / 1000)
        Log.i(TAG, "公告确认回执: id=$announcementId, device=$deviceId, acknowledgedAt=$acknowledgedAt")
        return null // 无响应
    }

    /**
     * 处理心跳
     */
    private fun handleHeartbeat(): P2PMessage {
        return P2PMessage(
            type = "heartbeat_ack",
            payload = mapOf("timestamp" to (System.currentTimeMillis() / 1000))
        )
    }

    // ==================== 配对码 ====================

    /**
     * 生成 6 位配对码（有效期 5 分钟）
     */
    fun generatePairingCode(): String {
        val code = String.format("%06d", SecureRandom().nextInt(1_000_000))
        currentPairingCode = code
        pairingCodeGeneratedAt = System.currentTimeMillis()
        failedPairingAttempts = 0
        pairingLockoutUntil = 0
        Log.i(TAG, "已生成配对码: $code（有效期 5 分钟）")
        return code
    }

    /**
     * 获取当前配对码（可能为 null）
     */
    fun getCurrentPairingCode(): String? {
        // 5 分钟过期
        if (currentPairingCode != null &&
            System.currentTimeMillis() - pairingCodeGeneratedAt > 5 * 60 * 1000L) {
            currentPairingCode = null
        }
        return currentPairingCode
    }

    /**
     * 验证配对码
     */
    private fun verifyPairingCode(code: String): Boolean {
        // 检查锁定
        if (pairingLockoutUntil > 0 && System.currentTimeMillis() < pairingLockoutUntil) {
            Log.w(TAG, "配对码验证已锁定")
            return false
        }

        // 检查过期
        if (currentPairingCode == null ||
            System.currentTimeMillis() - pairingCodeGeneratedAt > 5 * 60 * 1000L) {
            currentPairingCode = null
            return false
        }

        val match = MessageDigest.isEqual(
            code.toByteArray(Charsets.UTF_8),
            currentPairingCode!!.toByteArray(Charsets.UTF_8)
        )

        if (match) {
            // 成功：重置计数，一次性使用
            currentPairingCode = null
            failedPairingAttempts = 0
            return true
        } else {
            failedPairingAttempts++
            if (failedPairingAttempts >= MAX_PAIRING_ATTEMPTS) {
                pairingLockoutUntil = System.currentTimeMillis() + PAIRING_LOCKOUT_MS
                Log.w(TAG, "配对码验证锁定 ${PAIRING_LOCKOUT_MS / 60000} 分钟")
            }
            return false
        }
    }

    // ==================== 数据库操作 ====================

    /**
     * [SEC-P1] 查询已注册设备的证书指纹（设备身份锚点，红线 R3.x）
     * @return 注册指纹；设备未注册或指纹为空时返回 null
     */
    private fun getRegisteredFingerprint(deviceId: String): String? {
        return try {
            val db = XiaopacaiApp.instance.database.getReadable(getPassphrase())
            try {
                db.rawQuery(
                    "SELECT cert_fingerprint FROM device_registry WHERE device_id = ?",
                    arrayOf(deviceId)
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询设备注册指纹失败: ${e.message}")
            null
        }
    }

    /**
     * 持久化儿童端设备信息
     */
    private fun persistDevice(deviceId: String, deviceName: String, certFingerprint: String) {
        try {
            val db = XiaopacaiApp.instance.database.getWritable(getPassphrase())
            try {
                db.execSQL("""
                    INSERT OR REPLACE INTO device_registry
                    (device_id, device_name, cert_fingerprint, last_connected_at, is_active)
                    VALUES (?, ?, ?, ?, 1)
                """.trimIndent(), arrayOf(
                    deviceId, deviceName, certFingerprint,
                    (System.currentTimeMillis() / 1000).toString()
                ))
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "设备持久化失败: ${e.message}")
        }
    }

    /**
     * 获取活跃策略（JSON Array）
     */
    private fun getActivePolicies(deviceId: String): org.json.JSONArray {
        val arr = org.json.JSONArray()
        try {
            val db = XiaopacaiApp.instance.database.getReadable(getPassphrase())
            try {
                val cursor = db.rawQuery(
                    """SELECT policy_data FROM parent_policies
                       WHERE is_active = 1 AND (target_device_id = ? OR target_device_id = '')
                       ORDER BY updated_at DESC LIMIT 20""",
                    arrayOf(deviceId)
                )
                cursor.use {
                    while (it.moveToNext()) {
                        arr.put(org.json.JSONObject(it.getString(0)))
                    }
                }
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            // 数据库未就绪
        }
        return arr
    }

    /**
     * 获取数据库密钥
     */
    private fun getPassphrase(): ByteArray {
        return com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(this)
    }

    // ==================== 工具 ====================

    /**
     * 获取对端证书指纹
     */
    private fun getPeerFingerprint(sslSocket: SSLSocket): String {
        return try {
            val certs = sslSocket.session.peerCertificates
            if (certs.isNotEmpty()) {
                computeFingerprint(certs[0] as X509Certificate)
            } else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 获取当前已连接设备列表
     */
    fun getConnectedDevices(): List<ChildDeviceInfo> {
        return connectedDevices.values.toList()
    }

    /**
     * 向指定设备发送公告（供 UI 层调用）
     *
     * [TASK-OPT-12-P1] 新增 requiresAck 参数：紧急公告需儿童确认（全屏置顶），
     * 儿童端确认后回传 announcement_ack 消息。
     */
    fun sendAnnouncementToDevice(
        deviceId: String,
        announcementId: String,
        title: String,
        content: String,
        priority: Int = 0,
        requiresAck: Boolean = false,
        expiresAt: Long = 0
    ): Boolean {
        val output = deviceStreams[deviceId] ?: run {
            Log.w(TAG, "公告推送失败：设备未连接 $deviceId")
            return false
        }
        // 与儿童端 SyncManager.handleAnnouncementPush 兼容的消息格式
        val announcements = "[{\"id\":\"$announcementId\",\"title\":${org.json.JSONObject.quote(title)}," +
            "\"content\":${org.json.JSONObject.quote(content)},\"priority\":$priority," +
            "\"requires_ack\":$requiresAck,\"expires_at\":$expiresAt}]"
        val ok = sendMessage(
            output,
            P2PMessage(
                type = "announcement_push",
                payload = mapOf(
                    "announcements" to announcements,
                    "timestamp" to (System.currentTimeMillis() / 1000)
                )
            )
        )
        Log.i(TAG, "公告已推送: $deviceId -> $title (ok=$ok)")
        return ok
    }

    // ==================== 通知管理 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "P2P 监听服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "家长端 P2P 入站监听运行中"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/**
 * [TASK-ROLE-P1] 儿童端设备信息
 */
data class ChildDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val certFingerprint: String,
    val lastSeen: Long
)
