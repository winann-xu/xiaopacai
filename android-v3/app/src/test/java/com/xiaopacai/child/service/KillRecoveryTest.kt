package com.xiaopacai.child.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TASK-MILESTONE-V3] 需求 5：杀进程检测纯函数单元测试
 *
 * isKillRecovery：心跳间隔超过 5 分钟即判定进程曾被结束；
 * 首次启动（无历史心跳）与正常重启（间隔在阈值内）不误报。
 */
class KillRecoveryTest {

    private val now = 1_000_000_000_000L

    @Test
    fun gapOverThreshold_returnsTrue() {
        assertTrue(GuardianForegroundService.isKillRecovery(
            now - GuardianForegroundService.KILL_GAP_MS - 1_000L, now))
    }

    @Test
    fun gapWithinThreshold_returnsFalse() {
        assertFalse(GuardianForegroundService.isKillRecovery(
            now - GuardianForegroundService.KILL_GAP_MS + 1_000L, now))
    }

    @Test
    fun noPreviousHeartbeat_firstLaunch_returnsFalse() {
        assertFalse(GuardianForegroundService.isKillRecovery(0L, now))
    }

    @Test
    fun negativeLastHeartbeat_returnsFalse() {
        assertFalse(GuardianForegroundService.isKillRecovery(-1L, now))
    }

    @Test
    fun exactlyAtThreshold_returnsFalse() {
        assertFalse(GuardianForegroundService.isKillRecovery(
            now - GuardianForegroundService.KILL_GAP_MS, now))
    }
}
