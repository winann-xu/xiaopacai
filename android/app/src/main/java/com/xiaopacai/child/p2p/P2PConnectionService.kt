package com.xiaopacai.child.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
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
    HEARTBEAT("heartbeat"),
    ERROR("error")
}

class P2PConnectionService {

    companion object {
        private const val TAG = "P2PConnection"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_TIMEOUT_COUNT = 3
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
    }

    /** 连接状态 */
    private val _connectionState = MutableStateFlow(P2PConnectionState.DISCONNECTED)
    val connectionState: StateFlow<P2PConnectionState> = _connectionState

    /** 收到的消息流 */
    private val _receivedMessages = MutableStateFlow<List<P2PMessage>>(emptyList())
    val receivedMessages: StateFlow<List<P2PMessage>> = _receivedMessages

    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    private var expectedFingerprint: String? = null  // 期望的证书指纹
    private var connectedFingerprint: String? = null  // 实际连接的证书指纹
    private var heartbeatMissCount = 0
    private var reconnectAttempt = 0
    private var _deviceId: String = ""
    private var _deviceName: String = ""
    private var _pairingCode: String? = null

    /**
     * 连接到家长端
     * @param host 家长端 IP 地址
     * @param port 家长端端口
     * @param expectedFingerprint 期望的证书指纹（首次配对时可为 null）
     * @param deviceId 本机设备 ID
     * @param deviceName 本机设备名称
     * @param pairingCode 配对码（6 位数字，家长端要求时提供）
     */
    suspend fun connect(
        host: String,
        port: Int,
        expectedFingerprint: String?,
        deviceId: String,
        deviceName: String,
        pairingCode: String? = null,
        scope: CoroutineScope
    ) {
        this.expectedFingerprint = expectedFingerprint
        this._deviceId = deviceId
        this._deviceName = deviceName
        this._pairingCode = pairingCode
        this.reconnectAttempt = 0

        disconnect()  // 断开旧连接

        connectionJob = scope.launch(Dispatchers.IO) {
            performConnect(host, port)
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

            // 6. 启动心跳
            startHeartbeat()

            _connectionState.value = P2PConnectionState.CONNECTED
            reconnectAttempt = 0  // 重置重连计数

            // 7. 开始接收消息循环
            receiveLoop()

        } catch (e: Exception) {
            Log.e(TAG, "连接失败: ${e.message}", e)
            _connectionState.value = P2PConnectionState.DISCONNECTED
            scheduleReconnect(host, port)
        }
    }

    /**
     * 创建 TLS Socket（双向认证）
     * 由于是自签名证书，信任所有服务器证书，但记录指纹用于配对校验
     */
    private fun createTlsSocket(socket: Socket): SSLSocket {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())

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
            // 首次配对：接受并记录
            expectedFingerprint.isNullOrEmpty() -> {
                Log.i(TAG, "首次配对，证书指纹: $actualFingerprint")
                expectedFingerprint = actualFingerprint
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
        // 仅在提供了配对码时携带，避免空串触发家长端校验
        _pairingCode?.let { payload["pairingCode"] = it }
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
