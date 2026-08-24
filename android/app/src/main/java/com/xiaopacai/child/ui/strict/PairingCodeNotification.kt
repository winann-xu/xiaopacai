package com.xiaopacai.child.ui.strict

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.xiaopacai.child.R
import com.xiaopacai.child.adbshell.AdbOutputParser
import com.xiaopacai.child.adbshell.ProvisionMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [TASK-STRICT-PROVISION-V1] 强管制配对通知（ADR 0018 v1.3.2，Shizuku 同款）
 *
 * ColorOS 真机实测：无线调试配对服务只在「设置页保持前台」时存活，
 * 一切换到 App 即停止（配对端口立刻消失）。因此配对码输入必须走「通知栏内联输入」：
 * 用户在设置页保持配对弹窗打开，下拉通知栏输入 6 位码，App 在后台完成配对与预置。
 */
object PairingCodeNotification {
    const val CHANNEL_ID = "channel_pairing"
    const val NOTIFICATION_ID = 4001
    const val ACTION_SUBMIT_CODE = "com.xiaopacai.child.action.SUBMIT_PAIRING_CODE"
    const val EXTRA_CODE = "pairing_code"

    /** 建立强管制配对通知渠道（幂等） */
    fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "强管制配对",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "强管制模式配对码输入与进度"
                    }
                )
            }
        }
    }

    /** 显示「等待输入配对码」通知（含通知栏内联输入动作，用户无需离开设置页） */
    fun showAwaitingCode(context: Context) {
        ensureChannel(context)
        val submitIntent = Intent(context, PairingCodeReceiver::class.java).apply {
            action = ACTION_SUBMIT_CODE
        }
        val submitPi = PendingIntent.getBroadcast(
            context,
            1,
            submitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val remoteInput = RemoteInput.Builder(EXTRA_CODE)
            .setLabel("6 位配对码")
            .build()
        val submitAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "输入配对码",
            submitPi
        ).addRemoteInput(remoteInput).build()

        val text = "1. 点按系统「使用配对码配对设备」，保持该页面打开\n" +
            "2. 下拉通知栏，在此输入 6 位配对码"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("强管制配对进行中")
            .setContentText("请在下方输入 6 位配对码")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(submitAction)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notify(context, notification)
    }

    /** 更新进度文案（前台服务运行期间） */
    fun update(context: Context, title: String, text: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
        notify(context, notification)
    }

    /** 展示最终结果（可关闭） */
    fun showResult(context: Context, title: String, text: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        notify(context, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun notify(context: Context, notification: android.app.Notification) {
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}

/**
 * 强管制配对流程状态（后台服务与 UI 共享）。
 * UI 侧（StrictProvisionScreen）据此驱动状态机与失败分类展示。
 */
object PairingStatusStore {
    sealed class Status {
        object Idle : Status()
        object AwaitingCode : Status()
        data class Running(val stepText: String) : Status()
        data class Failed(
            val message: String,
            val error: ProvisionMachine.ProvisionError,
            val dpmOutcome: AdbOutputParser.DpmOutcome? = null
        ) : Status()
        object Succeeded : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun post(status: Status) {
        _status.value = status
    }

    fun reset() {
        _status.value = Status.Idle
    }
}
