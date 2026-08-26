package com.xiaopacai.child.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.R
import kotlinx.coroutines.*

class GuardianForegroundService : Service() {

    companion object {
        private const val TAG = "GuardianService"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS_GUARDIAN = "guardian_prefs"
        private const val KEY_HEARTBEAT_MS = "guardian_heartbeat_ms"
        private const val KEY_BOOT_EPOCH = "guardian_boot_epoch"
        private const val KEY_SWIPE_PENDING = "swipe_recover_pending"
        const val KILL_GAP_MS = 5 * 60 * 1000L
        private const val HEARTBEAT_INTERVAL_MS = 60 * 1000L

        fun isKillRecovery(lastHeartbeatMs: Long, nowMs: Long): Boolean =
            lastHeartbeatMs > 0 && nowMs - lastHeartbeatMs > KILL_GAP_MS

        fun isEnforcementActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
                .getBoolean("enforcement_active", false)

        @Volatile
        private var collector: UsageStatsCollector? = null

        fun getCollector(): UsageStatsCollector? = collector

        fun start(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardianForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val eventReceiver = GuardianEventReceiver()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "守护前台服务创建")
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this, eventReceiver, GuardianEventReceiver.dynamicFilter(),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "注册事件自检接收器失败: ${e.message}")
        }
        AntiBypassService.startMonitoring(this, serviceScope)
        AntiBypassService.scheduleSelfCheck(this)
        DiagnosticsCollector.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "守护前台服务启动")
        updateNotification("正在守护中...", 0)
        startUsageCollector()
        startCloudSync()
        startNotificationUpdater()
        startHeartbeat()
        detectKillRecovery()
        if (isEnforcementActive(this)) {
            Log.i(TAG, "检测到管控曾生效，立即重放采集以快速恢复拦截")
            serviceScope.launch(Dispatchers.IO) {
                runCatching { collector?.collectAndPersist() }
                    .onFailure { Log.e(TAG, "重放采集失败: ${it.message}") }
            }
        }
        return START_STICKY
    }

    private fun startHeartbeat() {
        serviceScope.launch {
            while (isActive) {
                val prefs = getSharedPreferences(PREFS_GUARDIAN, MODE_PRIVATE)
                prefs.edit()
                    .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
                    .putLong(KEY_BOOT_EPOCH, currentBootEpoch())
                    .apply()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun currentBootEpoch(): Long =
        System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    private fun detectKillRecovery() {
        try {
            val prefs = getSharedPreferences(PREFS_GUARDIAN, MODE_PRIVATE)
            val pendingSwipe = prefs.getBoolean(KEY_SWIPE_PENDING, false)
            if (pendingSwipe) return
            val lastHeartbeat = prefs.getLong(KEY_HEARTBEAT_MS, 0L)
            val lastBootEpoch = prefs.getLong(KEY_BOOT_EPOCH, -1L)
            if (lastBootEpoch >= 0 && lastBootEpoch != currentBootEpoch()) {
                Log.i(TAG, "设备重启后恢复（开机自启），跳过被杀检测")
                return
            }
            if (!isKillRecovery(lastHeartbeat, System.currentTimeMillis())) return
            Log.w(TAG, "检测到守护进程曾被结束（心跳间隔超阈值），已恢复")
            val wasEnforcing = isEnforcementActive(this)
            GuardDownMonitor.onGuardLost(this, "process_killed", startTs = lastHeartbeat)
            GuardDownMonitor.onGuardRestored(this, "auto_recovered")
            AntiBypassService.notifySecurityEvent(
                this,
                "守护已自动恢复" + if (wasEnforcing) "，管控重新生效" else "",
                "检测到小趴菜后台进程曾被系统结束" +
                    if (wasEnforcing) "（期间管控可能短暂失效）" else "" +
                    "，守护已自动恢复。建议在系统设置中允许自启动并关闭电池优化。"
            )
        } catch (e: Exception) {
            Log.e(TAG, "杀进程检测失败: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "检测到上滑结束小趴菜（onTaskRemoved），注册恢复闹钟")
        GuardianAlarmReceiver.scheduleSwipeRecovery(this)
        super.onTaskRemoved(rootIntent)
    }

    private fun startUsageCollector() {
        if (collector == null) {
            collector = UsageStatsCollector(this, serviceScope)
        }
        collector?.start()
    }

    private fun startCloudSync() {
        if (CloudSyncService.isRegistered(this)) {
            CloudSyncService.startPolling(this, serviceScope)
        }
    }

    private fun startNotificationUpdater() {
        serviceScope.launch {
            while (isActive) {
                delay(2 * 60 * 1000L)
                val totalMinutes = collector?.todayAdjustedMinutes ?: 0
                val isTimeout = collector?.isTimeoutActive ?: false
                val contentText = when {
                    isTimeout -> "今日使用时长已超限"
                    totalMinutes > 0 -> "今日已使用 $totalMinutes 分钟"
                    else -> "正在守护孩子的使用时长"
                }
                updateNotification(contentText, totalMinutes.toInt())
            }
        }
    }

    private fun updateNotification(contentText: String, progressMinutes: Int) {
        val channelId = XiaopacaiApp.CHANNEL_GUARDIAN
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("小趴菜守护运行中")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        val limitMinutes = collector?.todayLimitMinutes?.toInt() ?: 0
        if (limitMinutes > 0) {
            builder.setProgress(limitMinutes, progressMinutes.coerceAtMost(limitMinutes), false)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        collector?.stop()
        CloudSyncService.stopPolling()
        AntiBypassService.stopMonitoring()
        try { unregisterReceiver(eventReceiver) } catch (_: Exception) {}
        serviceScope.cancel()
        Log.i(TAG, "守护前台服务销毁")
    }

    private fun getPassphrase(): ByteArray {
        return try {
            com.xiaopacai.child.util.KeyStoreManager.getOrCreateDbMasterKey(this)
        } catch (e: Exception) {
            Log.w(TAG, "KeyStore 不可用，使用备用密码方案")
            val prefs = getSharedPreferences("guardian_secure_prefs", Context.MODE_PRIVATE)
            val seed = prefs.getString("db_key_seed", null)
                ?: java.util.UUID.randomUUID().toString().also {
                    prefs.edit().putString("db_key_seed", it).apply()
                }
            seed.toByteArray(Charsets.UTF_8)
        }
    }
}
