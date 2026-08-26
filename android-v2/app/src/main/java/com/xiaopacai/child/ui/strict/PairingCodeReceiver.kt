package com.xiaopacai.child.ui.strict

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * [TASK-STRICT-PROVISION-V1] 通知栏配对码接收器（ADR 0018 v1.3.2）
 *
 * 接收通知栏内联输入（格式：配对端口:6位配对码，如 39019:123456），转交后台预置服务执行
 * （回环配对 → 自动回连 → dpm），用户全程无需离开系统设置页。
 *
 * 配对端口与配对码都在系统配对弹窗上显示（「IP 地址和端口」+「WLAN 配对码」），
 * 由用户抄录最可靠；配对走 127.0.0.1 回环，连接由 adb server 配对后自动回连。
 */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingCodeNotification.EXTRA_CODE)
            ?.toString()
            ?.trim()
        val portCode = raw?.split(":")?.map { it.trim() }
        val port = portCode?.getOrNull(0)?.toIntOrNull()
        val code = portCode?.getOrNull(1)?.filter { it.isDigit() }
        if (port != null && port in 1..65535 && code != null && code.length == 6) {
            PairingProvisionService.start(context, code, port)
        } else {
            PairingCodeNotification.update(
                context,
                "输入格式不对",
                "请按「配对端口:配对码」输入，例如 39019:123456"
            )
        }
    }
}
