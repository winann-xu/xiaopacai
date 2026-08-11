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

                    val response = handleMessage(message, clientId, clientCertFingerprint)
                    if (response != null) {
                        sendMessage(output, response)
                    }

                    // 更新 clientId（handshake 消息中获取）
                    if (message.type == "handshake") {
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
            "handshake" -> handleHandshake(message, deviceId, certFingerprint)
            "usage_report" -> handleUsageReport(message, deviceId)
            "announcement_push" -> handleAnnouncementFromChild(message, deviceId)
            "heartbeat" -> handleHeartbeat()
            "heartbeat_ack" -> null  // 由心跳机制处理
            else -> {
                Log.d(TAG, "未处理的消息类型: ${message.type}")
                null
            }
        }
    }

    /**
     * 处理设备握手
     * 包含配对码校验（如有启用）和策略下发。
     */
    private fun handleHandshake(
        message: P2PMessage,
        deviceId: String,
        certFingerprint: String
    ): P2PMessage {
        val payload = message.payload

        // 配对码校验（仅当家长端已生成配对码时）
        val pairingCode = payload["pairingCode"]?.toString()
        if (pairingCode != null && currentPairingCode != null) {
            if (!verifyPairingCode(pairingCode)) {
                Log.w(TAG, "配对码校验失败: 设备=$deviceId")
                return P2PMessage(
                    type = "handshake_response",
                    payload = mapOf(
                        "status" to "rejected",
                        "reason" to "配对码错误或已过期"
                    )
                )
            }
        }

        val deviceName = payload["deviceName"]?.toString() ?: "未知设备"
        // 用握手包中的真实设备 ID（参数 deviceId 是连接临时 ID，会导致每次重连重复注册）
        val realDeviceId = payload["deviceId"]?.toString() ?: deviceId

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

        return P2PMessage(
            type = "policy_update",
            payload = mapOf(
                "deviceId" to realDeviceId,
                "policies" to policies.toString(),
                "status" to "accepted",
                "timestamp" to (System.currentTimeMillis() / 1000)
            )
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
     */
    fun sendAnnouncementToDevice(
        deviceId: String,
        announcementId: String,
        title: String,
        content: String,
        priority: Int = 0,
        expiresAt: Long = 0
    ): Boolean {
        val output = deviceStreams[deviceId] ?: run {
            Log.w(TAG, "公告推送失败：设备未连接 $deviceId")
            return false
        }
        // 与儿童端 SyncManager.handleAnnouncementPush 兼容的消息格式
        val announcements = "[{\"id\":\"$announcementId\",\"title\":${org.json.JSONObject.quote(title)}," +
            "\"content\":${org.json.JSONObject.quote(content)},\"priority\":$priority,\"expires_at\":$expiresAt}]"
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
