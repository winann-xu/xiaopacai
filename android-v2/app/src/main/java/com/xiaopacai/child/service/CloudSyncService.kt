package com.xiaopacai.child.service

import android.content.Context
import android.util.Log
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.CloudAccountManager
import com.xiaopacai.child.util.DbPassphraseProvider
import com.xiaopacai.child.util.httpGetJson
import com.xiaopacai.child.util.httpPostJson
import com.xiaopacai.child.XiaopacaiApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CloudSyncService {

    private const val TAG = "CloudSyncService"
    private const val PREFS_NAME = "cloud_sync_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_BIND_CODE = "bind_code"
    private const val KEY_REGISTERED = "registered"
    private const val KEY_LAST_POLICY_PULL = "last_policy_pull_ms"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat_ms"
    private const val KEY_LAST_USAGE_REPORT = "last_usage_report_ms"

    const val CLOUD_HOST = "xpc.winann.com"
    const val CLOUD_PORT = 443

    const val POLL_INTERVAL_MS = 5 * 60 * 1000L
    const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    const val USAGE_REPORT_INTERVAL_MS = 15 * 60 * 1000L

    private val _connectionState = MutableStateFlow(CloudSyncState.DISCONNECTED)
    val connectionState: StateFlow<CloudSyncState> = _connectionState

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var syncScope: CoroutineScope? = null
    private var webSocketJob: Job? = null

    enum class CloudSyncState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    sealed class CloudResult {
        data class Success(val data: JSONObject? = null) : CloudResult()
        data class Failed(val reason: String) : CloudResult()
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getBindCode(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BIND_CODE, null)
    }

    fun isRegistered(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REGISTERED, false)
    }

    fun registerDevice(context: Context, bindCode: String): CloudResult {
        val deviceId = getDeviceId(context)
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("bindCode", bindCode)
            put("osVersion", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            put("appVersion", try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) { "unknown" })
        }
        return try {
            val (code, resp, err) = httpPostJson(CLOUD_HOST, CLOUD_PORT, "/api/v1/device/register", body.toString(), null)
            when {
                code in 200..299 -> {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(KEY_BIND_CODE, bindCode)
                        .putBoolean(KEY_REGISTERED, true)
                        .apply()
                    AppLog.i(TAG, "设备注册成功 deviceId=$deviceId")
                    _connectionState.value = CloudSyncState.CONNECTED
                    CloudResult.Success(JSONObject(resp))
                }
                code == 409 -> CloudResult.Failed("绑定码已被使用")
                code == 404 -> CloudResult.Failed("绑定码无效")
                else -> CloudResult.Failed("注册失败: HTTP $code")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "设备注册网络异常: ${e.message}")
            _lastError.value = e.message
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    fun pullPolicies(context: Context): CloudResult {
        val deviceId = getDeviceId(context)
        val token = CloudAccountManager.getToken(context)
        return try {
            val (code, resp, err) = httpGetJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/policies?deviceId=$deviceId", token)
            when {
                code in 200..299 -> {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_LAST_POLICY_PULL, System.currentTimeMillis())
                        .apply()
                    applyPolicies(context, resp)
                    AppLog.i(TAG, "策略拉取成功")
                    CloudResult.Success(JSONObject(resp))
                }
                code == 401 -> CloudResult.Failed("认证失败，请重新绑定")
                else -> CloudResult.Failed("策略拉取失败: HTTP $code")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "策略拉取网络异常: ${e.message}")
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    fun reportUsage(context: Context): CloudResult {
        val deviceId = getDeviceId(context)
        val token = CloudAccountManager.getToken(context)
        val collector = GuardianForegroundService.getCollector() ?: return CloudResult.Failed("采集器未运行")

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("timestamp", System.currentTimeMillis() / 1000)
            put("todayUsedMinutes", collector.todayAdjustedMinutes.toInt())
            put("todayLimitMinutes", collector.todayLimitMinutes.toInt())
            put("isTimeoutActive", collector.isTimeoutActive)
            put("stopMode", collector.stopMode)
        }

        return try {
            val (code, resp, err) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/usage-report", body.toString(), token)
            when {
                code in 200..299 -> {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_LAST_USAGE_REPORT, System.currentTimeMillis())
                        .apply()
                    CloudResult.Success()
                }
                else -> CloudResult.Failed("使用报告失败: HTTP $code")
            }
        } catch (e: Exception) {
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    fun sendHeartbeat(context: Context): CloudResult {
        val deviceId = getDeviceId(context)
        val token = CloudAccountManager.getToken(context)
        val collector = GuardianForegroundService.getCollector()

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("timestamp", System.currentTimeMillis() / 1000)
            put("guardServiceRunning", collector != null)
            put("appVersion", try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) { "unknown" })
        }

        return try {
            val (code, resp, err) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/heartbeat", body.toString(), token)
            when {
                code in 200..299 -> {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
                        .apply()
                    _connectionState.value = CloudSyncState.CONNECTED
                    _lastError.value = null
                    CloudResult.Success()
                }
                else -> {
                    _connectionState.value = CloudSyncState.ERROR
                    CloudResult.Failed("心跳失败: HTTP $code")
                }
            }
        } catch (e: Exception) {
            _lastError.value = e.message
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    fun requestEmergencyRelease(context: Context, reason: String): CloudResult {
        val deviceId = getDeviceId(context)
        val token = CloudAccountManager.getToken(context)
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("reason", reason)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        return try {
            val (code, resp, err) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/emergency-release", body.toString(), token)
            when {
                code in 200..299 -> CloudResult.Success(JSONObject(resp))
                else -> CloudResult.Failed("远程紧急解除失败: HTTP $code")
            }
        } catch (e: Exception) {
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    private fun applyPolicies(context: Context, responseBody: String) {
        try {
            val json = JSONObject(responseBody)
            val policies = json.optJSONArray("policies") ?: return
            val passphrase = DbPassphraseProvider.getPassphrase(context)
            val db = XiaopacaiApp.instance.database.getWritable(passphrase)
            db.delete("policy_cache", null, null)
            for (i in 0 until policies.length()) {
                val p = policies.getJSONObject(i)
                db.execSQL(
                    """INSERT OR REPLACE INTO policy_cache (policy_type, policy_data, version, applied_at)
                       VALUES (?, ?, ?, ?)""",
                    arrayOf(
                        p.optString("policyType"),
                        p.toString(),
                        p.optInt("version", 1),
                        (System.currentTimeMillis() / 1000).toString()
                    )
                )
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "策略应用失败: ${e.message}")
        }
    }

    fun startPolling(context: Context, scope: CoroutineScope) {
        syncScope = scope
        scope.launch {
            while (isActive) {
                try {
                    sendHeartbeat(context)
                    pullPolicies(context)
                    reportUsage(context)
                } catch (e: Exception) {
                    AppLog.w(TAG, "同步循环异常: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        syncScope?.cancel()
        syncScope = null
        webSocketJob?.cancel()
        webSocketJob = null
        _connectionState.value = CloudSyncState.DISCONNECTED
    }

    fun generateBindCode(): String {
        return (100000..999999).random().toString()
    }

    fun reportGuardEvent(context: Context, event: JSONObject): Boolean {
        val token = CloudAccountManager.getToken(context)
        val deviceId = getDeviceId(context)
        event.put("deviceId", deviceId)
        return try {
            val (code, _, _) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/guard-event", event.toString(), token)
            if (code in 200..299) {
                AppLog.i(TAG, "守护事件已上报")
                true
            } else {
                AppLog.w(TAG, "守护事件上报失败: HTTP $code")
                false
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "守护事件上报网络异常: ${e.message}")
            false
        }
    }

    fun reportAnnouncementAck(context: Context, announcementId: String): Boolean {
        val token = CloudAccountManager.getToken(context)
        val deviceId = getDeviceId(context)
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("announcementId", announcementId)
            put("acknowledgedAt", System.currentTimeMillis() / 1000)
        }
        return try {
            val (code, _, _) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/announcement-ack", body.toString(), token)
            if (code in 200..299) {
                AppLog.i(TAG, "公告回执已上报: $announcementId")
                true
            } else {
                AppLog.w(TAG, "公告回执上报失败: HTTP $code")
                false
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "公告回执网络异常: ${e.message}")
            false
        }
    }

    fun getLastSyncInfo(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0)
        val lastPolicy = prefs.getLong(KEY_LAST_POLICY_PULL, 0)
        val lastUsage = prefs.getLong(KEY_LAST_USAGE_REPORT, 0)
        return if (lastHeartbeat > 0) {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            "心跳: ${sdf.format(java.util.Date(lastHeartbeat))}"
        } else "未连接"
    }
}
