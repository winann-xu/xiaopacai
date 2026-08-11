package com.xiaopacai.child.service

import android.content.Context
import android.content.pm.PackageManager
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
        /** 采集间隔：5 分钟 */
        private const val COLLECT_INTERVAL_MS = 5 * 60 * 1000L
        /** 初始延迟：30 秒（给系统启动留足时间） */
        private const val INITIAL_DELAY_MS = 30 * 1000L

        // === 应用分类规则（包名关键词 → 分类） ===
        // [TASK-OPT-12-P2] 对外暴露：应用分类初始化助手（AppCategoryHelper）复用同一套规则
        val CATEGORY_RULES = listOf(
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
    }

    private val dao = UsageRecordDao(XiaopacaiApp.instance.database)
    private val timeoutExecutor = TimeoutExecutor(context)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var collectJob: Job? = null

    /** 当前数据快照（UI 可观察） */
    private val _currentUsage = mutableMapOf<String, Long>()
    val currentUsage: Map<String, Long> get() = _currentUsage.toMap()

    private var _todayTotalMinutes: Long = 0
    val todayTotalMinutes: Long get() = _todayTotalMinutes

    /** 是否处于超时停用状态 */
    private var _isTimeoutActive: Boolean = false
    val isTimeoutActive: Boolean get() = _isTimeoutActive

    /** 停用模式 */
    private var _stopMode: String = "none"
    val stopMode: String get() = _stopMode

    /** 今日限额（分钟） */
    private var _todayLimitMinutes: Long = 0
    val todayLimitMinutes: Long get() = _todayLimitMinutes

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
     * 执行一次完整的采集 + 持久化流程
     */
    fun collectAndPersist() {
        val passphrase = getPassphrase()
        val today = dateFormat.format(Date())
        val calendar = Calendar.getInstance()

        // 1. 从 UsageStatsManager 获取原始数据
        val usageMap = UsageStatsHelper.getDailyUsageMinutes(context, calendar)
        _currentUsage.clear()
        _currentUsage.putAll(usageMap)
        _todayTotalMinutes = usageMap.values.sum()

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

        // 5. 检查超时状态
        checkTimeoutStatus(today, limitMinutes, passphrase)

        // 6. 执行超时停用（主动封锁 + 事件记录）
        timeoutExecutor.checkAndExecute(
            isTimeout = _isTimeoutActive,
            stopMode = _stopMode,
            usedMinutes = _todayTotalMinutes,
            limitMinutes = limitMinutes
        )

        Log.d(TAG, "今日总时长: ${_todayTotalMinutes}分钟 | " +
                "游戏: ${gameMinutes}分钟 | 学习: ${studyMinutes}分钟 | " +
                "限额: ${limitMinutes}分钟 | 超限: ${_isTimeoutActive}")
    }

    /**
     * 分类应用
     *
     * [TASK-OPT-12-P2] 优先使用 app_category 表（家长手工设置优先），
     * 未收录时按关键词规则分类；V3 口径 learning 映射回内部 study。
     */
    private fun classifyApp(packageName: String, appName: String): String {
        // 1. 查询家长设置的应用分类（manual 覆盖 default）
        val stored = try {
            com.xiaopacai.child.data.database.AppCategoryDao(XiaopacaiApp.instance.database)
                .getCategory(packageName, getPassphrase())
        } catch (e: Exception) {
            null
        }
        if (stored != null) {
            // 时长上报沿用旧 study 口径，V3 learning 在此映射
            return com.xiaopacai.child.util.AppCategoryHelper.toInternalCategory(stored)
        }

        // 2. 兜底：关键词规则
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
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取限额失败: ${e.message}")
            0L
        }
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

        val exceeded = _todayTotalMinutes >= limitMinutes
        if (exceeded != _isTimeoutActive) {
            _isTimeoutActive = exceeded
            // [TASK-OPT-12-P2] 按策略 restrictMode 决定停用模式（需求7）：
            // full_lock→full / partial_lock→partial / warn_only→warn，缺省 full（兼容旧策略）
            _stopMode = if (exceeded) getRestrictMode(passphrase) else "none"
            Log.i(TAG, "超时状态变更: isTimeout=$_isTimeoutActive, mode=$_stopMode")
        }
    }

    /**
     * [TASK-OPT-12-P2] 读取策略限制模式（需求7 partial_lock 全链路）
     *
     * 从 daily_limit 策略缓存解析 restrictMode 字段：
     * - "full"（或 full_lock/缺省）→ full：整机停用
     * - "partial"（或 partial_lock）→ partial：部分停用
     * - "warn"（或 warn_only）→ warn：仅警告
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
                        val modePattern = Regex(""""restrictMode"\s*:\s*"([^"]+)"""")
                        val raw = modePattern.find(json)?.groupValues?.get(1) ?: "full"
                        // 与 Web 端 OvertimeAction 对齐：full_lock/partial_lock/warn_only → full/partial/warn
                        when (raw) {
                            "full_lock" -> "full"
                            "partial_lock" -> "partial"
                            "warn_only" -> "warn"
                            else -> raw.ifBlank { "full" }
                        }
                    } else "full"
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取限制模式失败: ${e.message}")
            "full"
        }
    }

    /**
     * 获取数据库加密密码 [TASK-D3-05]
     */
    private fun getPassphrase(): ByteArray {
        return DbPassphraseProvider.getPassphrase(context)
    }
}
