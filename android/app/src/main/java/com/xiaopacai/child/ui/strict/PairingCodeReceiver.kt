package com.xiaopacai.child.ui.strict

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * [TASK-STRICT-PROVISION-V1] 通知栏配对码接收器（ADR 0018 v1.3.2）
 *
 * 接收通知栏内联输入提交的 6 位配对码，转交后台预置服务执行
 * （发现 → 配对 → 连接 → dpm），用户全程无需离开系统设置页。
 */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingCodeNotification.EXTRA_CODE)
            ?.toString()
            ?.filter { it.isDigit() }
        if (code != null && code.length == 6) {
            PairingProvisionService.start(context, code)
        } else {
            PairingCodeNotification.update(context, "配对码无效", "请输入 6 位数字配对码后重试")
        }
    }
}
