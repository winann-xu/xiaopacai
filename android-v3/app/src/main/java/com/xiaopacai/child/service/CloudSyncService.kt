package com.xiaopacai.child.service

import android.content.Context
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.CloudAccountManager
import com.xiaopacai.child.util.DbPassphraseProvider
import com.xiaopacai.child.util.KeyStoreManager
import com.xiaopacai.child.util.httpGetJson
import com.xiaopacai.child.util.httpPostJson
import com.xiaopacai.child.XiaopacaiApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

object CloudSyncService {

    private const val TAG = "CloudSyncService"
    private const val PREFS_NAME = "cloud_sync_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_BIND_CODE = "bind_code"
    private const val KEY_REGISTERED = "registered"
    private const val KEY_LAST_POLICY_PULL = "last_policy_pull_ms"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat_ms"
    private const val KEY_LAST_USAGE_REPORT = "last_usage_report_ms"
    private const val KEY_DEVICE_TOKEN = "device_token_encrypted"

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

    /**
     * [TASK-V2.0.6-UNBIND-SYNC] 设备已在 Web 端解绑（服务端硬删除设备行，后续接口返回 404）：
     * 清除本地账号绑定 + 设备注册/令牌/绑定码，驱动 UI 回到「未绑定」并可重新绑定。
     * 幂等：重复调用安全。
     */
    fun handleDeviceUnbound(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_BIND_CODE)
            .putBoolean(KEY_REGISTERED, false)
            .remove(KEY_LAST_HEARTBEAT)
            .remove(KEY_LAST_POLICY_PULL)
            .remove(KEY_LAST_USAGE_REPORT)
            .apply()
        CloudAccountManager.clearAccount(context)
        _connectionState.value = CloudSyncState.DISCONNECTED
        _lastError.value = "设备已在 Web 端解绑，请重新绑定"
        AppLog.w(TAG, "检测到设备已解绑（服务端 404），本地绑定已清除")
    }

    /**
     * [TASK-V2.0.6-UNBIND-SYNC] 首页/轮询统一入口：同步一次绑定状态。
     * - 已有设备令牌：直接心跳；服务端 404 = Web 端解绑（硬删除设备行）→ 清除本地绑定；
     * - 无令牌（旧绑定/令牌丢失）：匿名注册探测——服务端设备行已删或未绑定返回 200，
     *   此时本地"已绑定"是残留，清除并提示重新绑定；行仍存在且已绑定返回 409，保留显示。
     */
    fun syncBindingStatus(context: Context) {
        if (getDeviceToken(context) != null) {
            sendHeartbeat(context)
            return
        }
        val reg = registerDevice(context, "")
        if (reg is CloudResult.Success) {
            // 服务端确认设备未绑定（或不存在）：本地"已绑定"是残留，清除
            if (CloudAccountManager.isBound(context)) {
                CloudAccountManager.clearAccount(context)
                _lastError.value = "设备已在 Web 端解绑，请重新绑定"
                AppLog.w(TAG, "服务端确认设备未绑定（匿名注册成功），已清除本地残留绑定")
            }
        }
    }

    fun getDeviceToken(context: Context): String? {
        val encrypted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_TOKEN, null)
            ?.takeIf { it.isNotBlank() } ?: return null
        return KeyStoreManager.decryptPrefsValue(encrypted)?.takeIf { it.isNotBlank() }
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
                    val token = try { JSONObject(resp).optString("token", "") } catch (_: Exception) { "" }
                    val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(KEY_BIND_CODE, bindCode)
                        .putBoolean(KEY_REGISTERED, true)
                    if (token.isNotBlank()) {
                        editor.putString(KEY_DEVICE_TOKEN, KeyStoreManager.encryptPrefsValue(token))
                    }
                    editor.apply()
                    AppLog.i(TAG, "设备注册成功 deviceId=$deviceId tokenSaved=${token.isNotBlank()}")
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

    // [V2.0.5] 确保设备已注册（拿到设备令牌）：绑定成功或启动同步前调用，避免云端同步 401 死循环
    fun ensureRegistered(context: Context) {
        if (getDeviceToken(context) != null) return
        val result = registerDevice(context, "")
        if (result is CloudResult.Failed) {
            AppLog.w(TAG, "设备注册失败: ${result.reason}")
        }
    }

    fun pullPolicies(context: Context): CloudResult {
        val token = getDeviceToken(context)
        return try {
            val (code, resp, err) = httpGetJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/policies", token)
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
                code == 404 -> {
                    handleDeviceUnbound(context)
                    CloudResult.Failed("设备已在 Web 端解绑，请重新绑定")
                }
                else -> CloudResult.Failed("策略拉取失败: HTTP $code")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "策略拉取网络异常: ${e.message}")
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    fun reportUsage(context: Context): CloudResult {
        val deviceId = getDeviceId(context)
        val token = getDeviceToken(context)
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
                code == 404 -> {
                    handleDeviceUnbound(context)
                    CloudResult.Failed("设备已在 Web 端解绑，请重新绑定")
                }
                else -> CloudResult.Failed("使用报告失败: HTTP $code")
            }
        } catch (e: Exception) {
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    fun sendHeartbeat(context: Context): CloudResult {
        val deviceId = getDeviceId(context)
        val token = getDeviceToken(context)
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
                code == 404 -> {
                    handleDeviceUnbound(context)
                    CloudResult.Failed("设备已在 Web 端解绑，请重新绑定")
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

    fun requestEmergencyRelease(context: Context, reason: String, durationMinutes: Int = 60): CloudResult {
        val deviceId = getDeviceId(context)
        val token = CloudAccountManager.getToken(context)
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("reason", reason)
            put("durationMinutes", durationMinutes)
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
                val version = p.optInt("version", 1)
                val now = (System.currentTimeMillis() / 1000).toString()
                insertPolicyRow(db, "daily_limit", JSONObject().apply {
                    put("limitMinutes", p.optInt("dailyLimitMinutes", 0))
                    put("restrictMode", when (p.optString("overtimeAction", "full_lock")) {
                        "partial_lock" -> "partial"
                        "warn_only" -> "warn"
                        else -> "full"
                    })
                }, version, now)
                val bedtimeStart = p.optString("bedtimeStart", "")
                val bedtimeEnd = p.optString("bedtimeEnd", "")
                if (bedtimeStart.isNotBlank() && bedtimeEnd.isNotBlank()) {
                    insertPolicyRow(db, "sleep_time", JSONObject().apply {
                        put("sleepStart", bedtimeStart)
                        put("sleepEnd", bedtimeEnd)
                    }, version, now)
                }
                for ((key, type) in listOf(
                    "categoryGameLimit" to "category_game",
                    "categorySocialLimit" to "category_social",
                    "categoryVideoLimit" to "category_video",
                    "categoryLearningLimit" to "category_learning"
                )) {
                    val limit = p.optInt(key, -1)
                    if (limit >= 0) {
                        insertPolicyRow(db, type, JSONObject().apply {
                            put("limitMinutes", limit)
                        }, version, now)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "策略应用失败: ${e.message}")
        }
    }

    private fun insertPolicyRow(db: net.sqlcipher.database.SQLiteDatabase, policyType: String, data: JSONObject, version: Int, appliedAt: String) {
        db.execSQL(
            """INSERT OR REPLACE INTO policy_cache (policy_type, policy_data, version, applied_at)
               VALUES (?, ?, ?, ?)""",
            arrayOf(policyType, data.toString(), version, appliedAt)
        )
    }

    fun startPolling(context: Context, scope: CoroutineScope) {
        syncScope = scope
        scope.launch {
            while (isActive) {
                try {
                    // [TASK-V2.0.6-UNBIND-SYNC] 先同步绑定状态（心跳探测解绑 / 注册探测自愈）
                    syncBindingStatus(context)
                    if (getDeviceToken(context) != null) {
                        pullPolicies(context)
                        reportUsage(context)
                        pullAnnouncements(context)
                    }
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
        _connectionState.value = CloudSyncState.DISCONNECTED
    }

    fun generateBindCode(): String {
        return (100000..999999).random().toString()
    }

    fun generatePairCode(parentJwt: String): CloudResult {
        val body = JSONObject().apply { put("method", "manual") }
        return try {
            val (code, resp, err) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/pairing/generate-code", body.toString(), parentJwt)
            when {
                code in 200..299 -> {
                    AppLog.i(TAG, "配对码生成成功")
                    CloudResult.Success(JSONObject(resp))
                }
                code == 401 -> CloudResult.Failed("认证失败，请重新登录")
                else -> CloudResult.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "配对码生成网络异常: ${e.message}")
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    fun verifyPairCode(parentJwt: String, pairCode: String, deviceId: String,
                       deviceName: String, platform: String): CloudResult {
        val body = JSONObject().apply {
            put("pairCode", pairCode)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("platform", platform)
        }
        return try {
            val (code, resp, err) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/pairing/verify", body.toString(), parentJwt)
            when {
                code in 200..299 -> {
                    AppLog.i(TAG, "设备绑定成功 deviceId=$deviceId")
                    CloudResult.Success(JSONObject(resp))
                }
                code == 403 -> CloudResult.Failed("device_owned_by_other: 该设备已绑定其它账号")
                code == 401 -> CloudResult.Failed("认证失败，请重新登录")
                else -> CloudResult.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "设备绑定网络异常: ${e.message}")
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    // [V2.0.5] 儿童端扫码/输入配对码绑定：设备令牌 + 家长在 Web 生成的配对码，绑定到该家长账号
    fun bindWithCode(context: Context, pairCode: String): CloudResult {
        val token = getDeviceToken(context)
        val body = JSONObject().apply {
            put("pairCode", pairCode.trim())
        }
        return try {
            val (code, resp, err) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/bind-with-code", body.toString(), token)
            when {
                code in 200..299 -> {
                    AppLog.i(TAG, "扫码/配对码绑定成功")
                    CloudResult.Success(JSONObject(resp))
                }
                code == 403 -> CloudResult.Failed("device_owned_by_other: 该设备已绑定其它账号")
                code == 401 -> CloudResult.Failed("认证失败，请重新注册设备")
                code == 404 -> {
                    handleDeviceUnbound(context)
                    CloudResult.Failed("设备已解绑，请重新绑定")
                }
                else -> CloudResult.Failed("HTTP $code $err")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "扫码/配对码绑定网络异常: ${e.message}")
            CloudResult.Failed("网络异常: ${e.message}")
        }
    }

    fun reportGuardEvent(context: Context, event: JSONObject): Boolean {
        val token = getDeviceToken(context)
        val deviceId = getDeviceId(context)
        event.put("deviceId", deviceId)
        return try {
            val (code, _, _) = httpPostJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/guard-event", event.toString(), token)
            if (code in 200..299) {
                AppLog.i(TAG, "守护事件已上报")
                true
            } else {
                if (code == 404) handleDeviceUnbound(context)
                AppLog.w(TAG, "守护事件上报失败: HTTP $code")
                false
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "守护事件上报网络异常: ${e.message}")
            false
        }
    }

    fun pullAnnouncements(context: Context): Boolean {
        val token = getDeviceToken(context) ?: return false
        if (token.isEmpty()) return false
        return try {
            val (code, body, _) = httpGetJson(CLOUD_HOST, CLOUD_PORT,
                "/api/v1/device/announcements", token)
            if (code in 200..299 && !body.isNullOrEmpty()) {
                val root = JSONObject(body)
                val arr = root.optJSONArray("announcements") ?: return true
                val passphrase = com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(context)
                val db = com.xiaopacai.child.XiaopacaiApp.instance.database.getWritable(passphrase)
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val id = item.optInt("id", 0)
                    if (id <= 0) continue
                    val title = item.optString("title", "")
                    val content = item.optString("content", "")
                    val priority = item.optString("priority", "normal")
                    val version = item.optInt("version", 1)
                    val publishedAt = item.optLong("publishedAt", 0)
                    val expiresAt = item.optLong("expiresAt", 0)
                    val acknowledgedAt = item.optLong("acknowledgedAt", 0)
                    val priorityInt = when (priority) {
                        "urgent" -> 3; "important" -> 2; else -> 1
                    }
                    val isRead = if (acknowledgedAt > 0) 1 else 0
                    db.execSQL(
                        """INSERT OR REPLACE INTO announcements
                           (announcement_id, title, content, priority, version, created_at, expires_at, acknowledged_at, is_read)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        arrayOf(id.toString(), title, content, priorityInt.toString(),
                            version.toString(), publishedAt.toString(), expiresAt.toString(),
                            acknowledgedAt.toString(), isRead.toString())
                    )
                }
                AppLog.i(TAG, "公告拉取成功: ${arr.length()} 条")
                true
            } else {
                if (code == 404) handleDeviceUnbound(context)
                AppLog.w(TAG, "公告拉取失败: HTTP $code")
                false
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "公告拉取网络异常: ${e.message}")
            false
        }
    }

    fun reportAnnouncementAck(context: Context, announcementId: String): Boolean {
        val token = getDeviceToken(context)
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
                if (code == 404) handleDeviceUnbound(context)
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
