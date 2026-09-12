// [android-tv] 核心模块 — 锁屏状态机（纯逻辑层）
// 小趴菜 TVOS 版 — TimeoutExecutorTV 状态机逻辑提取，支持单元测试

package com.xiaopacai.tvos.util

/**
 * 锁屏状态机 — 纯逻辑层（无 Android 依赖）
 *
 * 状态转换:
 *  IDLE → RUNNING（开始使用） → EXPIRING（即将到期，提示） → LOCKED（超时锁屏）
 *  LOCKED → IDLE（家长解除） / IDLE（次日重置）
 *
 * 状态判定规则:
 *  - 已用时长 >= 每日限额 → LOCKED
 *  - 剩余时长 <= 10 分钟 → EXPIRING
 *  - 已用时长 > 0 → RUNNING
 *  - 其他 → IDLE
 *
 * 使用示例:
 *  val sm = TimeoutStateMachine()
 *  sm.setUsedMinutes(0)
 *  sm.state shouldBe State.RUNNING
 */
class TimeoutStateMachine {

    /** 超时状态枚举 */
    enum class State {
        IDLE,         // 空闲（未开始使用或未达限额）
        RUNNING,      // 使用中（已启动 TV 应用，计时中）
        EXPIRING,     // 即将到期（剩余 ≤ 10 分钟，给予提示）
        LOCKED        // 已锁定（超时，显示锁屏覆盖）
    }

    /** 操作结果 */
    sealed class Result {
        data class StateChange(val newState: State, val wasLocked: Boolean) : Result()
        data class Error(val message: String) : Result()
    }

    /** 当前状态 */
    var state: State = State.IDLE
        private set

    /** 是否已锁定 */
    var isLocked: Boolean = false
        private set

    /** 今日已用分钟数 */
    var usedMinutes: Int = 0
        set(value) {
            if (value >= 0) field = value
        }

    /** 每日限额（分钟） */
    var dailyLimit: Int = 60
        private set

    /** 剩余可用分钟数（计算属性） */
    val remainingMinutes: Int
        get() = maxOf(0, dailyLimit - usedMinutes)

    /** 重置状态机到初始状态 */
    fun reset() {
        state = State.IDLE
        isLocked = false
        usedMinutes = 0
        dailyLimit = 60
    }

    /**
     * 设置每日限额并重新计算状态
     * @return 状态变化结果
     */
    fun setDailyLimit(minutes: Int): Result {
        if (minutes < 0) return Result.Error("限额不能为负数")
        dailyLimit = minutes
        return recalculate()
    }

    /**
     * 设置已用时长并重新计算状态
     * @return 状态变化结果
     */
    fun setUsedMinutes(minutes: Int): Result {
        if (minutes < 0) return Result.Error("已用时长不能为负数")
        usedMinutes = minutes
        return recalculate()
    }

    /**
     * 同时设置限额和已用时长
     */
    fun update(dailyLimitMinutes: Int, usedMinutesInt: Int): Result {
        if (dailyLimitMinutes < 0) return Result.Error("限额不能为负数")
        if (usedMinutesInt < 0) return Result.Error("已用时长不能为负数")
        dailyLimit = dailyLimitMinutes
        usedMinutes = usedMinutesInt
        return recalculate()
    }

    /**
     * 切换到 RUNNING 状态（开始使用）
     */
    fun startUsing(): Result {
        if (state == State.LOCKED) {
            return Result.Error("设备已锁定，请先解除锁定")
        }
        state = State.RUNNING
        return Result.StateChange(State.RUNNING, false)
    }

    /**
     * 切换到 IDLE 状态（停止使用）
     */
    fun stopUsing(): Result {
        if (state != State.LOCKED && usedMinutes < dailyLimit) {
            state = State.IDLE
            return Result.StateChange(State.IDLE, false)
        }
        // 如果已锁定或已超时，停止使用不会自动解锁
        return Result.Error("无法停止使用（状态: $state）")
    }

    /**
     * 家长解除锁定
     */
    fun unlock(): Result {
        state = State.IDLE
        isLocked = false
        return Result.StateChange(State.IDLE, false)
    }

    /**
     * 强制锁定（用于外部触发）
     */
    fun forceLock(): Result {
        state = State.LOCKED
        isLocked = true
        return Result.StateChange(State.LOCKED, true)
    }

    /**
     * 每日重置（从 WorkManager 触发）
     */
    fun resetDaily(): Result {
        state = State.IDLE
        isLocked = false
        usedMinutes = 0
        return Result.StateChange(State.IDLE, false)
    }

    /**
     * 重新计算状态（核心逻辑）
     */
    private fun recalculate(): Result {
        val newState = when {
            dailyLimit == 0 -> State.LOCKED              // 限额为 0 = 完全禁用
            usedMinutes >= dailyLimit -> State.LOCKED     // 已超限额
            remainingMinutes <= 10 -> State.EXPIRING      // 即将到期
            usedMinutes > 0 -> State.RUNNING              // 使用中
            else -> State.IDLE                            // 未开始使用
        }

        val willLock = newState == State.LOCKED
        state = newState
        isLocked = willLock
        return Result.StateChange(newState, willLock)
    }

    /**
     * 检查是否应显示超时提醒
     */
    fun shouldShowReminder(): Boolean {
        return state == State.EXPIRING
    }

    /**
     * 检查是否可以豁免（家长授权后）
     */
    fun canExempt(): Boolean {
        return state == State.LOCKED
    }
}
