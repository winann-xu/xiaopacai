// [android-tv] 前台服务 — TVGuardianForegroundService
// 小趴菜 TVOS 版 — 守护进程：心跳 + 时长监控 + 白名单检查

package com.xiaopacai.tvos.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaopacai.tvos.R
import com.xiaopacai.tvos.ui.lock.BlockOverlayActivity
import com.xiaopacai.tvos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*

/**
 * 守护前台服务 — 保持应用活跃、监控使用时长、触发锁屏
 * 
 * 功能：
 * 1. 前台通知（防止被杀）
 * 2. 定时刷新使用时长（30s 间隔）
 * 3. 检查是否超时 → 启动 BlockOverlayActivity
 * 4. 白名单应用检查
 */
class TVGuardianForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "xiaopacai_tv_guardian"
        private const val TAG = "GuardianSvcTV"
        private const val CHECK_INTERVAL_MS = 30_000L  // 30 秒检查一次
        private const val REMINDER_INTERVAL_MS = 60_000L  // 60 秒提醒一次
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        TimeoutExecutorTV.init(this)
        startForegroundService()
        startMonitoring()
    }

    override fun onDestroy() {
        scope.cancel()
        // [BUGFIX] stopForeground(int) 仅 API 24+ 可用；Android 5.1(TV) 上回退到布尔重载，否则 NoSuchMethodError
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 【项2】每次被拉起都确保看门狗在跑（幂等，同一闹钟会覆盖）
        GuardianWatchdog.schedule(this)
        return START_STICKY
    }

    /**
     * 【项2】防退出：App 被从「最近任务」划掉时系统会调用这里。
     * 立刻安排 1 秒后的快速重启并重建守护，把可逃逸的空档压到最短。
     * （孩子长按遥控器 HOME / 清后台 是电视上最常见的绕过手段）
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "App 被移出最近任务 → 安排快速重启 + 立即重建守护")
        GuardianWatchdog.scheduleQuickRestart(this)
        GuardianWatchdog.ensureGuardsRunning(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 前台通知 ====================

    /**
     * 启动前台通知（防止系统杀死）
     */
    private fun startForegroundService() {
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "小趴菜守护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "电视守护进程，请勿关闭"
            }
        } else {
            null
        }
        channel?.let { notificationManager.createNotificationChannel(it) }

        val intent = Intent(this, com.xiaopacai.tvos.ui.TvHomeActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小趴菜 · 电视守护")
            .setContentText("守护中")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)  // 系统图标
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // ==================== 监控循环 ====================

    /**
     * 启动时长监控
     */
    private fun startMonitoring() {
        scope.launch {
            // 首先重置每日计数
            TimeoutExecutorTV.resetDaily()

            // 读取已保存的每日限额并立即生效
            TimeoutExecutorTV.setDailyLimit(
                SharedPreferenceHelperTV.getDailyLimit(this@TVGuardianForegroundService).first(),
                this@TVGuardianForegroundService
            )

            while (isActive) {
                try {
                    // 检查使用时长
                    TimeoutExecutorTV.refreshUsage(this@TVGuardianForegroundService)

                    // 如果超时，显示锁屏
                    // [BUGFIX] 家长验证页在前台时不得重新拉起锁屏（CLEAR_TOP 会把验证页顶掉，
                    // 家长永远完不成验证）；此前的无条件每 30s 重拉就是这个死循环。
                    if (TimeoutExecutorTV.isLocked && !TimeoutExecutorTV.verificationInProgress) {
                        showBlockOverlay()
                    }

                    // 如果即将到期，显示提醒通知
                    if (TimeoutExecutorTV.shouldShowReminder(this@TVGuardianForegroundService)) {
                        showReminderNotification()
                    }

                } catch (e: Exception) {
                    android.util.Log.e(TAG, "监控异常", e)
                }

                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * 显示锁屏覆盖
     */
    private fun showBlockOverlay() {
        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    /**
     * 显示即将到期的提醒通知
     */
    private fun showReminderNotification() {
        val remaining = TimeoutExecutorTV.remainingMinutes
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⏰ 电视时间即将用完")
            .setContentText("还剩 $remaining 分钟，请准备关掉电视")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(1002, notification)
    }

    /**
     * 更新通知中的使用时长
     */
    fun updateNotificationUsage(usedMinutes: Int, limitMinutes: Int, remainingMinutes: Int) {
        val statusText = if (remainingMinutes <= 10) {
            "⚠️ 剩余 ${remainingMinutes} 分钟"
        } else {
            "已用 ${usedMinutes}/${limitMinutes} 分钟"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小趴菜 · 电视守护")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
