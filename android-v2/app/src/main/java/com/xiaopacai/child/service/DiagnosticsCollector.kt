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
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.DbPassphraseProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object DiagnosticsCollector {

    private const val TAG = "DiagnosticsCollector"
    private const val PREFS_NAME = "diagnostics_prefs"
    private const val KEY_PENDING = "pending_reports"
    private const val KEY_CRASHES = "recent_crashes"
    private const val KEY_ENABLED = "diagnostics_enabled"
    private const val MAX_CRASHES = 5
    private const val MAX_PENDING = 20

    fun start(context: Context) {
        installCrashHandler(context)
        scheduleDaily(context)
    }

    private fun installCrashHandler(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                recordCrash(context, stackTrace)
                AppLog.eCrash("Crash", stackTrace)
                Log.e(TAG, "捕获崩溃: ${throwable.message}")
            } catch (_: Exception) {}
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun scheduleDaily(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<DiagnosticsDailyWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "diagnostics_daily", ExistingPeriodicWorkPolicy.KEEP, request)
            Log.i(TAG, "每日诊断上报已调度")
        } catch (e: Exception) {
            Log.e(TAG, "调度每日诊断上报失败: ${e.message}")
        }
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun collect(context: Context): JSONObject {
        val report = JSONObject()
        report.put("appVersion", getAppVersion(context))
        report.put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        report.put("deviceModel", Build.MODEL)
        report.put("manufacturer", Build.MANUFACTURER)
        report.put("permissionStatus", collectPermissionStatus(context))
        report.put("serviceStatus", collectServiceStatus(context))
        report.put("recentCrashes", collectRecentCrashes(context))
        report.put("cloudSyncState", CloudSyncService.connectionState.value.name)
        report.put("dbSizeBytes", collectDbSize(context))
        report.put("networkType", getNetworkType(context))
        report.put("health", GuardDownMonitor.computeHealth(context))
        report.put("emergencyReleaseActive", EmergencyReleaseService.isActive(context))
        return report
    }

    private fun collectPermissionStatus(context: Context): JSONObject {
        val perms = JSONObject()
        perms.put("accessibility", AntiBypassService.isAccessibilityServiceEnabled(context))
        perms.put("usageStats", AntiBypassService.isUsageStatsPermissionGranted(context))
        perms.put("deviceAdmin", GuardianDeviceAdminReceiver.isActive(context))
        perms.put("notification", hasNotificationPermission(context))
        perms.put("batteryOptimizationDisabled", !AntiBypassService.isBatteryOptimizationEnabled(context))
        return perms
    }

    private fun collectServiceStatus(context: Context): JSONObject {
        val status = JSONObject()
        status.put("guardianServiceRunning", GuardianForegroundService.getCollector() != null)
        status.put("accessibilityServiceRunning", AntiBypassService.isAccessibilityServiceEnabled(context))
        status.put("cloudConnected", CloudSyncService.connectionState.value == CloudSyncService.CloudSyncState.CONNECTED)
        return status
    }

    private fun collectRecentCrashes(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try { JSONArray(prefs.getString(KEY_CRASHES, "[]")) } catch (_: Exception) { JSONArray() }
    }

    private fun collectDbSize(context: Context): Long {
        return try {
            val dbFile = File(context.applicationInfo.dataDir, "databases/xiaopacai_guardian.db")
            if (dbFile.exists()) dbFile.length() else 0L
        } catch (_: Exception) { 0L }
    }

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
        } catch (_: Exception) { "unknown" }
    }

    fun report(context: Context): Boolean {
        if (!isEnabled(context)) return false
        return try {
            val reportJson = collect(context)
            val sent = sendReport(context, reportJson)
            if (!sent) cachePending(context, reportJson)
            sent
        } catch (e: Exception) {
            Log.e(TAG, "诊断上报异常: ${e.message}")
            false
        }
    }

    fun flushPending(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = try { JSONArray(prefs.getString(KEY_PENDING, "[]")) } catch (_: Exception) { JSONArray() }
        if (pending.length() == 0) return 0
        var sentCount = 0
        val remaining = JSONArray()
        for (i in 0 until pending.length()) {
            val reportJson = try { pending.getJSONObject(i) } catch (_: Exception) { continue }
            if (sendReport(context, reportJson)) sentCount++ else remaining.put(reportJson)
        }
        prefs.edit().putString(KEY_PENDING, remaining.toString()).apply()
        return sentCount
    }

    private fun sendReport(context: Context, reportJson: JSONObject): Boolean {
        return try {
            val deviceId = getDeviceId(context)
            reportJson.put("deviceId", deviceId)
            reportJson.put("timestamp", System.currentTimeMillis() / 1000)
            val token = com.xiaopacai.child.util.CloudAccountManager.getToken(context)
            val (code, _, _) = com.xiaopacai.child.util.httpPostJson(
                CloudSyncService.CLOUD_HOST, CloudSyncService.CLOUD_PORT,
                "/api/v1/device/diagnostics-report", reportJson.toString(), token)
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "发送诊断报告失败: ${e.message}")
            false
        }
    }

    private fun cachePending(context: Context, reportJson: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val existing = try { JSONArray(prefs.getString(KEY_PENDING, "[]")) } catch (_: Exception) { JSONArray() }
            existing.put(reportJson)
            while (existing.length() > MAX_PENDING) existing.remove(0)
            prefs.edit().putString(KEY_PENDING, existing.toString()).apply()
        } catch (_: Exception) {}
    }

    fun recordCrash(context: Context, stackTrace: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val crashes = try { JSONArray(prefs.getString(KEY_CRASHES, "[]")) } catch (_: Exception) { JSONArray() }
            val entry = JSONObject()
            entry.put("time", System.currentTimeMillis() / 1000)
            entry.put("stackTrace", stackTrace.take(2000))
            crashes.put(entry)
            while (crashes.length() > MAX_CRASHES) crashes.remove(0)
            prefs.edit().putString(KEY_CRASHES, crashes.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun getAppVersion(context: Context): String {
        return try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown" }
        catch (_: Exception) { "unknown" }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun getDeviceId(context: Context): String {
        return CloudSyncService.getDeviceId(context)
    }
}
