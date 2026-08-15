package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import com.xiaopacai.child.data.database.ParentDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * [TASK-MILESTONE-V3] 需求 10+11：家长端云端同步层（服务端为权威）
 *
 * 与 Web 同源接口对接（GET/PUT /api/policies、/api/announcements、/api/reports、/api/devices）：
 * - 在线：实时拉取/修改，策略 PUT 携带 expectedVersion（A2 乐观并发，409 返回服务端最新版）；
 * - 离线：网络不可达（CloudConnectionException）→ 调用方读 prefs 快照缓存并标注「离线数据」；
 * - 缓存：设备列表 / 单设备策略 / 报告快照存 SharedPreferences；公告镜像存 parent_announcements
 *   （ParentDao.replaceAllAnnouncements，web- 前缀区分服务端 id）；
 * - 新账号绑定全量覆盖：需求 3/4 的 LocalDataWipe 清空本地后，下一次在线拉取即全量重建。
 */
object ParentCloudSync {

    private const val TAG = "ParentCloudSync"
    private const val PREFS_CACHE = "xiaopacai_parent_cloud_cache"
    private const val CACHE_DEVICES = "devices"
    private const val CACHE_POLICY_PREFIX = "policy_"
    private const val CACHE_REPORT_PREFIX = "report_"
    private const val CACHE_ANN_SYNC_AT = "ann_sync_at"

    // ==================== 结果封装 ====================

    sealed class Result<out T> {
        data class Ok<T>(val data: T) : Result<T>()

        /** offline=true：网络不可达（走本地缓存分支）；否则为业务错误（展示 message） */
        data class Err(val offline: Boolean, val message: String) : Result<Nothing>()
    }

    /** 策略保存结果：409 冲突单独成支（携带服务端最新策略，调用方采纳后重载） */
    sealed class PolicySaveResult {
        data class Saved(val policy: JSONObject, val pushed: Boolean) : PolicySaveResult()
        data class Conflict(val serverPolicy: JSONObject) : PolicySaveResult()
        data class Failed(val offline: Boolean, val message: String) : PolicySaveResult()
    }

    // ==================== 纯函数映射（可单测） ====================

    /** 优先级：本地 int（0/1/2）↔ 服务端字符串（normal/important/urgent） */
    fun priorityToServer(p: Int): String = when (p) {
        2 -> "urgent"
        1 -> "important"
        else -> "normal"
    }

    fun priorityFromServer(s: String?): Int = when (s) {
        "urgent" -> 2
        "important" -> 1
        else -> 0
    }

    /** 超时动作：本地 UI 值（full/partial/none）↔ 服务端（full_lock/partial_lock/warn_only） */
    fun stopModeToServer(mode: String): String = when (mode) {
        "partial" -> "partial_lock"
        "none" -> "warn_only"
        else -> "full_lock"
    }

    fun stopModeFromServer(action: String?): String = when (action) {
        "partial_lock" -> "partial"
        "warn_only" -> "none"
        else -> "full"
    }

    /** 分类键归一（与 Web ReportAggregator.NormalizeCategory 同口径：study→learning，空→other） */
    fun normalizeCategoryKey(key: String?): String {
        if (key.isNullOrBlank()) return "other"
        val c = key.trim().lowercase(Locale.ROOT)
        return if (c == "study") "learning" else c
    }

    // ==================== 基础设施 ====================

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)

    private fun hostPort(context: Context): Pair<String, Int>? {
        val h = CloudAccountManager.getServerHost(context) ?: return null
        return h to CloudAccountManager.getServerPort(context)
    }

    private fun token(context: Context): String? = CloudAccountManager.getToken(context)

    /** 统一网络执行：IO 线程 + 连接异常归类为离线 */
    private suspend fun <T> ioCall(context: Context, block: () -> Result<T>): Result<T> = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CloudConnectionException) {
        Result.Err(true, CloudAccountManager.loginNetworkErrorMessage(context, e))
    } catch (e: Exception) {
        Log.w(TAG, "云端同步异常: ${e.message}")
        Result.Err(false, "网络异常: ${e.message}")
    }

    private fun parseError(errBody: String, fallback: String): String = try {
        JSONObject(errBody).optString("error", "").ifBlank { fallback }
    } catch (e: Exception) {
        fallback
    }

    // ==================== 设备列表 ====================

    /** GET /api/devices（账号隔离：仅本账号设备）；成功即刷新本地快照缓存 */
    suspend fun fetchDevices(context: Context): Result<JSONArray> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val (code, body, err) = httpGetJson(host, port, "/api/devices", tk)
            when {
                code in 200..299 -> {
                    val devices = JSONObject(body).optJSONArray("devices") ?: JSONArray()
                    prefs(context).edit().putString(CACHE_DEVICES, devices.toString()).apply()
                    AppLog.i(TAG, "设备列表已同步: ${devices.length()} 台")
                    Result.Ok(devices)
                }
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "获取设备列表失败: HTTP $code"))
            }
        }
    }

    /** 离线缓存：设备列表快照（无则 null） */
    fun cachedDevices(context: Context): JSONArray? {
        val raw = prefs(context).getString(CACHE_DEVICES, null) ?: return null
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 策略（服务端权威 + 乐观并发） ====================

    /** GET /api/policies/{deviceId}；成功即刷新本地快照缓存 */
    suspend fun fetchPolicy(context: Context, serverDeviceId: Long): Result<JSONObject> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val (code, body, err) = httpGetJson(host, port, "/api/policies/$serverDeviceId", tk)
            when {
                code in 200..299 -> {
                    val policy = JSONObject(body)
                    prefs(context).edit()
                        .putString(CACHE_POLICY_PREFIX + serverDeviceId, policy.toString())
                        .apply()
                    Result.Ok(policy)
                }
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "获取策略失败: HTTP $code"))
            }
        }
    }

    /** 离线缓存：单设备策略快照（无则 null） */
    fun cachedPolicy(context: Context, serverDeviceId: Long): JSONObject? {
        val raw = prefs(context).getString(CACHE_POLICY_PREFIX + serverDeviceId, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * PUT /api/policies/{deviceId}：携带 expectedVersion（A2）。
     * - 409：其他端已修改 → Conflict(服务端最新策略)，调用方采纳并重载；
     * - 白名单/黑名单原样回传（服务端会整体覆盖，未传即清空，须保留）。
     */
    suspend fun savePolicy(
        context: Context,
        serverDeviceId: Long,
        policy: JSONObject
    ): PolicySaveResult {
        val (host, port) = hostPort(context)
            ?: return PolicySaveResult.Failed(false, "尚未配置服务器地址")
        val tk = token(context) ?: return PolicySaveResult.Failed(false, "登录已过期，请重新登录")
        return try {
            withContext(Dispatchers.IO) {
                val version = policy.optInt("version", 0)
                val body = JSONObject().apply {
                    put("dailyLimitMinutes", policy.optInt("dailyLimitMinutes", 120))
                    put("bedtimeStart", policy.optString("bedtimeStart", ""))
                    put("bedtimeEnd", policy.optString("bedtimeEnd", ""))
                    put("timeoutAction", stopModeToServer(policy.optString("timeoutAction", "full_lock")))
                    put("whitelist", policy.optJSONArray("whitelist") ?: JSONArray())
                    put("blacklist", policy.optJSONArray("blacklist") ?: JSONArray())
                    if (version > 0) put("expectedVersion", version)
                }
                val (code, resp, err) = httpPutJson(host, port, "/api/policies/$serverDeviceId", body.toString(), tk)
                when {
                    code in 200..299 -> {
                        val respJson = try { JSONObject(resp) } catch (e: Exception) { JSONObject() }
                        val saved = respJson.optJSONObject("policy") ?: policy
                        prefs(context).edit()
                            .putString(CACHE_POLICY_PREFIX + serverDeviceId, saved.toString())
                            .apply()
                        AppLog.i(TAG, "策略已保存: deviceId=$serverDeviceId v${saved.optInt("version")}")
                        PolicySaveResult.Saved(saved, respJson.optBoolean("pushed", false))
                    }
                    code == 409 -> {
                        val serverPolicy = try {
                            JSONObject(err).optJSONObject("policy") ?: policy
                        } catch (e: Exception) {
                            policy
                        }
                        Log.w(TAG, "策略并发冲突: deviceId=$serverDeviceId, serverVersion=${serverPolicy.optInt("version")}")
                        AppLog.w(TAG, "策略并发冲突 deviceId=$serverDeviceId，采纳服务端 v${serverPolicy.optInt("version")}")
                        PolicySaveResult.Conflict(serverPolicy)
                    }
                    code == 401 -> PolicySaveResult.Failed(false, "登录已过期，请重新登录")
                    else -> PolicySaveResult.Failed(false, parseError(err, "保存失败: HTTP $code"))
                }
            }
        } catch (e: CloudConnectionException) {
            PolicySaveResult.Failed(true, CloudAccountManager.loginNetworkErrorMessage(context, e))
        } catch (e: Exception) {
            PolicySaveResult.Failed(false, "网络异常: ${e.message}")
        }
    }

    // ==================== 公告（服务端权威，本地表为镜像） ====================

    /** GET /api/announcements（账号隔离）→ 全量覆盖本地 parent_announcements */
    suspend fun fetchAnnouncements(context: Context): Result<JSONArray> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val (code, body, err) = httpGetJson(host, port, "/api/announcements", tk)
            when {
                code in 200..299 -> {
                    val arr = JSONArray(body)
                    val count = ParentDao.replaceAllAnnouncements(context, arr)
                    prefs(context).edit().putLong(CACHE_ANN_SYNC_AT, System.currentTimeMillis()).apply()
                    Log.i(TAG, "公告已同步：$count 条")
                    AppLog.i(TAG, "公告已同步: $count 条")
                    Result.Ok(arr)
                }
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "获取公告失败: HTTP $code"))
            }
        }
    }

    /** 公告变更后重拉列表（服务端为权威，一次拉取保证全量一致） */
    suspend fun refetchAnnouncements(context: Context): Result<JSONArray> =
        fetchAnnouncements(context)

    suspend fun createAnnouncement(
        context: Context, title: String, content: String, priority: Int
    ): Result<JSONObject> = postAnnouncement(context, "/api/announcements", JSONObject().apply {
        put("title", title)
        put("content", content)
        put("priority", priorityToServer(priority))
        put("status", "draft")
    })

    suspend fun updateAnnouncement(
        context: Context, serverId: Long, title: String, content: String, priority: Int
    ): Result<JSONObject> = putAnnouncement(context, serverId, JSONObject().apply {
        put("title", title)
        put("content", content)
        put("priority", priorityToServer(priority))
    })

    suspend fun publishAnnouncement(context: Context, serverId: Long): Result<JSONObject> =
        postAnnouncement(context, "/api/announcements/$serverId/publish", JSONObject())

    suspend fun revokeAnnouncement(context: Context, serverId: Long): Result<JSONObject> =
        postAnnouncement(context, "/api/announcements/$serverId/revoke", JSONObject())

    suspend fun deleteAnnouncement(context: Context, serverId: Long): Result<Unit> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val (code, _, err) = httpDeleteJson(host, port, "/api/announcements/$serverId", tk)
            when {
                code in 200..299 -> Result.Ok(Unit)
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "删除失败: HTTP $code"))
            }
        }
    }

    private suspend fun postAnnouncement(
        context: Context, path: String, body: JSONObject
    ): Result<JSONObject> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val (code, resp, err) = httpPostJson(host, port, path, body.toString(), tk)
            when {
                code in 200..299 -> Result.Ok(JSONObject(resp))
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "操作失败: HTTP $code"))
            }
        }
    }

    private suspend fun putAnnouncement(
        context: Context, serverId: Long, body: JSONObject
    ): Result<JSONObject> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val (code, resp, err) = httpPutJson(host, port, "/api/announcements/$serverId", body.toString(), tk)
            when {
                code in 200..299 -> Result.Ok(JSONObject(resp))
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "编辑失败: HTTP $code"))
            }
        }
    }

    // ==================== 报告（口径与 Web 一致） ====================

    /**
     * 按周期拉取报告并归一化为统一结构：
     * ```
     * {
     *   totalMinutes, limitMinutes, rawAccumulated,
     *   dailyTotals: [{date, totalMinutes}],
     *   categories:  [{key, name, minutes, percent}]   // name/percent 由服务端计算，与 Web 一致
     * }
     * ```
     * - 1 天 → GET /api/reports/daily（服务端默认上海今日）
     * - 7 天 → GET /api/reports/weekly（默认近 7 天）
     * - 30 天 → GET /api/reports/export?format=json（逐日聚合合并）
     */
    suspend fun fetchReport(context: Context, periodDays: Int): Result<JSONObject> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val path = when (periodDays) {
                1 -> "/api/reports/daily"
                7 -> "/api/reports/weekly"
                else -> {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                    }
                    val now = System.currentTimeMillis()
                    val today = sdf.format(java.util.Date(now))
                    val from = sdf.format(java.util.Date(now - (periodDays - 1) * 86400_000L))
                    "/api/reports/export?format=json&from=$from&to=$today"
                }
            }
            val (code, body, err) = httpGetJson(host, port, path, tk)
            when {
                code in 200..299 -> {
                    val normalized = normalizeReport(periodDays, body)
                    prefs(context).edit()
                        .putString(CACHE_REPORT_PREFIX + periodDays, normalized.toString())
                        .apply()
                    Result.Ok(normalized)
                }
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "获取报告失败: HTTP $code"))
            }
        }
    }

    /** 离线缓存：报告快照（无则 null） */
    fun cachedReport(context: Context, periodDays: Int): JSONObject? {
        val raw = prefs(context).getString(CACHE_REPORT_PREFIX + periodDays, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    /** 三种周期响应归一化（纯函数，可单测） */
    fun normalizeReport(periodDays: Int, body: String): JSONObject {
        val out = JSONObject().apply {
            put("totalMinutes", 0L)
            put("rawAccumulated", true)
            put("dailyTotals", JSONArray())
            put("categories", JSONArray())
        }
        try {
            when (periodDays) {
                1 -> {
                    val d = JSONObject(body)
                    out.put("totalMinutes", d.optLong("totalMinutes", 0))
                    out.put("limitMinutes", d.optLong("limitMinutes", 0))
                    out.put("rawAccumulated", d.optBoolean("rawAccumulated", true))
                    val totals = JSONArray().put(JSONObject().apply {
                        put("date", d.optString("date", ""))
                        put("totalMinutes", d.optLong("totalMinutes", 0))
                    })
                    out.put("dailyTotals", totals)
                    out.put("categories", d.optJSONArray("categories") ?: JSONArray())
                }
                7 -> {
                    val w = JSONObject(body)
                    out.put("totalMinutes", w.optLong("totalMinutes", 0))
                    out.put("limitMinutes", w.optLong("limitMinutes", 0))
                    val details = w.optJSONArray("dailyDetails")
                    val totals = JSONArray()
                    if (details != null) {
                        for (i in 0 until details.length()) {
                            val dd = details.getJSONObject(i)
                            totals.put(JSONObject().apply {
                                put("date", dd.optString("date", ""))
                                put("totalMinutes", dd.optLong("totalMinutes", 0))
                            })
                        }
                    }
                    out.put("dailyTotals", totals)
                    out.put("categories", w.optJSONArray("categories") ?: JSONArray())
                }
                else -> {
                    // export?format=json：逐日聚合数组 → 合并为周期总览
                    val days = JSONArray(body)
                    var total = 0L
                    val totals = JSONArray()
                    val catMap = linkedMapOf<String, JSONObject>()
                    for (i in 0 until days.length()) {
                        val day = days.getJSONObject(i)
                        val dayTotal = day.optLong("totalMinutes", 0)
                        total += dayTotal
                        totals.put(JSONObject().apply {
                            put("date", day.optString("date", ""))
                            put("totalMinutes", dayTotal)
                        })
                        val cats = day.optJSONArray("categories") ?: continue
                        for (j in 0 until cats.length()) {
                            val c = cats.getJSONObject(j)
                            val key = normalizeCategoryKey(c.optString("key", ""))
                            val acc = catMap.getOrPut(key) {
                                JSONObject().apply {
                                    put("key", key)
                                    put("name", c.optString("name", key))
                                    put("minutes", 0L)
                                    put("percent", 0.0)
                                }
                            }
                            acc.put("minutes", acc.optLong("minutes", 0) + c.optLong("minutes", 0))
                        }
                    }
                    val cats = JSONArray()
                    catMap.values.forEach { c ->
                        val pct = if (total > 0)
                            Math.round(c.optLong("minutes", 0) * 1000.0 / total) / 10.0 else 0.0
                        c.put("percent", pct)
                        cats.put(c)
                    }
                    out.put("totalMinutes", total)
                    out.put("dailyTotals", totals)
                    out.put("categories", cats)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "报告归一化失败: ${e.message}")
        }
        return out
    }

    // ==================== 守护事件（V1.1.1 Bug1-D/1-B） ====================

    /**
     * POST /api/guard-events：转传儿童端守护失守事件（含健康度快照）到云端。
     * 服务端按账号校验设备归属（家长仅自己账号设备）。
     */
    suspend fun uploadGuardEvent(context: Context, deviceId: String, eventJson: String): Result<JSONObject> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("events", JSONArray().apply { put(JSONObject(eventJson)) })
            }
            val (code, resp, err) = httpPostJson(host, port, "/api/guard-events", body.toString(), tk)
            when {
                code in 200..299 -> {
                    AppLog.i(TAG, "守护事件已转传云端: device=$deviceId")
                    Result.Ok(JSONObject(resp))
                }
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "转传守护事件失败: HTTP $code"))
            }
        }
    }

    /**
     * GET /api/guard-events：拉取守护事件历史（服务端按账号隔离，parent 仅自己账号）。
     */
    suspend fun fetchGuardEvents(context: Context, deviceId: String? = null, limit: Int = 50): Result<JSONObject> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val query = buildString {
                append("/api/guard-events?limit=${limit.coerceIn(1, 100)}")
                if (!deviceId.isNullOrBlank()) append("&deviceId=$deviceId")
            }
            val (code, body, err) = httpGetJson(host, port, query, tk)
            when {
                code in 200..299 -> Result.Ok(JSONObject(body))
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "获取守护事件失败: HTTP $code"))
            }
        }
    }

    /**
     * GET /api/guard-events/health：拉取某设备最新健康度快照。
     */
    suspend fun fetchGuardHealth(context: Context, deviceId: String): Result<JSONObject> {
        val (host, port) = hostPort(context) ?: return Result.Err(false, "尚未配置服务器地址")
        val tk = token(context) ?: return Result.Err(false, "登录已过期，请重新登录")
        return ioCall(context) {
            val (code, body, err) = httpGetJson(host, port, "/api/guard-events/health?deviceId=$deviceId", tk)
            when {
                code in 200..299 -> Result.Ok(JSONObject(body))
                code == 401 -> Result.Err(false, "登录已过期，请重新登录")
                else -> Result.Err(false, parseError(err, "获取健康度失败: HTTP $code"))
            }
        }
    }
}
