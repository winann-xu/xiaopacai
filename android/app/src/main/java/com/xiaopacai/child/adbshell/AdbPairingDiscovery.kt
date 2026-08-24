package com.xiaopacai.child.adbshell

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * [TASK-STRICT-PROVISION-V1] 无线调试服务发现（ADR 0018，v1.3.2 修正组播锁）
 *
 * 通过 mDNS 发现本机无线调试的两个服务：
 * - `_adb-tls-pairing._tcp`：配对端口（配合 6 位配对码使用，Shizuku 验证过的路径）；
 * - `_adb-tls-connect._tcp`：Android 11+ 无线调试连接端口（v1.3.1 修正：此前误用
 *   老式 `_adb._tcp`，导致 OPPO/Android 11+ 上自动发现永远失败、只能手打端口）；
 * - `_adb._tcp`：Android 10 及以下老式无线调试服务（兼容回退）。
 * v1.3.2：真机实测 JmDNS 在 Wi-Fi 上必须持有 MulticastLock 才能收到 mDNS 响应，
 * 否则发现超时；已补 acquire/release（权限已在清单声明）。
 * 发现失败不阻塞：UI 提供手动填写兜底（LADB 交互）。
 */
class AdbPairingDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFound: (DiscoveredAdbServices) -> Unit,
    private val onError: (String) -> Unit
) {
    /** 从 JmDNS 原始结果提取的轻量结构（便于本地单测，不依赖 JmDNS 实例） */
    data class DiscoveredService(
        val type: String,
        val port: Int,
        val host: String?
    )

    companion object {
        /** 无线调试配对服务（弹窗打开期间广播，端口每次弹窗都会变化） */
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp.local."

        /** Android 11+ 无线调试连接服务 */
        const val CONNECT_SERVICE_TLS = "_adb-tls-connect._tcp.local."

        /** Android 10 及以下老式无线调试服务 */
        const val CONNECT_SERVICE_LEGACY = "_adb._tcp.local."

        /**
         * 解析 mDNS 服务列表，得到配对端口与无线调试连接端口。
         * 配对服务缺失或两种连接服务都缺失时返回 null（由 UI 给出具体提示）。
         * 连接端口优先取新式 TLS 服务，兼容回退老式服务。
         */
        fun resolve(services: List<DiscoveredService>): DiscoveredAdbServices? {
            val pairing = services.firstOrNull { it.type == PAIRING_SERVICE } ?: return null
            val connect = services.firstOrNull { it.type == CONNECT_SERVICE_TLS }
                ?: services.firstOrNull { it.type == CONNECT_SERVICE_LEGACY }
                ?: return null
            val host = pairing.host ?: connect.host ?: return null
            return DiscoveredAdbServices(host = host, pairingPort = pairing.port, adbPort = connect.port)
        }
    }

    data class DiscoveredAdbServices(
        val host: String,
        val pairingPort: Int,
        val adbPort: Int
    )

    /** 发现结果（含缺失分类，便于 UI 给出具体提示） */
    data class DiscoveryOutcome(
        val services: DiscoveredAdbServices?,
        val pairingFound: Boolean,
        val connectFound: Boolean
    )

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                val outcome = discoverNow(context)
                val result = outcome.services
                when {
                    result == null && !outcome.pairingFound ->
                        onError("未发现配对服务：请点按「使用配对码配对设备」并保持弹窗打开")
                    result == null && !outcome.connectFound ->
                        onError("未发现无线调试端口：请确认已开启无线调试")
                    result == null ->
                        onError("发现无线调试服务但无法解析地址，请手动填写")
                    else -> onFound(result)
                }
            } catch (e: Exception) {
                Log.w("AdbDiscovery", "mDNS 发现失败: ${e.message}")
                onError("自动发现失败：${e.message ?: "未知错误"}，请手动填写")
            }
        }
    }

    private fun ServiceInfo.toDiscovered(): DiscoveredService {
        val host = inet4Addresses.firstOrNull()?.hostAddress
        return DiscoveredService(type = type, port = port, host = host)
    }

    fun stop() {
        releaseMulticastLock()
        try {
            jmdns?.close()
        } catch (_: Exception) {
        }
        jmdns = null
    }

    /**
     * 一次性执行 mDNS 发现（可在后台服务中调用）。
     * 必须持有 MulticastLock：Wi-Fi 驱动默认丢弃组播包，JmDNS 将永远收不到响应。
     */
    suspend fun discoverNow(context: Context): DiscoveryOutcome = withContext(Dispatchers.IO) {
        acquireMulticastLock(context)
        try {
            val mdns = JmDNS.create(InetAddress.getLocalHost())
            try {
                val pairingList = mdns.list(PAIRING_SERVICE, 3000)
                val connectList = mdns.list(CONNECT_SERVICE_TLS, 2000) +
                    mdns.list(CONNECT_SERVICE_LEGACY, 2000)
                val services = (pairingList + connectList).map { it.toDiscovered() }
                val pairingFound = services.any { it.type == PAIRING_SERVICE }
                val connectFound = services.any {
                    it.type == CONNECT_SERVICE_TLS || it.type == CONNECT_SERVICE_LEGACY
                }
                DiscoveryOutcome(resolve(services), pairingFound, connectFound)
            } finally {
                try {
                    mdns.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w("AdbDiscovery", "mDNS 发现失败: ${e.message}")
            DiscoveryOutcome(null, pairingFound = false, connectFound = false)
        } finally {
            releaseMulticastLock()
        }
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("xiaopacai_adb_mdns")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.w("AdbDiscovery", "组播锁获取失败（发现可能超时）: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}
