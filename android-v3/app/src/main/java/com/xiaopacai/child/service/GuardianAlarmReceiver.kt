package com.xiaopacai.child.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.MainActivity
import com.xiaopacai.child.R
import com.xiaopacai.child.XiaopacaiApp

/**
 * [TASK-OPT-12-P2] 守护 AlarmManager 兜底接收器（需求6 双守护：AlarmManager 兜底）
 * [TASK-MILESTONE-V3] 需求 5：新增「上滑结束快速恢复」通道
 *
 * 1. ACTION_SELF_CHECK：系统闹钟每 30 分钟触发一次，重新拉起守护前台服务
 *    （即使 WorkManager/协程全被系统冻结），执行防绕过自检并重新调度下一次；
 * 2. ACTION_SWIPE_RECOVERY：用户在最近任务上滑结束小趴菜后，进程可能被 OEM
 *    立即杀死（OPPO/小米等常见），此时进程内 START_STICKY 未必能生效——
 *    由 onTaskRemoved 抢先注册的 5 秒一次性精确闹钟（系统侧，不随进程消亡）
 *    拉起守护；若管控曾生效（enforcement_active 标记），恢复后立即通知。
 *
 * 使用 setInexactRepeating 不保证精确，但功耗友好；
 * 作为 WorkManager 与协程检查之外的第三层保障。
 *
 * 能力边界：用户「强制停止」会同时取消全部闹钟/WorkManager，任何应用都无法
 * 自我恢复，只能等待用户下次打开应用（打开即恢复并通知）。详见 OEM_KEEPALIVE.md。
 */
class GuardianAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GuardianAlarm"
        private const val ACTION_SELF_CHECK = "com.xiaopacai.child.action.SELF_CHECK"
        // [TASK-MILESTONE-V3] 需求 5：上滑结束快速恢复
        private const val ACTION_SWIPE_RECOVERY = "com.xiaopacai.child.action.SWIPE_RECOVERY"
        private const val ALARM_INTERVAL_MS = 30 * 60 * 1000L  // 30 分钟
        private const val ALARM_REQUEST_CODE = 3001
        private const val SWIPE_RECOVERY_REQUEST_CODE = 3002
        // [TASK-HARDENING-V1.1.1] 1-A：internal 供单测断言恢复链路延迟契约
        internal const val SWIPE_RECOVERY_DELAY_MS = 5 * 1000L   // 上滑后 5 秒拉起
        private const val RECOVERY_NOTIFY_ID = 3003
        private const val PREFS_GUARDIAN = "guardian_prefs"
        private const val KEY_ENFORCEMENT_ACTIVE = "enforcement_active"
        private const val KEY_SWIPE_PENDING = "swipe_recover_pending"

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

        /**
         * [TASK-MILESTONE-V3] 需求 5：上滑结束快速恢复。
         * onTaskRemoved 中调用：注册 5 秒后的一次性精确闹钟（进程被杀也不丢），
         * 并打待恢复标记；恢复时若管控曾生效则通知家长。
         */
        fun scheduleSwipeRecovery(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, GuardianAlarmReceiver::class.java).apply {
                    action = ACTION_SWIPE_RECOVERY
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, SWIPE_RECOVERY_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = System.currentTimeMillis() + SWIPE_RECOVERY_DELAY_MS
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    } else {
                        @Suppress("DEPRECATION")
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    }
                } catch (e: SecurityException) {
                    // 无精确闹钟权限（SCHEDULE_EXACT_ALARM）时退化为普通闹钟
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SWIPE_PENDING, true).apply()
                // [TASK-HARDENING-V1.1.1] Bug1-D：上滑即失守（进程可能被 OEM 立即杀死）
                GuardDownMonitor.onGuardLost(context, "swipe_killed")
                Log.i(TAG, "上滑恢复闹钟已注册（${SWIPE_RECOVERY_DELAY_MS / 1000}s 后拉起）")
            } catch (e: Exception) {
                Log.e(TAG, "注册上滑恢复闹钟失败: ${e.message}")
            }
        }

        /** 管控是否曾生效（TimeoutExecutor 打标；用于恢复通知措辞） */
        fun isEnforcementActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENFORCEMENT_ACTIVE, false)

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
        when (intent.action) {
            ACTION_SELF_CHECK -> handleSelfCheck(context)
            ACTION_SWIPE_RECOVERY -> handleSwipeRecovery(context)
        }
    }

    private fun handleSelfCheck(context: Context) {
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

    /**
     * [TASK-HARDENING-V1.1.1] 1-A：恢复通知文案纯函数（恢复链路可单测）
     */
    fun recoveryNotificationText(wasEnforcing: Boolean): Pair<String, String> =
        if (wasEnforcing)
            "守护已自动恢复，管控重新生效" to
                "检测到小趴菜进程曾被上滑结束，守护已自动恢复并重新执行管控。"
        else
            "守护已自动恢复" to
                "检测到小趴菜进程曾被上滑结束，守护已自动恢复。"

    /**
     * [TASK-MILESTONE-V3] 需求 5：上滑结束后的快速恢复
     */
    private fun handleSwipeRecovery(context: Context) {
        Log.i(TAG, "上滑恢复闹钟触发，拉起守护服务")

        // 1. 拉起守护前台服务（进程已死则重新创建，START_STICKY + 采集立即重放管控）
        try {
            GuardianForegroundService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "拉起守护服务失败: ${e.message}")
        }

        // [TASK-HARDENING-V1.1.1] Bug1-D：上滑失守结算（上报家长端）
        GuardDownMonitor.onGuardRestored(context, "swipe_recovery")

        // 2. 若管控曾生效：通知「已自动恢复」（内容点击回到小趴菜）
        val wasEnforcing = isEnforcementActive(context)
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val (title, text) = recoveryNotificationText(wasEnforcing)
            val notification = NotificationCompat.Builder(context, XiaopacaiApp.CHANNEL_SECURITY)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "$text\n\n提示：可在系统设置中允许「自启动」与关闭电池优化，降低再次被结束的概率。"
                ))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            notificationManager.notify(RECOVERY_NOTIFY_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "恢复通知失败: ${e.message}")
        }

        // 3. 清除待恢复标记
        context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
            .edit().remove(KEY_SWIPE_PENDING).apply()
    }
}
