package com.xiaopacai.child.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * [TASK-D1-04] P2P 发现服务
 *
 * 负责在局域网中发现小趴菜家长端设备。
 * 提供三层发现机制（按优先级递减）：
 * 1. mDNS/DNS-SD 服务发现（优先，零配置）
 * 2. UDP 广播监听（兜底，mDNS 不可用时）
 * 3. 手动 IP 地址直连（最后手段）
 *
 * 发现的家长端信息通过 StateFlow 暴露给 UI 层。
 */

/** 发现的家长端设备信息 */
data class DiscoveredParent(
    val deviceId: String,       // 家长端设备 ID
    val serviceName: String,    // 服务名称
    val host: String,           // IP 地址
    val port: Int,              // 服务端口
    val fingerprint: String,    // 证书指纹（[SEC-P1] 完整 64 位 SHA-256，用于首连固定比对）
    val discoveryMethod: String // 发现方式: "mdns" / "udp" / "manual"
)

class P2PDiscoveryService {

    companion object {
        private const val TAG = "P2PDiscovery"
        private const val MDNS_SERVICE_TYPE = "_xiaopacai._tcp.local."
        private const val UDP_BROADCAST_PORT = 9528
        private const val BROADCAST_MAGIC = "XPACAI"
        private const val DISCOVERY_TIMEOUT_MS = 10_000L  // 10 秒发现超时
    }

    /** 发现的家长端设备列表（StateFlow 供 UI 实时更新） */
    private val _discoveredParents = MutableStateFlow<List<DiscoveredParent>>(emptyList())
    val discoveredParents: StateFlow<List<DiscoveredParent>> = _discoveredParents

    private var jmdns: JmDNS? = null
    private var udpSocket: DatagramSocket? = null
    private var discoveryJob: Job? = null

    /**
     * 开始发现家长端设备
     * 同时启动 mDNS 查询和 UDP 广播监听
     */
    suspend fun startDiscovery(scope: CoroutineScope) {
        _discoveredParents.value = emptyList()

        discoveryJob = scope.launch {
            // 并行执行 mDNS 发现和 UDP 监听
            val mdnsDeferred = async { discoverViaMdns() }
            val udpDeferred = async { discoverViaUdp() }

            // 等待两种方式的结果（任一返回即收集）
            val mdnsResults = mdnsDeferred.await()
            val udpResults = udpDeferred.await()

            // 合并结果
            _discoveredParents.value = (mdnsResults + udpResults)
                .distinctBy { it.deviceId }
        }
    }

    /**
     * 停止发现
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        try { jmdns?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        jmdns = null
        udpSocket = null
    }

    // === 方式一：mDNS/DNS-SD 被发现 ===

    /**
     * 通过 mDNS/DNS-SD 查询局域网中的家长端
     * 查询服务类型 _xiaopacai._tcp.local.
     */
    private suspend fun discoverViaMdns(): List<DiscoveredParent> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiscoveredParent>()
        try {
            // 创建 JmDNS 实例（绑定到局域网接口）
            jmdns = JmDNS.create(InetAddress.getLocalHost())

            // 查询服务
            val services = jmdns!!.list(MDNS_SERVICE_TYPE, DISCOVERY_TIMEOUT_MS)
            services?.forEach { service ->
                try {
                    // 获取服务详细信息（IP + 端口 + TXT 记录）
                    val info = jmdns!!.getServiceInfo(
                        MDNS_SERVICE_TYPE,
                        service.name,
                        DISCOVERY_TIMEOUT_MS
                    )
                    info?.let { parseMdnsService(it) }?.let { results.add(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "解析 mDNS 服务异常: ${service.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS 发现异常（可能网络不支持）: ${e.message}")
        }
        results
    }

    /** 解析 mDNS 服务信息为 DiscoveredParent */
    private fun parseMdnsService(info: ServiceInfo): DiscoveredParent? {
        val deviceId = info.getPropertyString("deviceId") ?: return null
        val port = info.port
        val fingerprint = info.getPropertyString("fingerprint") ?: ""

        // 取第一个 IPv4 地址
        val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return null

        return DiscoveredParent(
            deviceId = deviceId,
            serviceName = info.name,
            host = host,
            port = port,
            fingerprint = fingerprint,
            discoveryMethod = "mdns"
        )
    }

    // === 方式二：UDP 广播监听 ===

    /**
     * 监听 UDP 广播包（端口 9528）
     * 当 mDNS 不可用时，家长端会通过 UDP 广播宣告自己
     */
    private suspend fun discoverViaUdp(): List<DiscoveredParent> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiscoveredParent>()
        try {
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(UDP_BROADCAST_PORT))
                soTimeout = DISCOVERY_TIMEOUT_MS.toInt()
            }

            val buffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()

            // 持续监听直到超时
            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT_MS) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)

                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (data.startsWith(BROADCAST_MAGIC)) {
                        // 解析 JSON payload（跳过 "XPACAI" 头部）
                        val jsonStr = data.substring(BROADCAST_MAGIC.length)
                        val parent = parseUdpBroadcast(jsonStr, packet.address)
                        parent?.let { results.add(it) }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    break  // 超时，结束监听
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP 广播监听异常: ${e.message}")
        }
        results
    }

    /** 解析 UDP 广播消息 */
    private fun parseUdpBroadcast(jsonStr: String, senderAddress: InetAddress): DiscoveredParent? {
        return try {
            // 简单 JSON 解析（避免引入第三方库，使用 Android 内置 org.json）
            val json = org.json.JSONObject(jsonStr)
            DiscoveredParent(
                deviceId = json.optString("deviceId", ""),
                serviceName = "UDP-${json.optString("deviceId", "unknown")}",
                host = senderAddress.hostAddress ?: return null,
                port = json.optInt("port", 9527),
                fingerprint = json.optString("fingerprint", ""),
                discoveryMethod = "udp"
            )
        } catch (e: Exception) {
            Log.w(TAG, "UDP 消息解析失败: $jsonStr", e)
            null
        }
    }

    // === 方式三：手动 IP 输入 ===

    /**
     * 手动添加家长端设备（IP 直连）
     * 用于 mDNS 和 UDP 均不可用时的兜底方案
     */
    fun addManualParent(
        host: String,
        port: Int = 9527,
        deviceId: String = "manual-${System.currentTimeMillis()}"
    ): DiscoveredParent {
        val manual = DiscoveredParent(
            deviceId = deviceId,
            serviceName = "手动连接-$host",
            host = host,
            port = port,
            fingerprint = "",  // 首次连接后从 TLS 握手获取
            discoveryMethod = "manual"
        )
        _discoveredParents.value = _discoveredParents.value + manual
        return manual
    }
}
