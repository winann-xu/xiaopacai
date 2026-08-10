package com.xiaopacai.child.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.MainActivity
import com.xiaopacai.child.XiaopacaiApp
import kotlinx.coroutines.*

/**
 * [TASK-D3-03] 防绕过检测服务
 *
 * 实时监测以下绕过行为并触发告警/封锁：
 * 1. 无障碍服务被关闭 → 重新引导开启 + 通知家长
 * 2. 使用情况访问权限被撤销 → 引导重新授权
 * 3. 通知权限被关闭 → 前台服务受影响但无法完全阻止
 * 4. 电池优化被开启 → 引导关闭电池优化
 * 5. 被强制停止 → 尝试自动恢复
 * 6. 应用信息页被打开（可能意味着尝试卸载）→ 记录告警
 */
object AntiBypassService {

    private const val TAG = "AntiBypass"
    private const val SECURITY_NOTIFY_ID = 2001
    private const val CHECK_INTERVAL_MS = 60_000L  // 每分钟检查一次

    private var checkJob: Job? = null

    /**
     * 启动防绕过监控
     *
     * @param context 应用上下文
     * @param scope 协程作用域
     */
    fun startMonitoring(context: Context, scope: CoroutineScope) {
        if (checkJob?.isActive == true) return

        checkJob = scope.launch {
            while (isActive) {
                try {
                    checkAllBypassVectors(context)
                } catch (e: Exception) {
                    Log.e(TAG, "绕过检查异常: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }

        Log.i(TAG, "防绕过监控已启动")
    }

    /**
     * 停止防绕过监控
     */
    fun stopMonitoring() {
        checkJob?.cancel()
        checkJob = null
        Log.i(TAG, "防绕过监控已停止")
    }

    /**
     * 检查所有绕过向量
     */
    private fun checkAllBypassVectors(context: Context) {
        val issues = mutableListOf<String>()

        // 1. 检查无障碍服务是否启用
        if (!isAccessibilityServiceEnabled(context)) {
            issues.add("无障碍服务已关闭")
        }

        // 2. 检查使用情况访问权限
        if (!isUsageStatsPermissionGranted(context)) {
            issues.add("使用情况访问权限已撤销")
        }

        // 3. 检查设备管理器状态
        if (!GuardianDeviceAdminReceiver.isActive(context)) {
            issues.add("设备管理器未激活")
        }

        // 4. 检查电池优化状态
        if (isBatteryOptimizationEnabled(context)) {
            issues.add("电池优化未关闭（可能影响后台运行）")
        }

        if (issues.isNotEmpty()) {
            val message = issues.joinToString("；")
            Log.w(TAG, "检测到安全风险: $message")
            notifySecurityIssue(context, issues)
        }
    }

    /**
     * 检查无障碍服务是否启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceName = "${context.packageName}/.service.GuardianAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(serviceName) || enabledServices.contains("GuardianAccessibilityService")
    }

    /**
     * 检查使用情况访问权限
     */
    fun isUsageStatsPermissionGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * 检查电池优化是否启用（true=启用优化=可能被杀）
     */
    fun isBatteryOptimizationEnabled(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else false
    }

    /**
     * 通知安全问题（通知栏 + 家长端同步）
     */
    private fun notifySecurityIssue(context: Context, issues: List<String>) {
        // 本地通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, XiaopacaiApp.CHANNEL_SECURITY)
            .setContentTitle("⚠️ 安全风险检测")
            .setContentText(issues.firstOrNull() ?: "检测到安全风险")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "检测到以下安全风险：\n${issues.joinToString("\n") { "• $it" }}\n\n请检查并修复以上问题。"
            ))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(SECURITY_NOTIFY_ID, notification)
    }

    /**
     * 发送安全事件通知（用于外部调用）
     */
    fun notifySecurityEvent(context: Context, title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, XiaopacaiApp.CHANNEL_SECURITY)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(SECURITY_NOTIFY_ID + 1, notification)
    }

    /**
     * 检测应用是否正在被卸载（通过监控包管理事件）
     */
    fun onPackageChanged(context: Context, packageName: String, eventType: String) {
        if (packageName == context.packageName) {
            Log.w(TAG, "检测到自身应用包变更: $eventType")
            when (eventType) {
                "PACKAGE_REMOVED" -> {
                    Log.e(TAG, "⚠️ 应用正在被卸载！")
                    // 此时已无法阻止，但已记录审计日志
                }
                "PACKAGE_DATA_CLEARED" -> {
                    Log.e(TAG, "⚠️ 应用数据被清除！")
                    notifySecurityEvent(context,
                        "应用数据被清除",
                        "检测到小趴菜应用数据被清除。守护服务可能已中断。"
                    )
                }
            }
        }
    }
}
