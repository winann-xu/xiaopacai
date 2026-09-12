// [android-tv] 工具类 — 超时执行器
// [TASK-TV-01] 按 CORRECTION.md 偏离纠正 1：锁屏改用 LockOverlayService（纯 View 覆盖层）
// 小趴菜 TVOS 版 — 定时检查、超时锁屏状态机

package com.xiaopacai.tvos.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.xiaopacai.tvos.XiaopacaiTVApp
import com.xiaopacai.tvos.data.database.DeviceConfigDao
import com.xiaopacai.tvos.data.database.AppUsageDao
import com.xiaopacai.tvos.data.model.DeviceConfigEntity
import com.xiaopacai.tvos.data.model.AppUsageEntity
import com.xiaopacai.tvos.service.LockOverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 超时执行器 — 核心锁屏状态机
 * 
 * 状态转换:
 *  IDLE → RUNNING（开始使用） → EXPIRING（即将到期，提示） → LOCKED（超时锁屏）
 *  LOCKED → IDLE（家长解除） / IDLE（次日重置）
 */
object TimeoutExecutorTV {

    /** 超时状态枚举 */
    enum class State {
        IDLE,         // 空闲（未开始使用或未达限额）
        RUNNING,      // 使用中（已启动 TV 应用，计时中）
        EXPIRING,     // 即将到期（剩余 ≤ 10 分钟，给予提示）
        LOCKED        // 已锁定（超时，显示锁屏覆盖）
    }

    private var currentState: State = State.IDLE
        private set

    private var usageStartedAt: Long = 0
    private var lastUsageMinutes: Int = 0
    /** [FIX-2026-09-12] 今日已用**秒**（与首页倒计时同一个数，避免「卡片说超时、判定说没超」） */
    private var lastUsageSeconds: Int = 0
    private var remainingMinutesVal: Int = 0
    private var dailyLimitMinutes: Int = 60
    private var lockedState: Boolean = false

    /**
     * [TASK-GUARD-TOGGLE-V1] 小趴菜管控是否启用（家长在 Web 端「设备管理 → 禁用/启用」切换）。
     *
     * 停用 = 该设备**停止管控**：不锁屏、不拦截，并立即解除当前锁屏（含 kiosk）；
     * 启用 = 恢复管控：立刻重新评估，超限则重新上锁。
     * 指令双通道：P2P 实时下发 `guard_state` + 心跳响应 `guardEnabled` 兜底（离线期间切换也能纠正）。
     */
    @Volatile
    private var guardEnabled: Boolean = true

    fun isGuardEnabled(): Boolean = guardEnabled

    /**
     * 应用「管控启用/停用」（P2P 实时指令与心跳兜底都走这里；状态未变化时空操作）
     */
    fun setGuardEnabled(context: Context, enabled: Boolean) {
        if (guardEnabled == enabled) return
        guardEnabled = enabled
        scope.launch { runCatching { SharedPreferenceHelperTV.saveGuardEnabled(context.applicationContext, enabled) } }
        if (enabled) {
            AppLogTV.i("Timeout", "管控已启用（家长在 Web 端开启）→ 立即重新评估")
            refreshUsage(context)
        } else {
            AppLogTV.w("Timeout", "管控已停用（家长在 Web 端禁用）→ 解除锁屏并暂停限制")
            currentState = State.IDLE
            lockReason = ""
            unlock(context)
        }
    }

    /**
     * 家长验证进行中（登录页在前台）。
     * [BUGFIX] 此前守护服务每 30 秒用 CLEAR_TOP 重新拉起锁屏 Activity，会把家长验证页顶掉，
     * 导致家长永远完不成验证。验证期间暂停「重新上锁」与「重新拉起锁屏」。
     */
    @Volatile
    var verificationInProgress: Boolean = false
        private set

    /** 家长验证开始（进入登录页时调用） */
    fun beginVerification() {
        verificationInProgress = true
    }

    /** 家长验证结束（登录页关闭时调用） */
    fun endVerification() {
        verificationInProgress = false
    }

    // 协程作用域（应用级别）
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // DAO 引用（延迟初始化）
    private lateinit var configDao: DeviceConfigDao
    private lateinit var usageDao: AppUsageDao

    // 【A3】就寝时段（分钟数，null=未设置）；内存缓存供同步判断
    private var bedtimeStartMin: Int? = null
    private var bedtimeEndMin: Int? = null
    private var bedtimeLabel: String = ""

    /** 当前锁屏原因（供锁屏页展示） */
    var lockReason: String = ""
        private set

    /**
     * 初始化（由 Application 调用）
     */
    fun init(context: Context) {
        val app = context.applicationContext
        // 【A3】把持久化的就寝时段读回内存（策略未变化时也能在冷启动后生效）
        scope.launch {
            runCatching {
                val s = SharedPreferenceHelperTV.getBedtimeStart(app).first()
                val e = SharedPreferenceHelperTV.getBedtimeEnd(app).first()
                setBedtime(s.ifBlank { null }, e.ifBlank { null })
            }
        }
        // [TASK-GUARD-TOGGLE-V1] 冷启动先把「管控启停」读回内存（首帧心跳到达前也不误锁）
        scope.launch {
            runCatching {
                guardEnabled = SharedPreferenceHelperTV.getGuardEnabled(app).first()
                AppLogTV.i("Timeout", "管控启停（本地持久化）= $guardEnabled")
            }
        }
    }

    // ==================== 【A3】就寝时段 ====================

    /** 设置就寝时段（HH:mm 字符串；任一为空表示未启用） */
    fun setBedtime(start: String?, end: String?) {
        bedtimeStartMin = parseHhMm(start)
        bedtimeEndMin = parseHhMm(end)
        bedtimeLabel = if (bedtimeStartMin != null && bedtimeEndMin != null) {
            formatHhMm(bedtimeStartMin!!) + "–" + formatHhMm(bedtimeEndMin!!)
        } else ""
    }

    /** 现在是否处于就寝时段（支持跨零点，如 21:00–07:00） */
    fun isBedtimeNow(): Boolean {
        val s = bedtimeStartMin ?: return false
        val e = bedtimeEndMin ?: return false
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (s <= e) now in s until e else (now >= s || now < e)
    }

    /** 就寝时段文案（未设置则空串） */
    fun bedtimeText(): String = bedtimeLabel

    private fun parseHhMm(v: String?): Int? {
        if (v.isNullOrBlank()) return null
        val parts = v.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun formatHhMm(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
    }

    /**
     * 当前状态
     */
    val state: State
        get() = currentState

    /**
     * 是否已锁定
     */
    val isLocked: Boolean
        get() = lockedState

    /**
     * 剩余可用分钟数
     */
    val remainingMinutes: Int
        get() = remainingMinutesVal

    /**
     * 今日已用分钟数
     */
    val usedMinutesToday: Int
        get() = lastUsageMinutes

    /**
     * [FIX-2026-09-12 二次修正 · 真机重大 bug] 与首页倒计时**同源**的今日已用秒数。
     *
     * 事故：首页卡片用「估算值」（含交互增量），限额判定用「系统权威用量」——两个时钟不同源，
     * 于是出现「卡片显示已超时、但没锁屏」（真机 18:2x 报障）。
     * 现在判定与展示一律走 [UsageStatsHelperTV.sampleDisplayUsedSeconds]，
     * 并且该估算已加领先上限（最多领先权威值 60s）⇒ 卡片归零与锁屏必然同步。
     */
    private fun effectiveUsedSeconds(context: Context): Int =
        runCatching { UsageStatsHelperTV.sampleDisplayUsedSeconds(context).first }.getOrDefault(0)

    /**
     * 设置限额并重新计算
     *
     * [FIX-2026-09-12] 增加可选的 context：家长把限额**调高到当前有效用量之上**时，
     * 除了改状态位还要真正退出锁屏（原来只调 recalculateState() 改内存状态，
     * 锁屏页与整机拦截都不会退出 → Web 端「调整限额」看着毫无反应）。
     */
    fun setDailyLimit(minutes: Int, context: Context? = null) {
        dailyLimitMinutes = maxOf(0, minutes)
        recalculateState()
        if (context != null && lockedState) {
            val usedSeconds = effectiveUsedSeconds(context)
            lastUsageSeconds = usedSeconds
            lastUsageMinutes = usedSeconds / 60
            if (usedSeconds < dailyLimitMinutes * 60) {
                AppLogTV.i("Timeout", "限额调整为 $dailyLimitMinutes 分钟（已用 $lastUsageMinutes 分钟）→ 解除锁屏")
                unlock(context)
            }
        }
    }

    /**
     * 开始使用（用户打开电视应用）
     */
    fun onAppLaunched(context: Context) {
        currentState = State.RUNNING
        usageStartedAt = System.currentTimeMillis()
        // 从数据库加载今日已用时长
        scope.launch {
            // 统计今日用量（已剔除本应用自身，避免锁屏自我计时 → 越锁越超）
            val usedSeconds = effectiveUsedSeconds(context)
            lastUsageSeconds = usedSeconds
            lastUsageMinutes = usedSeconds / 60
            remainingMinutesVal = maxOf(0, dailyLimitMinutes - lastUsageMinutes)
            recalculateState()
        }
    }

    /**
     * 锁屏页收到的「自行关闭」广播（见 dismissLockScreen）
     */
    const val ACTION_DISMISS_LOCK = "com.xiaopacai.tvos.action.DISMISS_LOCK"

    /**
     * 刷新使用时长并重新计算状态
     *
     * [FIX-2026-09-12] 原实现开头 `if (lockedState) return`：一旦上锁就**再也不评估**，
     * 家长「重置当日限额 / 提高限额」后电视端没有任何反应（永远解不开）。
     * 现在锁屏态下也重新评估：有效用量回落到限额内就自动解除（重置/调高/跨零点/撤销策略）。
     */
    fun refreshUsage(context: Context) {
        // 家长正在验证时不重新上锁/不盖住验证页
        if (verificationInProgress) return

        // [TASK-GUARD-TOGGLE-V1] 家长已「停用管控」→ 不评估、不锁屏，并确保当前锁屏（含 kiosk）退出
        if (!guardEnabled) {
            if (lockedState) unlock(context)
            return
        }

        scope.launch {
            try {
                // 【A3】就寝时段优先：到点直接锁（与当日限额无关；紧急解除期间不锁）
                if (isBedtimeNow() && !EmergencyReleaseServiceTV.isActive(context)) {
                    lockReason = "就寝时段 " + bedtimeLabel
                    AppLogTV.w("Timeout", "进入就寝时段 $bedtimeLabel → 锁屏")
                    if (!lockedState) lockScreen(context)
                    return@launch
                }
                // [FIX-2026-09-12 二次修正] 与首页倒计时**同一个数**（估算含领先上限 60s）：
                // 之前这里读 queryTodayTotalMinutes（权威值），与卡片用的估算不同源，
                // 导致真机「卡片已超时、就是不锁屏」。判定精度也提到秒级。
                val usedSeconds = effectiveUsedSeconds(context)
                lastUsageSeconds = usedSeconds
                val todayUsage = usedSeconds / 60
                lastUsageMinutes = todayUsage
                remainingMinutesVal = maxOf(0, dailyLimitMinutes - todayUsage)

                if (usedSeconds >= dailyLimitMinutes * 60) {
                    // 紧急解除期间不锁屏；解除到期后 isActive 自动为 false，管控自动恢复
                    if (EmergencyReleaseServiceTV.isActive(context)) {
                        currentState = State.RUNNING
                    } else {
                        currentState = State.LOCKED
                        // 已锁定时不重复拉起锁屏页（避免每 30s 重拉把家长验证页顶掉）
                        if (!lockedState) lockScreen(context)
                    }
                } else {
                    currentState = if (remainingMinutesVal <= 10) State.EXPIRING else State.RUNNING
                    if (lockedState) {
                        AppLogTV.i("Timeout", "有效用量 $todayUsage 分钟 < 限额 $dailyLimitMinutes 分钟 → 自动解除锁屏")
                        unlock(context)
                    } else {
                        // 状态是未锁但锁屏页还留着（进程重启/跨零点）→ 顺手关掉
                        dismissLockScreen(context)
                    }
                }
            } catch (e: Exception) {
                // 静默处理统计错误
            }
        }
    }

    /**
     * 家长解除锁定（含远程重置/调高限额触发的自动解除）
     */
    fun unlock(context: Context) {
        currentState = State.IDLE
        lockedState = false
        lockReason = ""
        AppLogTV.i("Timeout", "解除锁屏，恢复使用")
        usageStartedAt = 0
        // [FIX-2026-09-12 · 用户口径] 解锁后恢复「任何界面都算时间」的累计
        UsageStatsHelperTV.pauseSelfScreenAccrual = false
        // 【A7/A14】解除整机拦截（清空锁任务白名单 = kiosk 强制退出）
        DeviceHardLock.release(context)
        // 移除锁屏覆盖层
        removeOverlay()
        // [FIX-2026-09-12] 关闭锁屏页：Service 侧没有 Activity 引用，靠应用内广播让它自己 finish
        // （原来只有调用方是 Activity 时才会 finish → 远程重置时锁屏页会一直挂在前台）
        dismissLockScreen(context)
    }

    /**
     * 通知锁屏页自行关闭（无锁屏页时是空操作）
     */
    fun dismissLockScreen(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ACTION_DISMISS_LOCK).setPackage(context.packageName))
        }
    }

    /**
     * 每日凌晨重置 / 开机自检：只把**本地内存计数**与缓存归零（不加偏移基线！）
     *
     * 注意：开机、守护服务启动都会调用它（BootReceiver / TVGuardianForegroundService），
     * 所以这里**绝不能**记重置基线 —— 否则孩子重启电视就能把当日用量清零。
     * 家长的远程重置走 [applyRemoteReset]。
     */
    fun resetDaily() {
        currentState = State.IDLE
        lastUsageMinutes = 0
        remainingMinutesVal = dailyLimitMinutes
        lockedState = false
        UsageStatsHelperTV.clearCache()
    }

    /**
     * 家长远程重置当日用量（P2P limit_reset / 心跳 commands.reset_daily_usage）
     *
     * [FIX-2026-09-12] 与手机端同语义：把重置当刻的今日原始用量记为偏移（此后按重置后口径计时），
     * 并真正解除锁屏 + 退出整机拦截 —— 原来只改内存标志，屏幕会一直停在锁屏，
     * 且下一次重算又从系统读出同一个大数 → 立刻重新判超限。
     */
    fun applyRemoteReset(context: Context) {
        val raw = UsageStatsHelperTV.rawTodayTotalSeconds(context)
        UsageStatsHelperTV.applyLimitReset(context, raw)
        currentState = State.IDLE
        lastUsageMinutes = 0
        remainingMinutesVal = dailyLimitMinutes
        AppLogTV.i("Timeout", "收到重置当日用量：基线 ${raw / 60} 分钟（重置后重新计时）→ 解除锁屏")
        unlock(context)
    }

    /**
     * 显示锁屏覆盖（启动 LockOverlayService 前台服务）
     * 由 Service 直接 addView 到 WindowManager（TYPE_APPLICATION_OVERLAY），纯 View 实现
     * HOME 键不会绕过（非 Activity，无 Back Stack）
     */
    fun lockScreen(context: Context) {
        // [TASK-GUARD-TOGGLE-V1] 管控已停用 → 一律不上锁（纵深防御：调用方漏判也不会误锁）
        if (!guardEnabled) {
            AppLogTV.i("Timeout", "管控已停用，忽略本次上锁请求")
            return
        }
        currentState = State.LOCKED
        lockedState = true
        if (lockReason.isBlank()) lockReason = "今日可用时长已用完"
        AppLogTV.w("Timeout", "锁屏（原因：$lockReason）")
        // [FIX-2026-09-12 · 用户口径] 上锁后停止把「自家界面时间」计入用量：
        // 锁屏页不是看电视的界面，超限后继续累加也没有意义（否则锁着也一直涨，家长端数字会越飘越离谱）
        UsageStatsHelperTV.pauseSelfScreenAccrual = true
        // 【A7/A14】设备所有者时同时上「整机拦截」：锁任务白名单只留本应用
        //（真正的 startLockTask() 由锁屏 Activity 执行 —— Service 进不了锁任务模式）
        DeviceHardLock.engage(context)

        // [BUGFIX-2026-09-12] 原来走 LockOverlayService（WindowManager 覆盖层），
        // 但它在 Android 5.1 上 addView(TYPE_APPLICATION_OVERLAY) 会被拒（该窗口类型 API 26+ 才有），
        // 服务 onCreate 直接抛 BadTokenException → **整个应用进程被杀**，而触发点正是「限额刚用超」，
        // 等于最关键的时刻守护全灭（真机日志：Fatal Exception ... LockOverlayService.onCreate）。
        // 现统一改用 BlockOverlayActivity：① 是 Activity，不受窗口权限限制 ② 有遥控器 D-pad 交互
        // ③ 唯一能调 startLockTask() 进 kiosk 的形态 ④ 就是家长验收过的那套全红锁屏 UI。
        val intent = Intent(context, com.xiaopacai.tvos.ui.lock.BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { AppLogTV.e("Timeout", "拉起锁屏页失败: ${it.message}") }
    }

    /**
     * 移除锁屏覆盖（停止 LockOverlayService）
     */
    @JvmStatic
    fun removeOverlay() {
        val service = LockOverlayService.getInstance()
        service?.let {
            it.onDestroy()
        }
    }

    /**
     * 重新计算状态
     */
    private fun recalculateState() {
        if (lastUsageMinutes >= dailyLimitMinutes) {
            // 已超限额，准备锁屏
            if (dailyLimitMinutes > 0) {
                currentState = State.LOCKED
            } else {
                // 限额为 0 = 完全禁用
                currentState = State.LOCKED
            }
        } else if (remainingMinutesVal <= 10) {
            currentState = State.EXPIRING
        } else {
            currentState = State.RUNNING
        }
    }

    /**
     * 检查是否应显示超时提醒（调用频率：每分钟一次）
     */
    fun shouldShowReminder(context: Context): Boolean {
        return currentState == State.EXPIRING
    }
}
