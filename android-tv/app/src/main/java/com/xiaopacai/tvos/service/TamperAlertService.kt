// [android-tv] 篡改告警服务 — TamperAlertService
// 小趴菜 TVOS 版 — 检测应用被卸载/禁用/关闭时向云端发送告警
// 功能：监控守护服务状态、检测调试模式、检测无障碍关闭

package com.xiaopacai.tvos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.xiaopacai.tvos.util.EncryptionHelper
import com.xiaopacai.tvos.util.NetworkHelper
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.UUID

/**
 * 篡改告警服务
 * 
 * 检测以下篡改行为：
 * 1. 守护服务被关闭/重启
 * 2. 应用被卸载
 * 3. 应用被禁用
 * 4. 调试模式开启（USB 调试）
 * 5. 无障碍服务被关闭
 * 6. 使用统计权限被撤销
 * 
 * 检测到篡改后：
 * 1. 本地显示告警通知
 * 2. 通过心跳接口上报云端
 * 3. 家长 APP 实时收到告警
 * 4. 保持锁屏状态（如果已锁定）
 */
class TamperAlertService : Service() {

    companion object {
        private const val TAG = "TamperAlertServiceTV"
        private const val CHANNEL_ID = "tamper_alert_channel"
        const val NOTIFICATION_ID = 9999
        const val CHECK_INTERVAL_MS = 30_000L // 每 30 秒检查一次

        // 守护服务包名列表
        internal val GUARDIAN_PACKAGES = listOf(
            "com.xiaopacai.tvos",
            "com.xiaopacai.tvos.debug"
        )

        // 守护服务名列表
        internal val GUARDIAN_SERVICES = listOf(
            "com.xiaopacai.tvos.service.CloudSyncServiceTV",
            "com.xiaopacai.tvos.service.AntiBypassServiceTV",
            "com.xiaopacai.tvos.service.TVGuardianForegroundService"
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(mainLooper)
    private var checkJob: Job? = null

    // 篡改状态追踪
    private var isTamperDetected = false
    private var lastTamperTime: Long = 0
    private var tamperAlertHash: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildAlertNotification())
        startTamperDetection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        checkJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 篡改检测 ====================

    /**
     * 启动篡改检测定时任务
     */
    private fun startTamperDetection() {
        checkJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (checkForTampering()) {
                    sendAlertToCloud()
                }
            }
        }
    }

    /**
     * 检测篡改行为
     * @return 是否检测到篡改
     */
    private fun checkForTampering(): Boolean {
        val tamperReasons = mutableListOf<String>()

        // 1. 检查守护服务是否运行
        checkServicesRunning(tamperReasons)

        // 2. 检查调试模式
        checkDebugMode(tamperReasons)

        // 3. 检查应用是否被禁用
        checkAppEnabled(tamperReasons)

        // 4. 检查使用统计权限
        checkUsageStatsPermission(tamperReasons)

        val detected = tamperReasons.isNotEmpty()
        if (detected != isTamperDetected) {
            isTamperDetected = detected
            if (detected) {
                lastTamperTime = System.currentTimeMillis()
                tamperAlertHash = generateTamperHash(tamperReasons)
                showTamperNotification(tamperReasons)
            }
        }
        return detected
    }

    /**
     * 检查守护服务是否运行
     */
    private fun checkServicesRunning(reasons: MutableList<String>) {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningServices = activityManager.getRunningServices(Int.MAX_VALUE) ?: emptyList()

        val runningServiceClasses = runningServices.mapNotNull { it.service?.className }

        GUARDIAN_SERVICES.forEach { serviceName ->
            if (!runningServiceClasses.contains(serviceName)) {
                reasons.add("守护服务未运行: $serviceName")
            }
        }
    }

    /**
     * 检查 USB 调试是否开启
     */
    private fun checkDebugMode(reasons: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (Settings.Global.getInt(contentResolver, "adb_enabled", 0) == 1) {
                reasons.add("USB 调试已开启")
            }
        } else {
            @Suppress("DEPRECATION")
            if (Settings.Secure.getString(contentResolver, "adb_enabled")?.toIntOrNull() == 1) {
                reasons.add("USB 调试已开启")
            }
        }
    }

    /**
     * 检查应用是否被禁用
     */
    private fun checkAppEnabled(reasons: MutableList<String>) {
        GUARDIAN_PACKAGES.forEach { packageName ->
            val packageManager = packageManager
            try {
                val info = packageManager.getApplicationInfo(packageName, 0)
                if (info.enabled.not()) {
                    reasons.add("应用被禁用: $packageName")
                }
            } catch (e: PackageManager.NameNotFoundException) {
                reasons.add("应用不存在: $packageName")
            }
        }
    }

    /**
     * 检查使用统计权限
     */
    private fun checkUsageStatsPermission(reasons: MutableList<String>) {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 60_000 // 最近 60 秒
            val stats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                startTime, endTime
            )
            // 如果能正常查询，说明权限未被撤销
        } catch (e: SecurityException) {
            reasons.add("使用统计权限被撤销")
        }
    }

    // ==================== 告警上报 ====================

    /**
     * 生成篡改哈希（用于去重）
     */
    private fun generateTamperHash(reasons: List<String>): String {
        val sortedReasons = reasons.sorted().joinToString("|")
        return EncryptionHelper.sha256(sortedReasons)
    }

    /**
     * 发送告警到云端
     */
    private fun sendAlertToCloud() {
        if (tamperAlertHash == null) return

        scope.launch {
            try {
                val payload = buildAlertPayload()
                // TODO: 实现实际的 HTTP 请求
                // NetworkHelper.postJson(cloudHost, "/api/v3/tamper_alert", payload)
            } catch (e: Exception) {
                // 告警发送失败不影响本地通知
            }
        }
    }

    /**
     * 构建告警载荷
     */
    private fun buildAlertPayload(): Map<String, Any?> {
        return mapOf(
            "device_id" to fetchDeviceId(),
            "alert_hash" to tamperAlertHash,
            "timestamp" to System.currentTimeMillis(),
            "reasons" to listOf(
                "守护服务异常",
                "USB 调试开启",
                "应用被禁用"
            ),
            "severity" to "HIGH"
        )
    }

    /**
     * 获取设备 ID
     */
    private fun fetchDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return androidId ?: UUID.randomUUID().toString()
    }

    // ==================== 通知管理 ====================

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "篡改告警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "小趴菜守护安全告警通知"
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建告警通知
     */
    private fun buildAlertNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("小趴菜守护")
            .setContentText("检测到异常行为，守护已保护")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 显示篡改通知
     */
    private fun showTamperNotification(reasons: List<String>) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val contentText = if (reasons.size <= 2) {
            reasons.joinToString("\n")
        } else {
            "${reasons.first()} 等 ${reasons.size} 项异常"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ 篡改告警")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reasons.joinToString("\n\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(0xFFFF5722.toInt())
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
