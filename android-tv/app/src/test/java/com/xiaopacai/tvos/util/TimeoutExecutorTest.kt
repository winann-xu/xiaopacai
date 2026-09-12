// [android-tv] 测试 — TimeoutExecutorTV 与 TimeoutStateMachine 单元测试
// 小趴菜 TVOS 版 — 超时执行器状态机测试（整合版）

package com.xiaopacai.tvos.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TimeoutExecutorTV 与 TimeoutStateMachine 整合单元测试
 * 
 * 验证：
 * 1. TimeoutStateMachine 独立状态机逻辑
 * 2. TimeoutExecutorTV 状态属性访问
 * 3. PIN 验证逻辑
 * 4. 加密工具函数
 */
class TimeoutExecutorTest {

    private lateinit var sm: TimeoutStateMachine
    private lateinit var executor: TimeoutExecutorTV

    @Before
    fun setUp() {
        sm = TimeoutStateMachine()
        executor = TimeoutExecutorTV
    }

    // ==================== TimeoutStateMachine 测试 ====================

    @Test
    fun testStateMachineInitialState() {
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)
        assertFalse(sm.isLocked)
        assertEquals(0, sm.usedMinutes)
    }

    @Test
    fun testStateMachineSetDailyLimit() {
        sm.setDailyLimit(30)
        // dailyLimit = 30, usedMinutes = 0 → IDLE
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)
    }

    @Test
    fun testStateMachineZeroLimit() {
        // 限额为 0 = 完全禁用 → LOCKED
        sm.setDailyLimit(0)
        assertEquals(TimeoutStateMachine.State.LOCKED, sm.state)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testStateMachineStartUsing() {
        sm.setDailyLimit(60)
        sm.startUsing()
        assertEquals(TimeoutStateMachine.State.RUNNING, sm.state)
    }

    @Test
    fun testStateMachineExpiringState() {
        // 剩余 10 分钟 → EXPIRING
        sm.setDailyLimit(60)
        sm.setUsedMinutes(50)
        assertEquals(TimeoutStateMachine.State.EXPIRING, sm.state)
        assertEquals(10, sm.remainingMinutes)
    }

    @Test
    fun testStateMachineLockedState() {
        sm.setDailyLimit(60)
        sm.setUsedMinutes(65)
        assertEquals(TimeoutStateMachine.State.LOCKED, sm.state)
        assertTrue(sm.isLocked)
    }

    @Test
    fun testStateMachineUnlock() {
        sm.setDailyLimit(60)
        sm.setUsedMinutes(65)
        sm.unlock()
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)
        assertFalse(sm.isLocked)
    }

    @Test
    fun testStateMachineResetDaily() {
        sm.setDailyLimit(60)
        sm.setUsedMinutes(65)
        sm.resetDaily()
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)
        assertEquals(0, sm.usedMinutes)
    }

    @Test
    fun testStateMachineFullDayCycle() {
        // 完整一天使用周期
        sm.setDailyLimit(60)

        // 早晨开始使用
        sm.startUsing()
        assertEquals(TimeoutStateMachine.State.RUNNING, sm.state)

        // 上午使用 15 分钟
        sm.setUsedMinutes(15)
        assertEquals(TimeoutStateMachine.State.RUNNING, sm.state)
        assertEquals(45, sm.remainingMinutes)

        // 中午 52 分钟（剩余 8 分钟 → EXPIRING）
        sm.setUsedMinutes(52)
        assertEquals(TimeoutStateMachine.State.EXPIRING, sm.state)
        assertEquals(8, sm.remainingMinutes)

        // 晚上超时 65 分钟（锁定）
        sm.setUsedMinutes(65)
        assertEquals(TimeoutStateMachine.State.LOCKED, sm.state)
        assertTrue(sm.isLocked)

        // 家长解除
        sm.unlock()
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)

        // 次日重置
        sm.resetDaily()
        assertEquals(TimeoutStateMachine.State.IDLE, sm.state)
        assertEquals(0, sm.usedMinutes)
    }

    // ==================== TimeoutExecutorTV 测试 ====================

    @Test
    fun testExecutorInitialState() {
        // 初始状态应为 IDLE
        assertEquals(TimeoutExecutorTV.State.IDLE, executor.state)
    }

    // ==================== PIN 验证测试 ====================

    @Test
    fun testValidPinFormat() {
        // 4-6 位数字
        assertTrue(EncryptionHelper.isValidPin("1234"))
        assertTrue(EncryptionHelper.isValidPin("123456"))
        assertTrue(EncryptionHelper.isValidPin("0000"))

        // 无效格式
        assertFalse(EncryptionHelper.isValidPin("123"))       // 少于 4 位
        assertFalse(EncryptionHelper.isValidPin("1234567"))   // 多于 6 位
        assertFalse(EncryptionHelper.isValidPin("12ab"))      // 非数字
        assertFalse(EncryptionHelper.isValidPin(""))          // 空字符串
    }

    // ==================== 加密工具测试 ====================

    @Test
    fun testSha256Consistency() {
        val hash1 = EncryptionHelper.sha256("test")
        val hash2 = EncryptionHelper.sha256("test")
        assertEquals(hash1, hash2)

        val hash3 = EncryptionHelper.sha256("other")
        assertNotEquals(hash1, hash3)
    }

    @Test
    fun testSha256OutputLength() {
        val hash = EncryptionHelper.sha256("test")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testBytesToBase64RoundTrip() {
        val input = byteArrayOf(1, 2, 3, 4)
        val base64 = EncryptionHelper.bytesToBase64(input)
        val output = EncryptionHelper.base64ToBytes(base64)
        assertArrayEquals(input, output)
    }

    @Test
    fun testGenerateDefaultPin() {
        val pin = EncryptionHelper.generateDefaultPin()
        assertEquals(6, pin.length)
        assertTrue(pin.all { it.isDigit() })
    }
}
