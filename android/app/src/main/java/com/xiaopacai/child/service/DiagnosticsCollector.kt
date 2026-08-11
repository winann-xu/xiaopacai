package com.xiaopacai.child.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.p2p.P2PMessage
import com.xiaopacai.child.p2p.P2PConnectionState
import com.xiaopacai.child.util.DbPassphraseProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [TASK-OPT-12-P2] 故障诊断信息采集器（需求5）
 *
 * 采集项：
 * - 应用版本 / Android 版本（API）/ 设备型号 / 厂商
 * - 权限状态（无障碍/用量/设备管理器/通知/电池优化）
 * - 守护服务与无障碍服务运行状态
 * - 最近崩溃堆栈（最近 5 条）
 * - P2P 连接历史（成功/失败/重连次数）
 * - 数据库大小 / 网络类型（WiFi/蜂窝/无）
 *
 * 上报时机：
 * - 每天一次（WorkManager 周期任务，家长可在设置关闭 —— 默认开启）
 * - 发生异常时立即补报（崩溃处理器钩子）
 * - 设置页"立即上报"手动触发
 *
 * 上报通道：复用 P2P 链路 diagnostics_report 消息；
 * 未连接时缓存本地，重连后补传（flushPending）。
 */
object DiagnosticsCollector {

    private const val TAG = "DiagnosticsCollector"

    private const val PREFS_NAME = "diagnostics_prefs"
    private const val KEY_PENDING = "pending_reports"    // 未上报诊断 JSON 数组
    private const val KEY_CRASHES = "recent_crashes"     // 最近崩溃 JSON 数组（最多 5 条）
    private const val KEY_P2P_SUCCESS = "p2p_success"    // P2P 发送成功次数
    private const val KEY_P2P_FAIL = "p2p_fail"          // P2P 发送失败次数
    private const val KEY_P2P_RECONNECT = "p2p_reconnect" // P2P 重连次数
    private const val KEY_ENABLED = "diagnostics_enabled" // 诊断上报总开关（默认开启）

    private const val MAX_CRASHES = 5
    private const val MAX_PENDING = 20

    // ==================== 生命周期 ====================

    /**
     * 初始化：安装崩溃处理器 + 调度每日上报
     */
    fun start(context: Context) {
        installCrashHandler(context)
        scheduleDaily(context)
    }

    /**
     * 安装全局崩溃处理器：记录最近 5 条崩溃堆栈，并立即补报
     */
    private fun installCrashHandler(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                recordCrash(context, stackTrace)
                Log.e(TAG, "捕获崩溃: ${throwable.message}")
            } catch (_: Exception) {
            }
            // 转交原处理器（保持系统默认崩溃行为）
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 调度每日自动上报（唯一周期任务）
     */
    fun scheduleDaily(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<DiagnosticsDailyWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "diagnostics_daily",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "每日诊断上报已调度")
        } catch (e: Exception) {
            Log.e(TAG, "调度每日诊断上报失败: ${e.message}")
        }
    }

    /**
     * 家长是否关闭了诊断上报（设置页开关，默认开启）
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    /**
     * 设置诊断上报开关
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ==================== 采集 ====================

    /**
     * 采集完整诊断信息
     *
     * @return JSONObject 诊断数据
     */
    fun collect(context: Context): JSONObject {
        val report = JSONObject()

        // 1. 基础信息
        report.put("appVersion", getAppVersion(context))
        report.put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        report.put("deviceModel", Build.MODEL)
        report.put("manufacturer", Build.MANUFACTURER)

        // 2. 权限状态
        report.put("permissionStatus", collectPermissionStatus(context))

        // 3. 服务运行状态
        report.put("serviceStatus", collectServiceStatus(context))

        // 4. 最近崩溃（最近 5 条）
        report.put("recentCrashes", collectRecentCrashes(context))

        // 5. P2P 连接历史
        report.put("p2pHistory", collectP2pHistory(context))

        // 6. 数据库大小（字节）
        report.put("dbSizeBytes", collectDbSize(context))

        // 7. 网络类型
        report.put("networkType", getNetworkType(context))

        return report
    }

    /**
     * 采集权限状态（JSON）
     */
    private fun collectPermissionStatus(context: Context): JSONObject {
        val perms = JSONObject()
        perms.put("accessibility", AntiBypassService.isAccessibilityServiceEnabled(context))
        perms.put("usageStats", AntiBypassService.isUsageStatsPermissionGranted(context))
        perms.put("deviceAdmin", GuardianDeviceAdminReceiver.isActive(context))
        perms.put("notification", hasNotificationPermission(context))
        // true=已关闭电池优化（保活正常）
        perms.put("batteryOptimizationDisabled", !AntiBypassService.isBatteryOptimizationEnabled(context))
        return perms
    }

    /**
     * 采集服务运行状态（JSON）
     */
    private fun collectServiceStatus(context: Context): JSONObject {
        val status = JSONObject()
        // 前台守护服务是否在运行（采集器实例存在即视为运行中）
        status.put("guardianServiceRunning", GuardianForegroundService.getCollector() != null)
        status.put("accessibilityServiceRunning", AntiBypassService.isAccessibilityServiceEnabled(context))
        val p2p = try {
            GuardianForegroundService.getP2PConnection()
        } catch (e: Exception) {
            null
        }
        status.put("p2pConnected", p2p?.connectionState?.value == P2PConnectionState.CONNECTED)
        status.put("p2pState", p2p?.connectionState?.value?.name ?: "UNKNOWN")
        return status
    }

    /**
     * 采集最近崩溃记录（JSON 数组，最多 5 条）
     */
    private fun collectRecentCrashes(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            val json = prefs.getString(KEY_CRASHES, "[]")
            JSONArray(json)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /**
     * 采集 P2P 连接历史（JSON）
     */
    private fun collectP2pHistory(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val history = JSONObject()
        history.put("successCount", prefs.getInt(KEY_P2P_SUCCESS, 0))
        history.put("failCount", prefs.getInt(KEY_P2P_FAIL, 0))
        history.put("reconnectCount", prefs.getInt(KEY_P2P_RECONNECT, 0))
        // 最后配对连接时间（来自 pairing_info 表）
        history.put("lastConnectedAt", queryLastConnectedAt(context))
        return history
    }

    /**
     * 查询最后配对连接时间（Unix 秒，来自 pairing_info 表）
     */
    private fun queryLastConnectedAt(context: Context): Long {
        return try {
            val passphrase = DbPassphraseProvider.getPassphrase(context)
            val db = XiaopacaiApp.instance.database.getReadable(passphrase)
            try {
                val cursor = db.rawQuery(
                    "SELECT MAX(last_connected_at) FROM pairing_info WHERE is_active = 1",
                    null
                )
                cursor.use { if (it.moveToFirst()) it.getLong(0) else 0L }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询配对时间失败: ${e.message}")
            0L
        }
    }

    /**
     * 采集数据库文件大小（字节）
     */
    private fun collectDbSize(context: Context): Long {
        return try {
            val dbFile = File(context.applicationInfo.dataDir, "databases/xiaopacai_guardian.db")
            if (dbFile.exists()) dbFile.length() else 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取当前网络类型（wifi/cellular/ethernet/none/other）
     */
    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ==================== 上报 ====================

    /**
     * 立即上报（设置页手动触发 / 每日任务 / 崩溃补报）
     *
     * @return true=发送成功；false=未连接，已缓存待补传
     */
    fun report(context: Context): Boolean {
        if (!isEnabled(context)) {
            Log.d(TAG, "诊断上报已由家长关闭，跳过")
            return false
        }
        try {
            val reportJson = collect(context)
            val sent = sendReport(context, reportJson)
            if (!sent) {
                cachePending(context, reportJson)
                Log.w(TAG, "未连接家长端，诊断报告已缓存待补传")
            }
            return sent
        } catch (e: Exception) {
            Log.e(TAG, "诊断上报异常: ${e.message}")
            return false
        }
    }

    /**
     * 补传缓存的诊断报告（SyncManager 同步循环中调用）
     *
     * @return 成功补传条数
     */
    fun flushPending(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = try {
            JSONArray(prefs.getString(KEY_PENDING, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        if (pending.length() == 0) return 0

        var sentCount = 0
        val remaining = JSONArray()
        for (i in 0 until pending.length()) {
            val reportJson = try {
                pending.getJSONObject(i)
            } catch (e: Exception) {
                continue
            }
            if (sendReport(context, reportJson)) {
                sentCount++
            } else {
                remaining.put(reportJson)
            }
        }
        // 写回剩余待补传报告
        prefs.edit().putString(KEY_PENDING, remaining.toString()).apply()
        if (sentCount > 0) {
            Log.i(TAG, "已补传 $sentCount 条诊断报告")
        }
        return sentCount
    }

    /**
     * 发送单条诊断报告（走 P2P 链路，未连接时返回 false）
     */
    private fun sendReport(context: Context, reportJson: JSONObject): Boolean {
        return try {
            val p2p = GuardianForegroundService.getP2PConnection()
            if (p2p.connectionState.value != P2PConnectionState.CONNECTED &&
                p2p.connectionState.value != P2PConnectionState.HANDSHAKING) {
                recordP2pResult(context, fail = true)
                return false
            }
            reportJson.put("deviceId", getDeviceId(context))
            reportJson.put("timestamp", System.currentTimeMillis() / 1000)
            val message = P2PMessage(
                type = "diagnostics_report",
                // payload 键名与家长端 ParentP2PListenerService.handleDiagnosticsReport 对齐
                payload = mapOf("diagnostics" to reportJson.toString())
            )
            val sent = p2p.sendMessage(message)
            recordP2pResult(context, fail = !sent)
            sent
        } catch (e: Exception) {
            Log.e(TAG, "发送诊断报告失败: ${e.message}")
            false
        }
    }

    /**
     * 缓存未上报的诊断报告（JSON 数组，最多保留 20 条）
     */
    private fun cachePending(context: Context, reportJson: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val existing = try {
                JSONArray(prefs.getString(KEY_PENDING, "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            existing.put(reportJson)
            // 控制缓存上限，丢弃最旧
            while (existing.length() > MAX_PENDING) {
                val trimmed = JSONArray()
                for (i in 1 until existing.length()) {
                    trimmed.put(existing.get(i))
                }
                return prefs.edit().putString(KEY_PENDING, trimmed.toString()).apply()
            }
            prefs.edit().putString(KEY_PENDING, existing.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "缓存诊断报告失败: ${e.message}")
        }
    }

    /**
     * 记录崩溃堆栈（最多保留 5 条）
     */
    fun recordCrash(context: Context, stackTrace: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val crashes = try {
                JSONArray(prefs.getString(KEY_CRASHES, "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            val entry = JSONObject()
            entry.put("time", System.currentTimeMillis() / 1000)
            entry.put("stackTrace", stackTrace.take(2000))
            crashes.put(entry)
            while (crashes.length() > MAX_CRASHES) {
                crashes.remove(0)
            }
            prefs.edit().putString(KEY_CRASHES, crashes.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "记录崩溃失败: ${e.message}")
        }
    }

    /**
     * 记录 P2P 发送结果（诊断历史用）
     */
    private fun recordP2pResult(context: Context, fail: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = if (fail) KEY_P2P_FAIL else KEY_P2P_SUCCESS
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    // ==================== 工具 ====================

    /**
     * 获取应用版本名
     */
    private fun getAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 检查通知权限（Android 13+ 运行时权限）
     */
    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 获取设备 ID
     */
    private fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }
}
