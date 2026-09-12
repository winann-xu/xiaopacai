// [android-tv] 测试 — TimeoutStateMachine 状态机完整测试
// 小趴菜 TVOS 版 — 锁屏状态机单元测试（核心模块 1/3）
// 覆盖：C3（单元测试全绿）、P3（锁屏状态机）

package com.xiaopacai.tvos.util

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TimeoutStateMachine 完整单元测试
 * 
 * 验证状态机：
 * 1. 初始状态为 IDLE
 * 2. 限额变更计算正确
 * 3. 状态转换完整路径：IDLE → RUNNING → EXPIRING → LOCKED
 * 4. 解锁/重置逻辑正确
 * 5. 边界条件处理正确
 * 6. 防篡改逻辑
 */
class TimeoutStateMachineTest {

    private val sm = TimeoutStateMachine()

    @Before
    fun setUp() {
        sm.reset()
    }

    @After
    fun tearDown() {
        sm.reset()
    }

    // ==================== 初始状态 ====================

    @Test
    fun testInitialState() {
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)
        assertFalse(sm.isLocked)
        assertEquals(0, sm.usedMinutes)
        // remainingMinutes 通过 dailyLimit - usedMinutes 计算
    }

    // ==================== 限额设置 ====================

    @Test
    fun testSetDailyLimitDefault() {
        // 默认限额 60 分钟
        assertEquals(60, sm.dailyLimit)
    }

    @Test
    fun testSetDailyLimitValid() {
        val result = sm.setDailyLimit(30)
        assertTrue(result is TimeoutStateMachine.Result.StateChange)
    }

    @Test
    fun testSetDailyLimitZero() {
        // 限额为 0 = 完全禁用 → 直接进入 LOCKED
        val result = sm.setDailyLimit(0)
        assertEquals(TimeoutStateMachine.State.LOCKED, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testSetDailyLimitNegative() {
        val result = sm.setDailyLimit(-1)
        assertTrue(result is TimeoutStateMachine.Result.Error)
        assertEquals("限额不能为负数", (result as TimeoutStateMachine.Result.Error).message)
    }

    @Test
    fun testSetDailyLimitLarge() {
        val result = sm.setDailyLimit(1440) // 24 小时
        assertTrue(result is TimeoutStateMachine.Result.StateChange)
    }

    // ==================== 使用时长设置 ====================

    @Test
    fun testSetUsedMinutesZero() {
        val result = sm.setUsedMinutes(0)
        assertTrue(result is TimeoutStateMachine.Result.StateChange)
        assertEquals(TimeoutStateMachine.State.IDLE, (result as TimeoutStateMachine.Result.StateChange).newState)
    }

    @Test
    fun testSetUsedMinutesUnderLimit() {
        sm.setDailyLimit(60)
        val result = sm.setUsedMinutes(30)
        assertEquals(TimeoutStateMachine.State.RUNNING, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertEquals(30, sm.remainingMinutes)
    }

    @Test
    fun testSetUsedMinutesExactLimit() {
        sm.setDailyLimit(60)
        val result = sm.setUsedMinutes(60)
        assertEquals(TimeoutStateMachine.State.LOCKED, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testSetUsedMinutesOverLimit() {
        sm.setDailyLimit(60)
        val result = sm.setUsedMinutes(65)
        assertEquals(TimeoutStateMachine.State.LOCKED, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testSetUsedMinutesNegative() {
        val result = sm.setUsedMinutes(-1)
        assertTrue(result is TimeoutStateMachine.Result.Error)
    }

    // ==================== 状态转换路径 ====================

    @Test
    fun testIdleToRunning() {
        sm.setDailyLimit(60)
        val result = sm.startUsing()
        assertTrue(result is TimeoutStateMachine.Result.StateChange)
        assertEquals(TimeoutStateMachine.State.RUNNING, (result as TimeoutStateMachine.Result.StateChange).newState)
    }

    @Test
    fun testRunningToExpiring() {
        // 使用 55 分钟（剩余 5 分钟 → EXPIRING）
        sm.setDailyLimit(60)
        val result = sm.setUsedMinutes(55)
        assertEquals(TimeoutStateMachine.State.EXPIRING, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertEquals(5, sm.remainingMinutes)
    }

    @Test
    fun testRunningToExpiringBoundary() {
        // 使用 50 分钟（剩余 10 分钟 → EXPIRING）
        sm.setDailyLimit(60)
        val result = sm.setUsedMinutes(50)
        assertEquals(TimeoutStateMachine.State.EXPIRING, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertEquals(10, sm.remainingMinutes)
    }

    @Test
    fun testRunningToExpiringNotYet() {
        // 使用 30 分钟（剩余 30 分钟 → RUNNING）
        sm.setDailyLimit(60)
        val result = sm.setUsedMinutes(30)
        assertEquals(TimeoutStateMachine.State.RUNNING, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertEquals(30, sm.remainingMinutes)
    }

    @Test
    fun testExpiringToLocked() {
        // 使用 61 分钟（超过限额 → LOCKED）
        sm.setDailyLimit(60)
        val result = sm.setUsedMinutes(61)
        assertEquals(TimeoutStateMachine.State.LOCKED, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testLockedToIdleUnlock() {
        sm.setDailyLimit(60)
        sm.setUsedMinutes(65)
        assertTrue(sm.isLocked)

        val result = sm.unlock()
        assertTrue(result is TimeoutStateMachine.Result.StateChange)
        assertEquals(TimeoutStateMachine.State.IDLE, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertFalse(sm.isLocked)
    }

    @Test
    fun testLockedToIdleResetDaily() {
        sm.setDailyLimit(60)
        sm.setUsedMinutes(65)
        assertTrue(sm.isLocked)

        val result = sm.resetDaily()
        assertTrue(result is TimeoutStateMachine.Result.StateChange)
        assertEquals(TimeoutStateMachine.State.IDLE, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertEquals(0, sm.usedMinutes)
        assertFalse(sm.isLocked)
    }

    // ==================== 特殊操作 ====================

    @Test
    fun testForceLock() {
        val result = sm.forceLock()
        assertEquals(TimeoutStateMachine.State.LOCKED, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testStopUsingWhenIdle() {
        val result = sm.stopUsing()
        // IDLE 状态下，如果未超时，停止使用返回 IDLE
        assertTrue(result is TimeoutStateMachine.Result.StateChange)
        assertEquals(TimeoutStateMachine.State.IDLE, (result as TimeoutStateMachine.Result.StateChange).newState)
    }

    @Test
    fun testStartUsingWhenLocked() {
        sm.forceLock()
        val result = sm.startUsing()
        assertTrue(result is TimeoutStateMachine.Result.Error)
        assertEquals("设备已锁定，请先解除锁定", (result as TimeoutStateMachine.Result.Error).message)
    }

    @Test
    fun testShouldShowReminder() {
        // EXPIRING 状态应显示提醒
        sm.setDailyLimit(60)
        sm.setUsedMinutes(55)
        assertTrue(sm.shouldShowReminder())

        // RUNNING 状态不显示提醒
        sm.setUsedMinutes(30)
        assertFalse(sm.shouldShowReminder())

        // LOCKED 状态不显示提醒
        sm.setUsedMinutes(65)
        assertFalse(sm.shouldShowReminder())
    }

    @Test
    fun testCanExempt() {
        // LOCKED 状态可以豁免
        sm.forceLock()
        assertTrue(sm.canExempt())

        // RUNNING 状态不显示豁免入口
        sm.reset()
        sm.setDailyLimit(60)
        sm.setUsedMinutes(30)
        assertFalse(sm.canExempt())
    }

    // ==================== 边界条件 ====================

    @Test
    fun testZeroLimitWithZeroUsage() {
        // 限额 0，使用 0 → LOCKED（完全禁用）
        val result = sm.update(0, 0)
        assertEquals(TimeoutStateMachine.State.LOCKED, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testZeroLimitWithUsage() {
        // 限额 0，使用 30 → LOCKED
        val result = sm.update(0, 30)
        assertEquals(TimeoutStateMachine.State.LOCKED, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testLargeDailyLimit() {
        // 限额 1440 分钟（24 小时），使用 30 → RUNNING
        val result = sm.update(1440, 30)
        assertEquals(TimeoutStateMachine.State.RUNNING, (result as TimeoutStateMachine.Result.StateChange).newState)
        assertEquals(1410, sm.remainingMinutes)
    }

    @Test
    fun testRemainingMinutesNeverNegative() {
        // 使用超过限额，剩余应为 0
        val result = sm.update(60, 100)
        assertEquals(0, sm.remainingMinutes)
        assertEquals(TimeoutStateMachine.State.LOCKED, (result as TimeoutStateMachine.Result.StateChange).newState)
    }

    @Test
    fun testMultipleResetDaily() {
        sm.setDailyLimit(60)
        sm.setUsedMinutes(65)
        assertTrue(sm.isLocked)

        sm.resetDaily()
        assertFalse(sm.isLocked)

        sm.setUsedMinutes(30)
        assertEquals(TimeoutStateMachine.State.RUNNING, sm.state)

        sm.resetDaily()
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)
        assertEquals(0, sm.usedMinutes)
    }

    // ==================== 综合场景 ====================

    @Test
    fun testFullDayUsageCycle() {
        val sm2 = TimeoutStateMachine()

        // 早晨：开始使用
        sm2.update(60, 0)
        sm2.startUsing()
        assertEquals(TimeoutStateMachine.State.RUNNING, sm2.state)

        // 上午：用了 15 分钟
        sm2.setUsedMinutes(15)
        assertEquals(TimeoutStateMachine.State.RUNNING, sm2.state)
        assertEquals(45, sm2.remainingMinutes)

        // 中午：用了 52 分钟（剩余 8 分钟 → EXPIRING，≤ 10 分钟阈值）
        sm2.setUsedMinutes(52)
        assertEquals(TimeoutStateMachine.State.EXPIRING, sm2.state)
        assertEquals(8, sm2.remainingMinutes)

        // 下午：用了 58 分钟
        sm2.setUsedMinutes(58)
        assertEquals(TimeoutStateMachine.State.EXPIRING, sm2.state)
        assertEquals(2, sm2.remainingMinutes)

        // 晚上：用了 62 分钟（超时锁定）
        sm2.setUsedMinutes(62)
        assertEquals(TimeoutStateMachine.State.LOCKED, sm2.state)
        assertTrue(sm2.isLocked)

        // 家长解除锁定
        sm2.unlock()
        assertEquals(TimeoutStateMachine.State.IDLE, sm2.state)
        assertFalse(sm2.isLocked)

        // 次日重置
        sm2.resetDaily()
        assertEquals(TimeoutStateMachine.State.IDLE, sm2.state)
        assertEquals(0, sm2.usedMinutes)
        assertEquals(60, sm2.remainingMinutes)
    }

    @Test
    fun testRapidStateChanges() {
        // 快速状态切换不应出错
        for (i in 1..100) {
            sm.reset()
            sm.update(60, i % 70)
            sm.unlock()
            sm.forceLock()
            sm.unlock()
        }
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)
        assertFalse(sm.isLocked)
    }
}
