// [android-tv] 工具类 — 使用时长统计
// 小趴菜 TVOS 版 — UsageStatsManager 查询

package com.xiaopacai.tvos.util

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 使用时长统计工具
 * 查询各应用今日使用时长（分钟），返回 Map<packageName, minutes>
 */
object UsageStatsHelperTV {

    private var cached: Map<String, Int>? = null        // 分钟（对外兼容旧调用）
    private var cachedSeconds: Map<String, Int>? = null // 秒（倒计时用，精度更高）
    private var cacheTime: Long = 0
    // [FIX-2026-09-12] 缓存从 60s 收到 15s：首页每 30s 采样一次，60s 缓存会让相邻两次采样拿到同一个值
    // → 倒计时每次刷新都回跳（真机表现：一直卡在 18:xx 循环）。秒级 + 单调采样一起解决。
    private const val CACHE_TTL_MS = 15_000L

    /**
     * 查询今日所有应用使用时长
     * @return Map<包名, 已用分钟数>
     */
    fun queryTodayUsage(context: Context): Map<String, Int> {
        ensureFresh(context)
        return cached ?: emptyMap()
    }

    /**
     * 今日各应用使用**秒数**（与 [queryTodayUsage] 复用同一次系统查询）
     */
    fun queryTodayUsageSeconds(context: Context): Map<String, Int> {
        ensureFresh(context)
        return cachedSeconds ?: emptyMap()
    }

    private fun ensureFresh(context: Context) {
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTime < CACHE_TTL_MS) return

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            cal.timeInMillis,
            now
        )

        val usageSeconds = mutableMapOf<String, Int>()
        val usageMinutes = mutableMapOf<String, Int>()
        stats.forEach { stat ->
            // 时长以毫秒计
            val seconds = (stat.totalTimeInForeground / 1000).toInt()
            if (seconds <= 0) return@forEach
            usageSeconds[stat.packageName] = seconds
            val minutes = seconds / 60
            if (minutes > 0) usageMinutes[stat.packageName] = minutes
        }

        cached = usageMinutes
        cachedSeconds = usageSeconds
        cacheTime = now
    }

    /**
     * 今日总使用分钟（**剔除本应用自身**）
     *
     * [BUGFIX 2026-09-12] 锁屏/守护界面属于本应用，且整屏常亮长期在前台；
     * 若把自己的前台时长也算进去，就会出现「锁得越久 → 用量越高 → 永远解不开」的自我累积
     * （真机实测：整夜锁屏后用量被自身顶到 600 分钟以上，家长怎么调限额都解不开）。
     * 孩子看的「电视时长」只应统计**其他应用**。
     */
    fun queryTodayTotalMinutes(context: Context): Int =
        maxOf(0, rawTodayTotalMinutes(context) - resetOffsetMinutes(context))

    /**
     * 今日原始总分钟（不含重置偏移，剔除本应用自身）
     */
    fun rawTodayTotalMinutes(context: Context): Int =
        queryTodayUsage(context)
            .filterKeys { it != context.packageName }
            .values
            .sum()

    // ==================== [FIX-2026-09-12] 重置后用量基线 ====================
    // 与手机端同语义（android-v3 UsageStatsCollector.applyLimitReset）：
    // 家长点「重置当日限额」时，把**重置那一刻的今日原始用量**记为偏移，
    // 有效用量 = 今日原始 − 偏移；偏移跨日自动失效。
    //
    // 电视端此前只 clearCache()（只是清掉 1 分钟缓存），下一次查询又从系统读出
    // 同一份原始用量（≥ 限额）→ 立刻重新判定超限，重置形同虚设。
    private const val PREF_RESET = "usage_reset_baseline"
    private const val KEY_RESET_OFFSET_MINUTES = "daily_reset_offset_minutes"
    private const val KEY_RESET_OFFSET_SECONDS = "daily_reset_offset_seconds"
    private const val KEY_RESET_OFFSET_DATE = "daily_reset_offset_date"

    /**
     * 今日原始总秒数（不含重置偏移，剔除本应用自身）
     */
    fun rawTodayTotalSeconds(context: Context): Int =
        queryTodayUsageSeconds(context)
            .filterKeys { it != context.packageName }
            .values
            .sum()

    // ==================== [FIX-2026-09-12] 倒计时单调采样 ====================
    // 症状：首页倒计时一直卡在某个值（真机：18:xx）反复循环。
    // 原因：显示用「(限额 − 已用分钟) − 采样后流逝时间」，而"已用"是**分钟取整 + 60s 缓存**：
    //      每 30s 刷新时这个值常常没变，剩余就被重新算回整分 → 显示来回跳（18:00 → 17:31 → 18:00）。
    // 修法：改用秒级采样 + 单调化（同一天、同一重置基线内，已用秒数只增不减）。
    private var monoKey: String = ""
    private var monoSeconds: Int = 0

    /**
     * 供倒计时使用的「今日已用秒数」：秒级精度 + 单调递增（重置/跨日时允许跳变）。
     */
    fun monotonicUsedSeconds(context: Context): Int {
        val raw = rawTodayTotalSeconds(context)
        val offset = resetOffsetSeconds(context)
        val effective = maxOf(0, raw - offset)
        val key = todayKey() + "#" + offset
        if (key != monoKey) {
            // 新的一天 / 刚发生过重置（偏移变了）→ 基准重取，此时允许数值跳变
            monoKey = key
            monoSeconds = effective
        } else if (effective > monoSeconds) {
            monoSeconds = effective
        }
        return monoSeconds
    }

    // ==================== [FIX-2026-09-12] 倒计时展示估算（对标手机端） ====================
    // 手机端当年同样的 bug 与修法（android-v3 UsageStatsCollector，注释原文）：
    //   「分钟粒度回跳：合并旧估算，锚点仅在估算推进时更新，交互增量随采集周期延续，
    //     避免采集分钟未进位时剩余时长回跳/冻结」
    // 电视端同样只有分钟级用量。要的效果：正在看电视 → 倒计时逐秒走；熄屏/待机 → 不走；
    // 系统用量还没进位时 → 用「交互增量」延续，绝不回跳。
    const val SAMPLE_INTERVAL_MS = 30_000L

    /**
     * [FIX-2026-09-12 · 用户口径] **额度有效期内不论在哪个界面一律算时间**（含自家首页/小米桌面），
     * 所以「交互增量」不设领先上限：它就是把「屏幕可交互的墙钟时长」计入用量。
     *
     * 唯一的例外：已经上锁时不加成（锁屏页不是看电视的界面，超限后继续累加也没有意义）。
     * 由 `TimeoutExecutorTV` 在 lock/unlock 时切换。
     *
     * 让「卡片显示」与「是否锁屏」同源（`TimeoutExecutorTV.effectiveUsedSeconds`）之后，
     * 估算领先多少都不会再出现「卡片说已超时、判定说没超 ⇒ 不锁屏」。
     */
    @Volatile
    var pauseSelfScreenAccrual: Boolean = false

    /** 交互增量：距锚点已流逝的毫秒（仅在屏幕可交互时计，封顶一个采样周期，防止熄屏/采集延迟虚增） */
    fun interactiveDeltaMs(
        anchorMs: Long,
        nowMs: Long,
        screenInteractive: Boolean,
        capMs: Long = SAMPLE_INTERVAL_MS
    ): Long = if (screenInteractive && anchorMs > 0) {
        (nowMs - anchorMs).coerceIn(0L, capMs)
    } else 0L

    fun isScreenInteractive(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

    // 展示估算状态（进程内）
    private var estUsedSeconds: Int = 0
    private var estAnchorMs: Long = 0
    private var lastAuthoritativeSeconds: Int = 0

    /**
     * 采样一次，返回 (展示用已用秒数, 采样锚点时刻)。
     *
     * 估算 = max(系统权威值, 上次估算 + 交互增量) —— 只增不减；权威值显著下降（重置/跨日）则重新起算。
     */
    fun sampleDisplayUsedSeconds(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Int, Long> {
        val authoritative = monotonicUsedSeconds(context)
        // 权威值比上次少了一个采样周期以上 → 判定为重置或跨日，重新起算
        if (lastAuthoritativeSeconds > 0 &&
            authoritative < lastAuthoritativeSeconds - (SAMPLE_INTERVAL_MS / 1000).toInt()
        ) {
            estUsedSeconds = authoritative
            estAnchorMs = nowMs
            lastAuthoritativeSeconds = authoritative
            return estUsedSeconds to estAnchorMs
        }
        // [FIX-2026-09-12 · 用户口径] 额度有效期内**不论在哪个界面一律算时间**（含自家首页/桌面），
        // 所以这里不再给估算设「领先上限」；展示与判定同源（见 TimeoutExecutorTV.effectiveUsedSeconds），
        // 因此估算领先多少都不会再出现「卡片说超时、判定说没超」。
        // 唯一不加成的时段：已经上锁（锁屏页不是看电视的界面，且超限后继续累加无意义）。
        val interactive = isScreenInteractive(context) && !pauseSelfScreenAccrual
        val deltaSeconds = (interactiveDeltaMs(estAnchorMs, nowMs, interactive) / 1000L).toInt()
        val carried = if (estAnchorMs > 0) estUsedSeconds + deltaSeconds else 0
        val estimate = maxOf(authoritative, carried)
        if (estAnchorMs <= 0 || estimate != estUsedSeconds) {
            estUsedSeconds = estimate
            estAnchorMs = nowMs
        }
        lastAuthoritativeSeconds = authoritative
        return estUsedSeconds to estAnchorMs
    }

    /**
     * 记录重置基线（offsetSeconds 传重置当刻的原始用量**秒数**）
     */
    fun applyLimitReset(context: Context, offsetSeconds: Int) {
        val seconds = maxOf(0, offsetSeconds)
        context.getSharedPreferences(PREF_RESET, Context.MODE_PRIVATE).edit()
            .putInt(KEY_RESET_OFFSET_SECONDS, seconds)
            .putInt(KEY_RESET_OFFSET_MINUTES, seconds / 60)   // 兼容旧版本读的分钟键
            .putString(KEY_RESET_OFFSET_DATE, todayKey())
            .apply()
        monoKey = ""      // 基线变了 → 单调采样基准作废，下次重取
        clearCache()
    }

    /**
     * 今日重置偏移（秒；非今天记录的 → 0，即跨日自动失效）
     */
    fun resetOffsetSeconds(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_RESET, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_RESET_OFFSET_DATE, null) != todayKey()) return 0
        val seconds = prefs.getInt(KEY_RESET_OFFSET_SECONDS, -1)
        if (seconds >= 0) return seconds
        // 旧版本只写过分钟键 → 退化成分钟
        return maxOf(0, prefs.getInt(KEY_RESET_OFFSET_MINUTES, 0)) * 60
    }

    /**
     * 今日重置偏移（分钟，兼容保留）
     */
    fun resetOffsetMinutes(context: Context): Int = resetOffsetSeconds(context) / 60

    /**
     * [FIX-2026-09-12 · 用户口径] 上报给服务端的「今日已用」（分钟）。
     *
     * 必须与首页卡片、限额判定同源（`sampleDisplayUsedSeconds`）——否则家长端数字与电视上
     * 孩子看到的数字不一致：电视口径是「额度有效期内任何界面都算时间」，会比系统统计的
     * 前台应用时长更大（停在自家首页/桌面/锁屏前的那段时间）。
     *
     * ⚠️ 不要再减 `resetOffsetSeconds`：估算的基准是 `monotonicUsedSeconds`（= 原始 − 基线），
     * 已经扣过一次了；再减一次会把家长的「今日已用」直接打成 0（真机 19:05 就是这么错的：
     * 估算 75 分钟 − 基线 251 分钟 < 0）。
     */
    fun reportedAdjustedMinutes(context: Context): Int {
        val seconds = runCatching { sampleDisplayUsedSeconds(context).first }.getOrDefault(0)
        return maxOf(0, seconds / 60)
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /**
     * 清除缓存
     */
    fun clearCache() {
        cached = null
        cacheTime = 0
    }

    /**
     * 检查是否已授予使用时长统计权限
     */
    fun isUsageStatsPermissionGranted(context: Context): Boolean {
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // 尝试查询，如果没权限会抛出 SecurityException
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                cal.timeInMillis,
                now
            )
            return true
        } catch (e: SecurityException) {
            return false
        }
    }

    /**
     * 打开使用时长统计权限设置页面
     */
    fun openUsageStatsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
