package com.xiaopacai.child.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.data.database.UsageRecordDao
import com.xiaopacai.child.data.database.UsageRecordEntry
import com.xiaopacai.child.util.DbPassphraseProvider
import com.xiaopacai.child.util.UsageStatsHelper
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * [TASK-D2-01] 应用使用时长采集器
 *
 * 核心职责：
 * 1. 定时（每 5 分钟）从 UsageStatsManager 拉取使用数据
 * 2. 将数据写入本地加密数据库
 * 3. 更新每日汇总（总时长、分类时长、超限状态）
 * 4. 提供实时数据供 UI 展示和服务判断
 *
 * 分类逻辑（基于包名关键词匹配）：
 * - game: 游戏类（含 game, 游戏, 王者, 原神, 和平, minecraft, roblox 等）
 * - social: 社交类（含 wechat, qq, 微信, 微博, douyin, tiktok, 快手 等）
 * - video: 视频类（含 video, 视频, bilibili, youtube, iqiyi, 腾讯视频 等）
 * - study: 学习类（含 edu, study, 学习, 词典, 作业, 笔记, 网课 等）
 * - other: 其他
 */
class UsageStatsCollector(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "UsageStatsCollector"
        /** [FIX] 采集间隔：1 分钟（此前 5 分钟导致超时后最多延迟 5 分钟才锁定） */
        private const val COLLECT_INTERVAL_MS = 60 * 1000L
        /** 初始延迟：30 秒（给系统启动留足时间） */
        private const val INITIAL_DELAY_MS = 30 * 1000L

        // [TASK-PRELAUNCH-P4] 限额重置偏移的 SharedPreferences 键（与 SyncManager 共用）
        const val PREFS_NAME = "guardian_prefs"
        const val KEY_RESET_OFFSET_MINUTES = "daily_reset_offset_minutes"
        const val KEY_RESET_OFFSET_DATE = "daily_reset_offset_date"

        /**
         * [TASK-PRELAUNCH-P4] 供 SyncManager 调用的限额重置入口（静态，解耦服务实例引用）：
         * 持久化偏移后立即重算“已用/超时”口径（超时锁定同步解除）
         */
        @JvmStatic
        fun applyLimitReset(offsetMinutes: Long, offsetDate: String) {
            try {
                val app = XiaopacaiApp.instance
                val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong(KEY_RESET_OFFSET_MINUTES, offsetMinutes)
                    .putString(KEY_RESET_OFFSET_DATE, offsetDate)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "持久化限额重置偏移失败: ${e.message}")
            }
            // 立即按新口径重算（采集循环 5 分钟内也会自然重算）
            instance?.scope?.launch {
                try { instance?.collectAndPersist() } catch (e: Exception) {
                    Log.e(TAG, "限额重置后重算失败: ${e.message}")
                }
            }
        }

        /** 当前实例（服务持有；applyLimitReset 通过它触发重算） */
        @Volatile
        private var instance: UsageStatsCollector? = null

        /**
         * [FIX-100] 供 SyncManager 读取调整后今日已用（分钟）；实例未启动时为 null
         */
        @JvmStatic
        fun todayAdjustedMinutesOrNull(): Long? = instance?.todayAdjustedMinutes

        // === 应用分类规则（包名关键词 → 分类） ===
        private val CATEGORY_RULES = listOf(
            // 游戏类关键词
            "game" to "game",
            "游戏" to "game",
            "puzzle" to "game",
            "minecraft" to "game",
            "roblox" to "game",
            "brawl" to "game",
            "clash" to "game",
            "genshin" to "game",
            // 社交类关键词
            "wechat" to "social",
            "tencent.mm" to "social",
            "tencent.mobileqq" to "social",
            "微博" to "social",
            "sina.weibo" to "social",
            "douyin" to "social",
            "tiktok" to "social",
            "快手" to "social",
            "twitter" to "social",
            "facebook" to "social",
            "instagram" to "social",
            "snapchat" to "social",
            "telegram" to "social",
            "whatsapp" to "social",
            // 视频类关键词
            "video" to "video",
            "视频" to "video",
            "bilibili" to "video",
            "youtube" to "video",
            "iqiyi" to "video",
            "youku" to "video",
            "tv.danmaku" to "video",
            "netflix" to "video",
            "twitch" to "video",
            // 学习类关键词
            "edu" to "study",
            "学习" to "study",
            "study" to "study",
            "词典" to "study",
            "dictionary" to "study",
            "作业" to "study",
            "笔记" to "study",
            "note" to "study",
            "网课" to "study",
            "course" to "study",
            "课堂" to "study",
            "翻译" to "study",
            "translate" to "study",
            "calculator" to "study",
            "wikipedia" to "study",
            "duolingo" to "study"
        )

        /** 供其他模块复用的分类规则（AppCategoryHelper 等） */
        fun getCategoryRules(): List<Pair<String, String>> = CATEGORY_RULES

        // [TASK-HARDENING-V1.1.1] Bug2-A：采集失效判定阈值（超过 3 个采集周期未成功 → 守护失效）
        const val COLLECT_STALE_THRESHOLD_MS = 3 * COLLECT_INTERVAL_MS

        /**
         * [TASK-HARDENING-V1.1.1] Bug2-A 纯函数（单测）：
         * 剩余 = 今日限额 −（最近一次采集已用 + 距最近采集的增量）。
         * 增量仅在屏幕交互（isInteractive）时累计——熄屏不计使用时长，
         * 与 UsageStats 口径一致（避免夜间熄屏倒计时虚减）。
         */
        fun computeRemainingMillis(
            limitMillis: Long,
            lastUsedMillis: Long,
            lastCollectAtMs: Long,
            nowMs: Long,
            screenInteractive: Boolean
        ): Long {
            if (limitMillis <= 0) return 0
            if (lastCollectAtMs <= 0) return limitMillis  // 尚未完成首次采集：不虚构已用
            val delta = interactiveDeltaMs(lastCollectAtMs, nowMs, screenInteractive)
            return (limitMillis - lastUsedMillis - delta).coerceAtLeast(0)
        }

        /** [FIX-COUNTDOWN] 距锚点的交互增量（毫秒），封顶一个采集周期，防止熄屏/延迟导致漂移 */
        fun interactiveDeltaMs(
            anchorMs: Long,
            nowMs: Long,
            screenInteractive: Boolean,
            capMs: Long = COLLECT_INTERVAL_MS
        ): Long = if (screenInteractive && anchorMs > 0)
            (nowMs - anchorMs).coerceIn(0, capMs) else 0L

        /**
         * [FIX-COUNTDOWN] 分钟粒度采集回跳修复（纯函数，单测）：
         * 估算 = max(新采集分钟值, 旧估算 + 交互增量)，交互增量封顶一个采集周期。
         * - 估算只增不减：倒计时不回跳、不冻结（交互时间随采集周期延续）；
         * - 交互增量封顶一个采集周期：熄屏/采集延迟不虚增；
         * - 重置/跨天判定用「上次权威采集值」对比，估算领先采集值不会误判为重置。
         * @return Pair(新估算已用毫秒, 是否推进锚点)
         */
        fun reconcileCollect(
            prevUsedMs: Long,
            prevCollectedMs: Long,
            prevAnchorMs: Long,
            nowMs: Long,
            collectedUsedMs: Long,
            screenInteractive: Boolean,
            collectIntervalMs: Long = COLLECT_INTERVAL_MS
        ): Pair<Long, Boolean> {
            val resetDrop = prevCollectedMs > 0 &&
                collectedUsedMs < prevCollectedMs - collectIntervalMs
            if (resetDrop) return collectedUsedMs to true
            val delta = interactiveDeltaMs(prevAnchorMs, nowMs, screenInteractive, collectIntervalMs)
            val carried = if (prevAnchorMs > 0) prevUsedMs + delta else 0L
            val estimate = maxOf(collectedUsedMs, carried)
            val advanceAnchor = prevAnchorMs <= 0 || estimate != prevUsedMs
            return estimate to advanceAnchor
        }

        /** 采集是否已失效（最近一次成功采集距今超过阈值） */
        fun isCollectStale(
            lastCollectAtMs: Long,
            nowMs: Long,
            staleThresholdMs: Long = COLLECT_STALE_THRESHOLD_MS
        ): Boolean = lastCollectAtMs > 0 && nowMs - lastCollectAtMs > staleThresholdMs

        /** 毫秒 → HH:MM:SS（秒级倒计时展示） */
        fun formatHms(millis: Long): String {
            val totalSec = (millis / 1000).coerceAtLeast(0)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
        }
    }

    /** [TASK-HARDENING-V1.1.1] Bug2：秒级倒计时快照（UI 每秒轮询） */
    data class CountdownSnapshot(
        val healthy: Boolean,        // 采集健康（权限正常、未超时失效）；false → UI 显示「守护失效」
        val limitMillis: Long,       // 今日限额（毫秒；0=未设置限额）
        val usedMillis: Long,        // 调整后已用（含最近采集以来的交互增量）
        val remainingMillis: Long,   // 剩余（毫秒，>=0）
        val isTimeoutActive: Boolean,
        val stopMode: String,
        val resetOffsetMinutes: Long
    ) {
        companion object {
            /** 守护未启动/失效时的占位快照 */
            val EMPTY = CountdownSnapshot(false, 0, 0, 0, false, "none", 0)
        }
    }

    private val dao = UsageRecordDao(XiaopacaiApp.instance.database)
    private val timeoutExecutor = TimeoutExecutor(context)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var collectJob: Job? = null

    init {
        instance = this
    }

    /** 当前数据快照（UI 可观察） */
    private val _currentUsage = mutableMapOf<String, Long>()
    val currentUsage: Map<String, Long> get() = _currentUsage.toMap()

    private var _todayTotalMinutes: Long = 0
    val todayTotalMinutes: Long get() = _todayTotalMinutes

    // [TASK-PRELAUNCH-P4] 限额重置偏移（家长端“重置当日限额”后“已用”从 0 重新计时）
    private var _resetOffsetMinutes: Long = 0
    val resetOffsetMinutes: Long get() = _resetOffsetMinutes

    /** 调整后今日已用 = max(0, 原始累计 − 重置偏移)；超时判断与 UI 展示统一用此口径 */
    val todayAdjustedMinutes: Long
        get() = (_todayTotalMinutes - _resetOffsetMinutes).coerceAtLeast(0)

    /** 是否处于超时停用状态 */
    private var _isTimeoutActive: Boolean = false
    val isTimeoutActive: Boolean get() = _isTimeoutActive

    /** 停用模式 */
    private var _stopMode: String = "none"
    val stopMode: String get() = _stopMode

    /** 今日限额（分钟） */
    private var _todayLimitMinutes: Long = 0
    val todayLimitMinutes: Long get() = _todayLimitMinutes

    // [TASK-HARDENING-V1.1.1] Bug2-A：秒级倒计时锚点（最近一次成功采集）
    @Volatile
    private var _lastCollectAtMs: Long = 0L

    /** [FIX-COUNTDOWN] 倒计时增量锚点（仅在估算推进时更新，避免采集分钟未进位时回跳） */
    @Volatile
    private var _countdownAnchorMs: Long = 0L

    @Volatile
    private var _lastCollectAdjustedUsedMs: Long = 0L

    /** [FIX-COUNTDOWN] 上次权威采集的已用值（用于重置/跨天判定，估算领先不误判） */
    @Volatile
    private var _lastCollectedUsedMs: Long = 0L

    /** 采集健康状态：权限撤销/采集异常 = false → UI 显示「守护失效」，禁止假倒计时 */
    @Volatile
    private var _collectHealthy: Boolean = false

    /**
     * 启动定时采集循环
     */
    fun start() {
        stop()  // 防止重复启动
        collectJob = scope.launch {
            delay(INITIAL_DELAY_MS)  // 初始延迟
            while (isActive) {
                try {
                    collectAndPersist()
                } catch (e: Exception) {
                    // [TASK-HARDENING-V1.1.1] Bug2-A：采集异常 → 守护失效展示，不假倒计时
                    _collectHealthy = false
                    Log.e(TAG, "采集失败: ${e.message}", e)
                }
                delay(COLLECT_INTERVAL_MS)
            }
        }
        Log.i(TAG, "时长采集器已启动（间隔 ${COLLECT_INTERVAL_MS / 1000}s）")
    }

    /**
     * 停止采集循环
     */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
        Log.i(TAG, "时长采集器已停止")
    }

    /**
     * [TASK-HARDENING-V1.1.1] Bug2-A：秒级倒计时快照（UI 每秒调用）
     *
     * 剩余 = 今日限额 −（最近采集已用 + 距最近采集的交互增量）；
     * 采集失效（权限撤销/异常/超时未采集）→ healthy=false，
     * UI 如实显示「守护失效」，禁止用旧数据假倒计时。
     */
    fun countdownSnapshot(): CountdownSnapshot {
        val now = System.currentTimeMillis()
        val hasCollected = _lastCollectAtMs > 0
        val healthy = hasCollected && _collectHealthy && !isCollectStale(_lastCollectAtMs, now)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive ?: true
        val limitMillis = _todayLimitMinutes * 60_000L
        val usedMillis = if (hasCollected) {
            _lastCollectAdjustedUsedMs + interactiveDeltaMs(_countdownAnchorMs, now, interactive)
        } else 0L
        return CountdownSnapshot(
            healthy = healthy,
            limitMillis = limitMillis,
            usedMillis = usedMillis,
            remainingMillis = computeRemainingMillis(
                limitMillis, _lastCollectAdjustedUsedMs, _countdownAnchorMs, now, interactive),
            isTimeoutActive = _isTimeoutActive,
            stopMode = _stopMode,
            resetOffsetMinutes = _resetOffsetMinutes
        )
    }

    /**
     * [TASK-HARDENING-V1.1.1] Bug2-B：倒计时归零立即锁定（双保险消除 ≤60s 采集空窗）
     *
     * UI 每秒调用：健康快照归零且尚未锁定 → 立即走 TimeoutExecutor 锁定链路，
     * 不等下一个采集周期。采集失效时不虚构锁定（只展示守护失效）。
     */
    @Synchronized
    fun lockIfCountdownExpired(): Boolean {
        val snap = countdownSnapshot()
        if (!snap.healthy || snap.limitMillis <= 0) return false
        if (snap.remainingMillis <= 0 && !_isTimeoutActive) {
            Log.i(TAG, "秒级倒计时归零 → 立即锁定（双保险）")
            val passphrase = getPassphrase()
            _isTimeoutActive = true
            _stopMode = getRestrictMode(passphrase)
            timeoutExecutor.checkAndExecute(
                isTimeout = true,
                stopMode = _stopMode,
                usedMinutes = todayAdjustedMinutes,
                limitMinutes = _todayLimitMinutes,
                triggerReason = null
            )
            return true
        }
        return false
    }

    /**
     * 执行一次完整的采集 + 持久化流程
     */
    // [REQ] 加锁：收到 limit_reset 时可能从 SyncManager 立即触发重采，避免与定时采集并发冲突
    @Synchronized
    fun collectAndPersist() {
        val passphrase = getPassphrase()
        val today = dateFormat.format(Date())
        val calendar = Calendar.getInstance()

        // [TASK-PRELAUNCH-P4] 每周期读取限额重置偏移（日期不匹配自动归零）
        loadResetOffset(today)

        // 1. 从 UsageStatsManager 获取原始数据
        // [TASK-HARDENING-V1.1.1] Bug2-A：使用情况权限被撤销 → 采集失效，
        // 标记不健康（UI 显示「守护失效」），禁止用旧数据假倒计时
        val hasUsagePermission = AntiBypassService.isUsageStatsPermissionGranted(context)
        val usageMap = if (hasUsagePermission) {
            UsageStatsHelper.getDailyUsageMinutes(context, calendar)
        } else {
            _collectHealthy = false
            emptyMap()
        }
        _currentUsage.clear()
        _currentUsage.putAll(usageMap)
        val rawTotal = usageMap.values.sum()

        // [REQ] 每日限额重置：家长重置后，今日已用 = 系统累计 - 重置时偏移，重新开始计时
        // 注意：usage_records 仍写入原始分钟数，使用报告照常统计重置前浪费的额度
        val resetOffset = _resetOffsetMinutes
        _todayTotalMinutes = (rawTotal - resetOffset).coerceAtLeast(0L)

        // [TASK-HARDENING-V1.1.1] Bug2-A：成功采集即更新倒计时锚点
        // （权限正常时即使当日无使用数据也视为健康——零使用日不是失效）
        if (hasUsagePermission) {
            _collectHealthy = true
            val now = System.currentTimeMillis()
            _lastCollectAtMs = now
            // [FIX-COUNTDOWN] 分钟粒度回跳：合并旧估算，锚点仅在估算推进时更新，
            // 交互增量随采集周期延续，避免采集分钟未进位时剩余时长回跳/冻结。
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val interactive = pm?.isInteractive ?: true
            val (newUsed, advanceAnchor) = reconcileCollect(
                prevUsedMs = _lastCollectAdjustedUsedMs,
                prevCollectedMs = _lastCollectedUsedMs,
                prevAnchorMs = _countdownAnchorMs,
                nowMs = now,
                collectedUsedMs = todayAdjustedMinutes * 60_000L,
                screenInteractive = interactive
            )
            _lastCollectAdjustedUsedMs = newUsed
            _lastCollectedUsedMs = todayAdjustedMinutes * 60_000L
            if (advanceAnchor) _countdownAnchorMs = now
        }

        if (usageMap.isEmpty()) {
            Log.d(TAG, "今日无使用数据")
            return
        }

        // 2. 分类并构建记录列表
        val pm = context.packageManager
        val entries = usageMap.map { (packageName, minutes) ->
            val appName = try {
                pm.getApplicationLabel(
                    pm.getApplicationInfo(packageName, 0)
                ).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName  // 降级使用包名
            }
            val category = classifyApp(packageName, appName)

            UsageRecordEntry(
                packageName = packageName,
                appName = appName,
                date = today,
                totalMinutes = minutes,
                category = category
            )
        }

        // 3. 批量写入数据库
        val count = dao.batchUpsertUsageRecords(entries, passphrase)
        Log.d(TAG, "已写入 $count 条使用记录")

        // 4. 更新每日汇总
        val gameMinutes = entries
            .filter { it.category == "game" }
            .sumOf { it.totalMinutes }
        val studyMinutes = entries
            .filter { it.category == "study" }
            .sumOf { it.totalMinutes }
        val limitMinutes = getTodayLimitMinutes(passphrase)

        dao.updateDailySummary(
            date = today,
            totalMinutes = _todayTotalMinutes,
            gameMinutes = gameMinutes,
            studyMinutes = studyMinutes,
            limitMinutes = limitMinutes,
            passphrase = passphrase
        )

        // 5. 检查超时状态（[TASK-PRELAUNCH-P4] 按调整后口径：重置后立即解除超时）
        checkTimeoutStatus(today, limitMinutes, passphrase)

        // 5.5 [REQ] 就寝时段：进入就寝窗口立即整机停用（优先级高于日常限额的 partial）
        val sleepActive = isInSleepWindow(passphrase)
        if (sleepActive && (!_isTimeoutActive || _stopMode != "full")) {
            _isTimeoutActive = true
            _stopMode = "full"
            Log.i(TAG, "就寝时段生效：整机停用")
        }

        // 6. 执行超时停用（主动封锁 + 事件记录；[TASK-PRELAUNCH-P4] 按调整后已用判定）
        timeoutExecutor.checkAndExecute(
            isTimeout = _isTimeoutActive,
            stopMode = _stopMode,
            usedMinutes = todayAdjustedMinutes,
            limitMinutes = limitMinutes,
            triggerReason = if (sleepActive) {
                "已到就寝时间，请休息"
            } else null
        )

        Log.d(TAG, "今日总时长: ${_todayTotalMinutes}分钟（调整后 ${todayAdjustedMinutes}）| " +
                "游戏: ${gameMinutes}分钟 | 学习: ${studyMinutes}分钟 | " +
                "限额: ${limitMinutes}分钟 | 超限: ${_isTimeoutActive} | 重置偏移: ${_resetOffsetMinutes}分钟")
    }

    /**
     * [TASK-PRELAUNCH-P4] 从 SharedPreferences 读取今日限额重置偏移（日期不匹配归零）
     */
    private fun loadResetOffset(today: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offsetDate = prefs.getString(KEY_RESET_OFFSET_DATE, null)
        _resetOffsetMinutes = if (offsetDate == today) {
            prefs.getLong(KEY_RESET_OFFSET_MINUTES, 0L).coerceAtLeast(0)
        } else {
            0L
        }
    }

    /**
     * 分类应用（基于包名和名称的关键词匹配）
     */
    private fun classifyApp(packageName: String, appName: String): String {
        val searchText = "${packageName.lowercase()} ${appName.lowercase()}"
        for ((keyword, category) in CATEGORY_RULES) {
            if (keyword in searchText) return category
        }
        return "other"
    }

    /**
     * 获取今日限额（从策略缓存读取）
     */
    private fun getTodayLimitMinutes(passphrase: ByteArray): Long {
        return try {
            val db = XiaopacaiApp.instance.database.getReadable(passphrase)
            try {
                val cursor = db.rawQuery(
                    "SELECT policy_data FROM policy_cache WHERE policy_type = ? AND " +
                    "(SELECT COUNT(*) FROM policy_cache WHERE policy_type = 'daily_limit') > 0",
                    arrayOf("daily_limit")
                )
                cursor.use {
                    if (it.moveToFirst()) {
                        val json = it.getString(0)
                        // 从 JSON 中提取 limitMinutes 字段
                        val limitPattern = Regex(""""limitMinutes"\s*:\s*(\d+)""")
                        limitPattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    } else 0L
                }
            } finally {
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取限额失败: ${e.message}")
            0L
        }
    }

    /**
     * [REQ] 判断当前时间是否处于就寝时段（policy_cache 的 sleep_time 策略）
     * 支持跨天窗口（如 23:40-07:00）；时间格式必须为 HH:mm
     */
    private fun isInSleepWindow(passphrase: ByteArray): Boolean {
        return try {
            val db = XiaopacaiApp.instance.database.getReadable(passphrase)
            try {
                val cursor = db.rawQuery(
                    "SELECT policy_data FROM policy_cache WHERE policy_type = ?",
                    arrayOf("sleep_time")
                )
                cursor.use {
                    if (!it.moveToFirst()) return false
                    val json = it.getString(0)
                    val startPattern = Regex(""""sleepStart"\s*:\s*"(\d{1,2}:\d{2})"""")
                    val endPattern = Regex(""""sleepEnd"\s*:\s*"(\d{1,2}:\d{2})"""")
                    val start = startPattern.find(json)?.groupValues?.get(1) ?: return false
                    val end = endPattern.find(json)?.groupValues?.get(1) ?: return false
                    val s = parseHm(start) ?: return false
                    val e = parseHm(end) ?: return false
                    if (s == e) return false

                    val now = Calendar.getInstance()
                    val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    return if (s < e) cur >= s && cur < e else cur >= s || cur < e
                }
            } finally {
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取就寝时段失败: ${e.message}")
            false
        }
    }

    /** 解析 HH:mm 为分钟数，失败返回 null */
    private fun parseHm(text: String): Int? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /**
     * 检查并更新超时状态
     */
    private fun checkTimeoutStatus(today: String, limitMinutes: Long, passphrase: ByteArray) {
        _todayLimitMinutes = limitMinutes
        if (limitMinutes <= 0) {
            // 无限额 → 非超时
            _isTimeoutActive = false
            _stopMode = "none"
            return
        }

        // [TASK-PRELAUNCH-P4] 超时判定用调整后口径（重置限额后立即解除封锁）
        val exceeded = todayAdjustedMinutes >= limitMinutes
        if (exceeded != _isTimeoutActive) {
            _isTimeoutActive = exceeded
            _stopMode = if (exceeded) getRestrictMode(passphrase) else "none"
            Log.i(TAG, "超时状态变更: isTimeout=$_isTimeoutActive, mode=$_stopMode")
        }
    }

    /**
     * [TASK-OPT-7] 从策略缓存读取超时处理模式（full/partial/warn），缺省 full
     * 与 Web/家长端 OvertimeAction（full_lock/partial_lock/warn_only）对齐
     */
    private fun getRestrictMode(passphrase: ByteArray): String {
        return try {
            val db = XiaopacaiApp.instance.database.getReadable(passphrase)
            try {
                val cursor = db.rawQuery(
                    "SELECT policy_data FROM policy_cache WHERE policy_type = ?",
                    arrayOf("daily_limit")
                )
                cursor.use {
                    if (it.moveToFirst()) {
                        val json = it.getString(0)
                        val modePattern = Regex(""""restrictMode"\s*:\s*"(\w+)"""")
                        val mode = modePattern.find(json)?.groupValues?.get(1)
                        when (mode) {
                            "partial" -> "partial"
                            "warn" -> "warn"
                            else -> "full"
                        }
                    } else "full"
                }
            } finally {
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取停用模式失败，默认 full: ${e.message}")
            "full"
        }
    }

    /**
     * 获取数据库加密密码 [TASK-D3-05]
     */
    private fun getPassphrase(): ByteArray {
        return DbPassphraseProvider.getPassphrase(context)
    }

    /**
     * 紧急解除：临时暂停/恢复超时拦截（不修改策略数据，仅运行时标记）
     */
    fun pauseEnforcement(paused: Boolean) {
        if (paused) {
            _isTimeoutActive = false
            _stopMode = "none"
            Log.i(TAG, "紧急解除：超时拦截已暂停")
        } else {
            Log.i(TAG, "紧急解除结束：下次采集将恢复正常超时判定")
        }
    }
}
