package com.xiaopacai.child.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * [TASK-OPT-12-P2] 守护 AlarmManager 兜底接收器（需求6 双守护：AlarmManager 兜底）
 *
 * 系统闹钟每 30 分钟触发一次：
 * 1. 重新拉起守护前台服务（即使 WorkManager/协程全被系统冻结）
 * 2. 执行防绕过自检
 * 3. 重新调度下一次闹钟（兜底链路自愈）
 *
 * 使用 setInexactRepeating 不保证精确，但功耗友好；
 * 作为 WorkManager 与协程检查之外的第三层保障。
 */
class GuardianAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GuardianAlarm"
        private const val ACTION_SELF_CHECK = "com.xiaopacai.child.action.SELF_CHECK"
        private const val ALARM_INTERVAL_MS = 30 * 60 * 1000L  // 30 分钟
        private const val ALARM_REQUEST_CODE = 3001

        /**
         * 调度守护兜底闹钟（幂等：重复调用只保留一个周期任务）
         */
        fun schedule(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pendingIntent = buildPendingIntent(context)
                val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    ALARM_INTERVAL_MS,
                    pendingIntent
                )
                Log.d(TAG, "守护兜底闹钟已调度（每 30 分钟）")
            } catch (e: Exception) {
                Log.e(TAG, "调度守护兜底闹钟失败: ${e.message}")
            }
        }

        /**
         * 取消守护兜底闹钟
         */
        fun cancel(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(buildPendingIntent(context))
            } catch (e: Exception) {
                Log.e(TAG, "取消守护兜底闹钟失败: ${e.message}")
            }
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, GuardianAlarmReceiver::class.java).apply {
                action = ACTION_SELF_CHECK
            }
            return PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SELF_CHECK) return

        Log.i(TAG, "守护兜底闹钟触发，重新拉起守护服务")

        // 1. 拉起守护前台服务（系统可能已杀死旧实例）
        try {
            GuardianForegroundService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "拉起守护服务失败: ${e.message}")
        }

        // 2. 防绕过自检
        try {
            AntiBypassService.checkAllBypassVectors(context)
        } catch (e: Exception) {
            Log.e(TAG, "自检失败: ${e.message}")
        }

        // 3. 重新调度下一次（防止系统清除了周期闹钟）
        schedule(context)
    }
}
