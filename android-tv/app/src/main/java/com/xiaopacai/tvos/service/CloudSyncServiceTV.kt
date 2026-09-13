// [android-tv] 服务 — CloudSyncServiceTV（云端同步）
// 小趴菜 TVOS 版 — 对接后端真实路由 /api/v1/device/*
//
// ⚠️ 历史坑：旧实现用的是 /api/v3/heartbeat、/api/v3/bind，后端**并不存在**这些路由，
//    等于一直"对着空气打"。本版已按 DeviceApiController 的真实契约重写：
//      1. POST /api/v1/device/register      → { token }（设备令牌，匿名可注册）
//      2. POST /api/v1/device/heartbeat     → { success, commands, policyVersion, announcementSignature }（Bearer 设备令牌）
//      3. GET  /api/v1/device/policies      → { policies:[...] }（Bearer）
//      4. POST /api/v1/device/usage-report  → { success }（Bearer）
//    心跳每 60s 一次；仅当 policyVersion 变化时才拉取策略（与手机版一致）。

package com.xiaopacai.tvos.service

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.xiaopacai.tvos.ui.announcement.AnnouncementStore
import com.xiaopacai.tvos.util.AppLogTV
import com.xiaopacai.tvos.util.EmergencyReleaseServiceTV
import com.xiaopacai.tvos.util.HeartbeatStatus
import com.xiaopacai.tvos.util.NetworkHelper
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import com.xiaopacai.tvos.util.UsageStatsHelperTV
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 云端同步服务 — 60s 心跳 + 策略同步 + 使用上报
 */
class CloudSyncServiceTV : Service() {

    companion object {
        private const val TAG = "CloudSyncServiceTV"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        /** 每 N 次心跳上报一次使用数据（后端限速 60 次/小时） */
        private const val USAGE_REPORT_EVERY = 5
        /** 【项8】诊断+日志上报频率：心跳 60s × 30 = 约 30 分钟一次 */
        private const val DIAGNOSTICS_EVERY = 30
        private const val API_DIAGNOSTICS = "/api/v1/device/diagnostics-report"

        private const val API_REGISTER = "/api/v1/device/register"
        private const val API_HEARTBEAT = "/api/v1/device/heartbeat"
        private const val API_POLICIES = "/api/v1/device/policies"
        private const val API_USAGE_REPORT = "/api/v1/device/usage-report"

        /**
         * [FIX-2026-09-13-TOKEN-RENEW] 设备令牌有效期 24h，本地令牌剩余寿命低于此值即提前续签。
         *
         * 历史事故（2026-09-13 真机）：设备令牌过期后，ensureToken 见本地有令牌就直接返回、
         * 永不重新注册 → 心跳/公告/更新检查全部 401，界面「○ 未连接服务器 · 最后心跳 <过期时刻>」，
         * 且家长重新绑定也救不回（绑定当时不下发令牌），只能到现场重装应用。
         * 现在三重保险：① 到期前 12h 主动续签；② 任何鉴权接口 401 → 强制续签并重试；
         * ③ 服务端 /register 对过期令牌也放行续签（只验签名/主体/权限）。
         */
        private const val RENEW_BEFORE_MS = 12 * 60 * 60 * 1000L

        /** 解析 JWT 的 exp（毫秒时间戳）；解析失败返回 0（视作「无法判断」，不强行续签） */
        private fun tokenExpiryMillis(token: String): Long {
            return runCatching {
                val payload = token.split(".").getOrNull(1) ?: return 0L
                val json = String(
                    android.util.Base64.decode(
                        payload,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                    ),
                    Charsets.UTF_8
                )
                JSONObject(json).optLong("exp", 0L) * 1000L
            }.getOrDefault(0L)
        }

        /** 【A1】设备侧公告通道（家长在 web/手机发布 → 电视拉取 + 回执） */
        private const val API_ANNOUNCEMENTS = "/api/v1/device/announcements"
        private const val API_ANNOUNCEMENT_ACK = "/api/v1/device/announcement-ack"
        private const val PLATFORM = "android-tv"

        /** 【A12】解析云端下发的应用分类映射（容错：非对象结构时返回空） */
        fun parseCategoryMapStatic(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(raw)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { k -> map[k] = obj.optString(k, "other") }
                map
            }.getOrDefault(emptyMap())
        }

        /**
         * 【A12】立刻上报一次使用明细（供「使用明细」页手动触发，不依赖 Service 实例）。
         * 契约与周期上报完全一致：POST /api/v1/device/usage-report
         * @return true 表示服务端已接收
         */
        suspend fun reportUsageNow(context: Context): Boolean {
            val app = context.applicationContext
            val deviceId = SharedPreferenceHelperTV.getDeviceId(app).first()
            val token = SharedPreferenceHelperTV.getDeviceToken(app).first()
            if (deviceId.isBlank() || token.isBlank()) {
                AppLogTV.w(TAG, "使用明细上报跳过：设备未注册或无令牌")
                return false
            }
            val categoryMap = parseCategoryMapStatic(SharedPreferenceHelperTV.getAppCategories(app).first())
            val records = JSONArray()
            UsageStatsHelperTV.queryTodayUsage(app)
                .filterKeys { it != app.packageName }   // 本应用自身不计入使用报告
                .forEach { (pkg, minutes) ->
                val label = runCatching {
                    app.packageManager.getApplicationLabel(
                        app.packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                }.getOrDefault(pkg)
                records.put(JSONObject().apply {
                    put("AppPackage", pkg)
                    put("AppName", label)
                    put("Category", categoryMap[pkg] ?: "other")
                    put("DurationSeconds", minutes * 60)
                    put("IsBlocked", false)
                })
            }
            if (records.length() == 0) {
                AppLogTV.w(TAG, "使用明细上报跳过：今日无应用用量")
                return false
            }
            val payload = JSONObject().apply {
                put("DeviceId", deviceId)
                put("Date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
                // [FIX-2026-09-12] 与手机端 P2P 上报对齐：带上今日重置基线，
                // 否则服务端只能算出「原始累计」，家长端「今日已用」会比电视卡片看到的大
                put("DailyResetOffsetMinutes", UsageStatsHelperTV.resetOffsetMinutes(app))
                put("DailyResetDate", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
                // [FIX-2026-09-12 · 用户口径] 电视自己的「今日已用」（任何界面都算时间、已扣重置基线）
                // → 家长端显示的就是孩子看到的那个数
                put("AdjustedTodayMinutes", UsageStatsHelperTV.reportedAdjustedMinutes(app))
                put("Records", records)
            }.toString()
            val host = SharedPreferenceHelperTV.getCloudHost(app).first()
            val port = SharedPreferenceHelperTV.getCloudPort(app).first()
            val url = NetworkHelper.buildBaseUrl(host, port) + API_USAGE_REPORT
            val (code, _) = suspendCancellableCoroutine<Pair<Int, String>> { cont ->
                NetworkHelper.request("POST", url, payload, token) { c, b ->
                    if (cont.isActive) cont.resume(c to b)
                }
            }
            if (code in 200..299) {
                AppLogTV.i(TAG, "使用明细已上报（手动触发，${records.length()} 条）")
                return true
            }
            AppLogTV.w(TAG, "使用明细上报失败 HTTP $code")
            return false
        }

        /**
         * 【A1】公告已读回执（设备令牌通道）。非 suspend —— 弹窗 Activity 关闭时直接调用即可。
         */
        fun reportAnnouncementAck(context: Context, announcementId: String) {
            if (announcementId.isBlank()) return
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val token = SharedPreferenceHelperTV.getDeviceToken(app).first()
                    if (token.isBlank()) {
                        AppLogTV.w(TAG, "无设备令牌，公告回执跳过 id=$announcementId")
                        return@runCatching
                    }
                    val annIdInt = announcementId.toIntOrNull()
                    if (annIdInt == null) {
                        AppLogTV.w(TAG, "公告回执跳过：id 不是数字（$announcementId）")
                        return@runCatching
                    }
                    val deviceId = SharedPreferenceHelperTV.getDeviceId(app).first()
                    val host = SharedPreferenceHelperTV.getCloudHost(app).first()
                    val port = SharedPreferenceHelperTV.getCloudPort(app).first()
                    val url = NetworkHelper.buildBaseUrl(host, port) + API_ANNOUNCEMENT_ACK
                    // 服务端 AnnouncementAck 强制校验：body.deviceId 必须等于令牌设备；announcementId 必须可解析为 int
                    val body = JSONObject().apply {
                        put("deviceId", deviceId)
                        put("announcementId", annIdInt)
                        put("acknowledgedAt", System.currentTimeMillis() / 1000)
                    }.toString()
                    NetworkHelper.request("POST", url, body, token) { code, resp ->
                        if (code in 200..299) {
                            AppLogTV.i(TAG, "公告回执成功 id=$announcementId")
                        } else {
                            AppLogTV.w(TAG, "公告回执失败 id=$announcementId HTTP $code ${resp.take(80)}")
                        }
                    }
                }.onFailure { AppLogTV.w(TAG, "公告回执异常: ${it.message}") }
            }
        }

        /**
         * [首页内嵌公告框] 拉取「已发布」公告写入 [AnnouncementStore]（供首页直接展示）。
         *
         * 服务端 /api/v1/device/announcements 已按 status=published + 目标设备/全部 + 有效期内 过滤，
         * 撤回（revoked）与删除（DELETE）的公告不会出现在返回里 —— 正是首页要的展示口径。
         */
        suspend fun refreshAnnouncementsIntoStore(context: Context) {
            val app = context.applicationContext
            runCatching {
                val token = SharedPreferenceHelperTV.getDeviceToken(app).first()
                if (token.isBlank()) return@runCatching
                val host = SharedPreferenceHelperTV.getCloudHost(app).first()
                val port = SharedPreferenceHelperTV.getCloudPort(app).first()
                val url = NetworkHelper.buildBaseUrl(host, port) + API_ANNOUNCEMENTS
                NetworkHelper.request("GET", url, null, token) { code, resp ->
                    if (code in 200..299) {
                        runCatching {
                            AnnouncementStore.replaceFromServer(
                                JSONObject(resp).optJSONArray("announcements") ?: JSONArray()
                            )
                        }
                    } else {
                        AppLogTV.w(TAG, "首页公告拉取失败 HTTP $code")
                    }
                }
            }.onFailure { AppLogTV.w(TAG, "首页公告拉取异常: ${it.message}") }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var isRunning = false
    private var lastPolicyVersion = -1
    /** 【A1】上次已处理的公告签名（变化才拉取；服务重启后为 "" → 会拉一次，已回执的会被过滤） */
    private var lastAnnouncementSignature = ""
    private var heartbeatCount = 0

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startHeartbeat()
    }

    override fun onDestroy() {
        isRunning = false
        heartbeatJob?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 心跳循环 ====================

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (isRunning && isActive) {
                try {
                    if (performHeartbeat()) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                    }
                    heartbeatCount++
                    if (heartbeatCount % USAGE_REPORT_EVERY == 0) {
                        reportUsage()
                    }
                    // 【项8】脱敏诊断 + 运行日志自动上传：启动 2 分钟后先来一次，之后每 30 分钟一次
                    if (heartbeatCount == 2 || heartbeatCount % DIAGNOSTICS_EVERY == 0) {
                        reportDiagnostics()
                    }
                    // 【项2】跟踪无障碍守护状态：应用每次「覆盖安装」后系统都会自动解除无障碍绑定，
                    // 拦截层会静默失效 —— 这里记住「曾经开过」，供主界面提示家长重新开启。
                    trackAccessibilityGuard()
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.e(TAG, "心跳异常（连续 $consecutiveFailures 次）", e)
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun performHeartbeat(): Boolean {
        val deviceId = ensureDeviceId()
        val token = ensureToken(deviceId)
        if (token.isBlank()) {
            Log.w(TAG, "尚无设备令牌，跳过本次心跳")
            return false
        }

        val payload = JSONObject().apply {
            put("DeviceId", deviceId)
            put("Timestamp", System.currentTimeMillis())
            put("EmergencyActive", EmergencyReleaseServiceTV.isActive(this@CloudSyncServiceTV))
        }.toString()

        HeartbeatStatus.markAttempt()

        var (code, body) = request("POST", baseUrl() + API_HEARTBEAT, payload, token)
        if (code == 401) {
            // [FIX-2026-09-13-TOKEN-RENEW] 令牌失效（过期/被轮换）→ 强制续签一次再重试，
            // 避免「本地有令牌就永不续签」导致的永久 401 死状态（真机曾因此整夜失联）。
            val fresh = renewToken(deviceId, token)
            if (fresh.isNotBlank()) {
                val retry = request("POST", baseUrl() + API_HEARTBEAT, payload, fresh)
                code = retry.first
                body = retry.second
                if (code in 200..299) AppLogTV.i("CloudSync", "令牌续签后心跳已恢复")
            }
        }
        if (code !in 200..299) {
            Log.w(TAG, "心跳失败 HTTP $code")
            AppLogTV.w("CloudSync", "心跳失败 HTTP $code")
            HeartbeatStatus.markFailure("HTTP $code")
            // [对标手机版 V2.0.6-UNBIND-SYNC] 服务端已解绑（设备行被删）→ 清除本地绑定标记，
            // 主界面随即回到「未绑定」，提示家长重新绑定
            if (code == 404) {
                SharedPreferenceHelperTV.saveDeviceBound(this, false)
            }
            return false
        }

        SharedPreferenceHelperTV.saveLastSyncTime(this, System.currentTimeMillis())
        HeartbeatStatus.markSuccess()

        runCatching {
            val obj = JSONObject(body)

            // 下行指令
            obj.optJSONArray("commands")?.let { commands ->
                for (i in 0 until commands.length()) {
                    when (commands.optJSONObject(i)?.optString("type")) {
                        "reset_daily_usage" -> {
                            // [FIX-2026-09-12] 与 P2P limit_reset 同一条路：记重置基线 + 真正解锁
                            TimeoutExecutorTV.applyRemoteReset(this@CloudSyncServiceTV)
                            Log.i(TAG, "云端指令：重置当日用量（重置后重新计时并解除锁屏）")
                        }
                        "wait_bind" -> Log.i(TAG, "云端指令：等待家长绑定")
                    }
                }
            }

            // [TASK-GUARD-TOGGLE-V1] 管控启停兜底同步（P2P 推送丢失 / 离线期间家长切换，都在这里纠正）
            if (obj.has("guardEnabled")) {
                TimeoutExecutorTV.setGuardEnabled(this@CloudSyncServiceTV, obj.optBoolean("guardEnabled", true))
            }

            // 策略版本变化时才拉取全量策略
            val version = obj.optInt("policyVersion", 0)
            if (version != lastPolicyVersion) {
                applyPolicies(fetchPolicies(token))
                lastPolicyVersion = version
            }

            // 【A1】公告下发：签名变化才拉取（对标手机版 announcementSignature 比对）
            val annSig = obj.optString("announcementSignature", "")
            if (annSig != lastAnnouncementSignature) {
                if (syncAnnouncements(token)) lastAnnouncementSignature = annSig
            }
        }.onFailure { Log.w(TAG, "心跳响应解析失败", it) }

        return true
    }

    private suspend fun fetchPolicies(token: String): JSONArray? {
        val (code, body) = request("GET", baseUrl() + API_POLICIES, null, token)
        if (code !in 200..299) {
            Log.w(TAG, "拉取策略失败 HTTP $code")
            return null
        }
        return runCatching { JSONObject(body).optJSONArray("policies") }.getOrNull()
    }

    /** 应用云端策略（当前 TV 端生效项：每日限额） */
    private suspend fun applyPolicies(policies: JSONArray?) {
        if (policies == null || policies.length() == 0) return
        val policy = policies.optJSONObject(0) ?: return

        val limit = policy.optInt("dailyLimitMinutes", -1)
        if (limit >= 0) {
            SharedPreferenceHelperTV.saveDailyLimit(this, limit)
            TimeoutExecutorTV.setDailyLimit(limit, this)
            Log.i(TAG, "云端策略生效：每日限额 $limit 分钟")
        }

        // 【A3】就寝时段（HH:mm；跨零点如 21:00–07:00 也支持）
        // 注意：后端未设置时返回 JSON null，org.json 的 optString 会给出字符串 "null"，必须归一成空
        val bedtimeStart = policy.optString("bedtimeStart", "").let { if (it == "null") "" else it }
        val bedtimeEnd = policy.optString("bedtimeEnd", "").let { if (it == "null") "" else it }
        SharedPreferenceHelperTV.saveBedtimeStart(this, bedtimeStart)
        SharedPreferenceHelperTV.saveBedtimeEnd(this, bedtimeEnd)
        TimeoutExecutorTV.setBedtime(bedtimeStart.ifBlank { null }, bedtimeEnd.ifBlank { null })
        if (bedtimeStart.isNotBlank() && bedtimeEnd.isNotBlank()) {
            AppLogTV.i(TAG, "云端策略生效：就寝时段 $bedtimeStart–$bedtimeEnd")
        } else {
            AppLogTV.i(TAG, "云端策略：未设置就寝时段")
        }

        // 【A12】应用分类映射（上报使用记录时用；服务端 /policies 随策略下发）
        val categories = policy.opt("appCategories")?.toString() ?: ""
        if (categories.isNotBlank() && categories != "null") {
            SharedPreferenceHelperTV.saveAppCategories(this, categories)
        }
    }



    /**
     * 【项2】无障碍守护状态跟踪（每分钟随心跳检查一次）
     * 背景：Android 在应用被覆盖安装/更新后会解除无障碍服务绑定 —— 更新完就「静默失效」，
     * 家长毫无感知，孩子于是又能换台逃逸。这里保证这种情况一定会被记录并提醒。
     */
    private suspend fun trackAccessibilityGuard() {
        val on = isAccessibilityEnabled()
        val everOn = SharedPreferenceHelperTV.getAccessibilityEverOn(this).first()
        when {
            on && !everOn -> {
                SharedPreferenceHelperTV.saveAccessibilityEverOn(this, true)
                AppLogTV.i(TAG, "无障碍守护已开启（拦截换台逃逸生效）")
            }
            !on && everOn -> {
                // 已开启过但现在关了 → 提醒家长重新开启（主界面会显示警示行）
                AppLogTV.w(TAG, "无障碍拦截已失效（应用更新后系统会解除绑定）→ 需家长重新开启")
            }
        }
    }

    /**
     * 【项8】脱敏诊断 + 运行日志自动上传（对标手机版 DiagnosticsService / LogUploader）
     *
     * 通道：POST /api/v1/device/diagnostics-report —— 设备令牌直传，**不需要家长 JWT**，
     *       所以可以在后台周期执行（家长不在电视旁也能拿到运行数据）。
     * 内容：版本/机型、权限状态、守护服务状态、最近心跳、最近崩溃、最近 30 条运行日志。
     * 脱敏：日志在写入缓冲时已由 AppLogTV 打码（邮箱/手机号/令牌/JWT/密码字段/本地路径），
     *       服务端另有一次二次脱敏。
     */
    private suspend fun reportDiagnostics() {
        val deviceId = ensureDeviceId()
        val token = ensureToken(deviceId)
        if (token.isBlank()) {
            Log.w(TAG, "尚无设备令牌，跳过诊断上报")
            return
        }

        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")

        val permissionStatus = JSONObject().apply {
            put("usageStats", UsageStatsHelperTV.isUsageStatsPermissionGranted(this@CloudSyncServiceTV))
            put("overlay", hasOverlayPermission())
            put("accessibility", isAccessibilityEnabled())
            put("deviceAdmin", GuardianDeviceAdminReceiver.isActive(this@CloudSyncServiceTV))
            put("deviceOwner", GuardianDeviceAdminReceiver.isDeviceOwner(this@CloudSyncServiceTV))
        }

        val recentLogs = JSONArray()
        AppLogTV.snapshot().takeLast(30).forEach { recentLogs.put(it.render()) }

        val serviceStatus = JSONObject().apply {
            put("guardService", isServiceRunning(TVGuardianForegroundService::class.java.name))
            put("cloudSync", isServiceRunning(CloudSyncServiceTV::class.java.name))
            put("locked", TimeoutExecutorTV.isLocked)
            put("emergencyActive", EmergencyReleaseServiceTV.isActive(this@CloudSyncServiceTV))
            put("online", HeartbeatStatus.online.value)
            put("lastHeartbeatAt", HeartbeatStatus.lastSuccessAt.value)
            put("logLines", AppLogTV.snapshot().size)
            put("recentLogs", recentLogs)
        }

        val crashes = JSONArray()
        AppLogTV.recentErrors(5).forEach { crashes.put(it) }

        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("appVersion", appVersion)
            put("osVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            put("deviceModel", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("permissionStatus", permissionStatus)
            put("serviceStatus", serviceStatus)
            put("recentCrashes", crashes)
            put("networkType", networkType())
        }.toString()

        val (code, _) = request("POST", baseUrl() + API_DIAGNOSTICS, payload, token)
        if (code in 200..299) {
            Log.i(TAG, "诊断+运行日志已上传（脱敏）：缓冲 ${AppLogTV.snapshot().size} 条")
        } else {
            Log.w(TAG, "诊断上报失败 HTTP $code")
        }
    }

    /** API 22 无悬浮窗权限概念（安装即授予），API 23+ 才需要专门检查 */
    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    /** 无障碍守护是否已被家长开启 */
    private fun isAccessibilityEnabled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.split(':').any { it.startsWith("$packageName/") }
    }.getOrDefault(false)

    /** 某个服务当前是否在运行（诊断上报用，API 22 可用 getRunningServices） */
    private fun isServiceRunning(className: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getRunningServices(Int.MAX_VALUE).any { it.service.className == className }
    }.getOrDefault(false)

    /** 网络类型：wifi | cellular | none */
    private fun networkType(): String = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return@runCatching "none"
        when (info.type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_MOBILE -> "cellular"
            else -> "other"
        }
    }.getOrDefault("none")

    private suspend fun reportUsage() {
        val deviceId = ensureDeviceId()
        val token = SharedPreferenceHelperTV.getDeviceToken(this).first()
        if (token.isBlank()) return

        // 【A12】按云端下发的分类映射给每条记录归类别（game/social/video/learning/other）
        val categoryMap = parseCategoryMapStatic(SharedPreferenceHelperTV.getAppCategories(this).first())
        val records = JSONArray()
        UsageStatsHelperTV.queryTodayUsage(this)
            .filterKeys { it != packageName }   // 本应用自身不计入使用报告
            .forEach { (pkg, minutes) ->
            records.put(JSONObject().apply {
                put("AppPackage", pkg)
                put("AppName", resolveAppLabel(pkg))
                put("Category", categoryMap[pkg] ?: "other")
                put("DurationSeconds", minutes * 60)
                put("IsBlocked", false)
            })
        }
        if (records.length() == 0) return

        val payload = JSONObject().apply {
            put("DeviceId", deviceId)
            put("Date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
            // [FIX-2026-09-12] 同 reportUsageNow：带上重置基线，保证家长端口径 = 电视卡片口径
            put("DailyResetOffsetMinutes", UsageStatsHelperTV.resetOffsetMinutes(applicationContext))
            put("DailyResetDate", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
            put("AdjustedTodayMinutes", UsageStatsHelperTV.reportedAdjustedMinutes(applicationContext))
            put("Records", records)
        }.toString()

        val (code, _) = request("POST", baseUrl() + API_USAGE_REPORT, payload, token)
        if (code in 200..299) {
            Log.i(TAG, "使用上报成功（${records.length()} 条）")
        } else {
            Log.w(TAG, "使用上报失败 HTTP $code")
        }
    }

    // ==================== 设备注册 / 令牌 ====================

    private suspend fun ensureDeviceId(): String {
        var id = SharedPreferenceHelperTV.getDeviceId(this).first()
        if (id.isBlank()) {
            SharedPreferenceHelperTV.generateDeviceId(this)
            id = SharedPreferenceHelperTV.getDeviceId(this).first()
        }
        return id
    }

    private suspend fun ensureToken(deviceId: String): String {
        val existing = SharedPreferenceHelperTV.getDeviceToken(this).first()
        if (existing.isNotBlank()) {
            // [FIX-2026-09-13-TOKEN-RENEW] 到期前主动续签；续签失败仍用旧令牌撑到 401 兜底那一跳
            if (!needsRenew(existing)) return existing
            val renewed = renewToken(deviceId, existing)
            return renewed.ifBlank { existing }
        }
        return registerFresh(deviceId)
    }

    /** 令牌剩余寿命不足阈值（含已过期）→ 需要续签 */
    private fun needsRenew(token: String): Boolean {
        val exp = tokenExpiryMillis(token)
        if (exp <= 0L) return false
        return exp - System.currentTimeMillis() < RENEW_BEFORE_MS
    }

    /** 匿名注册（首次装机 / 服务端已删行时使用） */
    private suspend fun registerFresh(deviceId: String): String {
        val payload = JSONObject().apply {
            put("DeviceId", deviceId)
            put("Platform", PLATFORM)
        }.toString()

        val (code, body) = request("POST", baseUrl() + API_REGISTER, payload, null)
        if (code !in 200..299) {
            Log.w(TAG, "设备注册失败 HTTP $code: $body")
            return ""
        }

        val token = runCatching { JSONObject(body).optString("token", "") }.getOrDefault("")
        if (token.isNotBlank()) {
            SharedPreferenceHelperTV.saveDeviceToken(this, token)
            Log.i(TAG, "设备注册成功，令牌已保存 deviceId=$deviceId")
        }
        return token
    }

    /**
     * 带既有令牌续签 —— 服务端对「已过期」的令牌同样放行（只验签名/主体/权限），
     * 这样电视关机/断网超过 24h 也能自愈。返回新令牌并落盘；失败返回空串。
     * 服务端明确回 409/404（无此设备行）时退回匿名注册，避免卡死。
     */
    private suspend fun renewToken(deviceId: String, existingToken: String): String {
        if (existingToken.isBlank()) return ""
        val payload = JSONObject().apply {
            put("DeviceId", deviceId)
            put("Platform", PLATFORM)
            put("ExistingToken", existingToken)
        }.toString()

        val (code, body) = request("POST", baseUrl() + API_REGISTER, payload, null)
        if (code == 409 || code == 404) {
            Log.w(TAG, "续签被拒（HTTP $code：服务端无此设备行）→ 退回匿名注册")
            return registerFresh(deviceId)
        }
        if (code !in 200..299) {
            Log.w(TAG, "令牌续签失败 HTTP $code")
            AppLogTV.w("CloudSync", "令牌续签失败 HTTP $code")
            return ""
        }
        val token = runCatching { JSONObject(body).optString("token", "") }.getOrDefault("")
        if (token.isNotBlank()) {
            SharedPreferenceHelperTV.saveDeviceToken(this, token)
            val remainMin = (tokenExpiryMillis(token) - System.currentTimeMillis()) / 60000
            Log.i(TAG, "设备令牌已续签，新令牌剩余 $remainMin 分钟")
            AppLogTV.i("CloudSync", "设备令牌已续签（剩余 $remainMin 分钟）")
        }
        return token
    }

    // ==================== 基础设施 ====================

    /**
     * 【A1】拉取云端已发布公告并弹出展示。
     * 只弹「未回执」的（后端返回 acknowledgedAt，已回执过的不再打扰）。
     * @return true 表示本次拉取成功（据此决定是否记下签名，失败则下次心跳重试）
     */
    private suspend fun syncAnnouncements(token: String): Boolean {
        val (code, body) = request("GET", baseUrl() + API_ANNOUNCEMENTS, null, token)
        if (code !in 200..299) {
            AppLogTV.w(TAG, "拉取公告失败 HTTP $code")
            return false
        }
        val arr = runCatching { JSONObject(body).optJSONArray("announcements") }.getOrNull() ?: return true
        // [首页内嵌公告框] 服务端已按 status=published + 目标设备 + 有效期内 过滤：
        // 这里整体同步给共享状态，首页公告框据此展示（撤回/删除的不会出现在列表里）。
        runCatching { AnnouncementStore.replaceFromServer(arr) }
        val pending = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.isNull("acknowledgedAt")) continue
            pending.put(o)
        }
        if (pending.length() == 0) return true

        AppLogTV.i(TAG, "收到云端公告 ${pending.length()} 条，弹出展示")
        runCatching {
            startActivity(
                Intent(this, com.xiaopacai.tvos.ui.announcement.AnnouncementOverlayActivity::class.java)
                    .putExtra(
                        com.xiaopacai.tvos.ui.announcement.AnnouncementOverlayActivity.EXTRA_JSON,
                        pending.toString()
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { AppLogTV.w(TAG, "弹出公告失败: ${it.message}") }
        return true
    }

    /** 【A12】取应用显示名（取不到就退回包名） */
    private fun resolveAppLabel(pkg: String): String = runCatching {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    }.getOrDefault(pkg)

    private suspend fun baseUrl(): String {
        val host = SharedPreferenceHelperTV.getCloudHost(this).first()
        val port = SharedPreferenceHelperTV.getCloudPort(this).first()
        return NetworkHelper.buildBaseUrl(host, port)
    }

    private suspend fun request(
        method: String,
        url: String,
        json: String?,
        token: String?
    ): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        NetworkHelper.request(method, url, json, token) { code, body ->
            if (cont.isActive) cont.resume(code to body)
        }
    }

    /** 主动触发一次心跳（设置变更时调用） */
    fun triggerHeartbeat() {
        scope.launch { performHeartbeat() }
    }
}
