package com.xiaopacai.child.adbshell

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import javax.jmdns.JmDNS

/**
 * [TASK-STRICT-PROVISION-V1] 无线调试服务发现（ADR 0018）
 *
 * 通过 mDNS 发现本机无线调试的两个服务：
 * - `_adb-tls-pairing._tcp`：配对端口（配合 6 位配对码使用，Shizuku 验证过的路径）；
 * - `_adb._tcp`：无线调试 adb 端口。
 * 发现失败不阻塞：UI 提供手动填写兜底（LADB 交互）。
 */
class AdbPairingDiscovery(
    private val scope: CoroutineScope,
    private val onFound: (DiscoveredAdbServices) -> Unit,
    private val onError: (String) -> Unit
) {
    data class DiscoveredAdbServices(
        val host: String,
        val pairingPort: Int,
        val adbPort: Int
    )

    private var jmdns: JmDNS? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                val mdns = JmDNS.create(InetAddress.getLocalHost())
                jmdns = mdns
                val pairing = mdns.list("_adb-tls-pairing._tcp.local.", 3000).firstOrNull()
                val adb = mdns.list("_adb._tcp.local.", 3000).firstOrNull()
                if (pairing == null || adb == null) {
                    onError("未发现无线调试服务：请确认已开启无线调试，并保持“使用配对码配对设备”页面打开")
                } else {
                    val host = (pairing.inet4Addresses.firstOrNull()
                        ?: adb.inet4Addresses.firstOrNull())?.hostAddress
                    if (host.isNullOrBlank()) {
                        onError("发现无线调试服务但无法解析地址，请手动填写")
                    } else {
                        onFound(
                            DiscoveredAdbServices(
                                host = host,
                                pairingPort = pairing.port,
                                adbPort = adb.port
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("AdbDiscovery", "mDNS 发现失败: ${e.message}")
                onError("自动发现失败：${e.message ?: "未知错误"}，请手动填写")
            }
        }
    }

    fun stop() {
        try {
            jmdns?.close()
        } catch (_: Exception) {
        }
        jmdns = null
    }
}
