package com.xiaopacai.child.adbshell

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * v1.3.2 实测结论：设备端 mDNS 无法回环发现 `_adb-tls-connect` 连接服务
 * （JmDNS 与 adb mdns 均查不到），连接端口改由用户从配对弹窗「IP 地址和端口」抄录，
 * 本类只负责配对服务（主机 + 配对端口）的发现。
 */
class AdbPairingDiscovery {
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

    /** 配对所需的最小信息（主机 + 配对端口） */
    data class PairingInfo(
        val host: String,
        val pairingPort: Int
    )

    /**
     * 发现结果。连接端口由用户从配对弹窗抄录（设备端 mDNS 无法回环发现
     * `_adb-tls-connect`，真机验证 JmDNS 与 adb mdns 均查不到），
     * 因此这里只负责配对服务的主机与配对端口。
     */
    data class DiscoveryOutcome(
        val pairing: PairingInfo?,
        val pairingFound: Boolean
    )

    private var multicastLock: WifiManager.MulticastLock? = null

    private fun ServiceInfo.toDiscovered(): DiscoveredService {
        val host = inet4Addresses.firstOrNull()?.hostAddress
        return DiscoveredService(type = type, port = port, host = host)
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
                // 配对服务最先查、给足 4s（配对弹窗生命周期短，最要紧；连接服务不再查询，
                // 其端口由用户从弹窗「IP 地址和端口」抄录，设备端 mDNS 查不到连接服务）。
                val pairingList = mdns.list(PAIRING_SERVICE, 4000)
                val pairingService = pairingList.firstOrNull()?.toDiscovered()
                val pairingFound = pairingService != null
                val pairing = pairingService?.let {
                    val host = it.host
                    if (host.isNullOrBlank()) null else PairingInfo(host, it.port)
                }
                DiscoveryOutcome(pairing = pairing, pairingFound = pairingFound)
            } finally {
                try {
                    mdns.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w("AdbDiscovery", "mDNS 发现失败: ${e.message}")
            DiscoveryOutcome(null, pairingFound = false)
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
