package com.xiaopacai.child.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TASK-HARDENING-V1.1.1] Bug2-A 秒级倒计时纯逻辑用例：
 *
 * 剩余 = 今日限额 −（最近采集已用 + 距最近采集的交互增量）。
 * 覆盖：交互增量 / 熄屏不累计 / 负值归零 / 未采集不虚构已用 /
 * 采集失效判定阈值 / HH:MM:SS 格式化。
 */
class CountdownLogicTest {

    private val limit = 120 * 60_000L  // 2 小时限额

    @Test
    fun remaining_basic() {
        // 最近采集：已用 30 分钟；5 秒后（亮屏交互中）
        val used = 30 * 60_000L
        val remain = UsageStatsCollector.computeRemainingMillis(
            limitMillis = limit,
            lastUsedMillis = used,
            lastCollectAtMs = 1_000_000L,
            nowMs = 1_000_000L + 5_000L,
            screenInteractive = true
        )
        assertEquals(90 * 60_000L - 5_000L, remain)
    }

    @Test
    fun remaining_screenOff_noDelta() {
        // 熄屏：距采集的增量不计入使用（与 UsageStats 口径一致，避免夜间虚减）
        val used = 30 * 60_000L
        val remain = UsageStatsCollector.computeRemainingMillis(
            limitMillis = limit,
            lastUsedMillis = used,
            lastCollectAtMs = 1_000_000L,
            nowMs = 1_000_000L + 55_000L,  // 熄屏 55 秒
            screenInteractive = false
        )
        assertEquals(90 * 60_000L, remain)
    }

    @Test
    fun remaining_clampsToZero() {
        // 增量超过剩余 → 归零（归零即触发 Bug2-B 立即锁定）
        val remain = UsageStatsCollector.computeRemainingMillis(
            limitMillis = 60_000L,
            lastUsedMillis = 58_000L,
            lastCollectAtMs = 1_000_000L,
            nowMs = 1_000_000L + 30_000L,
            screenInteractive = true
        )
        assertEquals(0L, remain)
    }

    @Test
    fun remaining_neverCollected_doesNotInventUsage() {
        // 尚未完成首次采集：不虚构已用，返回完整限额（首采 ≤60s 内完成）
        val remain = UsageStatsCollector.computeRemainingMillis(
            limitMillis = limit,
            lastUsedMillis = 0L,
            lastCollectAtMs = 0L,
            nowMs = 5_000L,
            screenInteractive = true
        )
        assertEquals(limit, remain)
    }

    @Test
    fun remaining_noLimit_isZero() {
        assertEquals(0L, UsageStatsCollector.computeRemainingMillis(
            limitMillis = 0L, lastUsedMillis = 0L,
            lastCollectAtMs = 1_000L, nowMs = 2_000L, screenInteractive = true))
    }

    @Test
    fun stale_judgement() {
        // 3 个采集周期（180s）内 = 未失效；超过 = 失效 → UI 显示「守护失效」
        assertFalse(UsageStatsCollector.isCollectStale(
            lastCollectAtMs = 1_000_000L, nowMs = 1_000_000L + 179_000L))
        assertTrue(UsageStatsCollector.isCollectStale(
            lastCollectAtMs = 1_000_000L, nowMs = 1_000_000L + 181_000L))
    }

    @Test
    fun formatHms_various() {
        assertEquals("00:00:00", UsageStatsCollector.formatHms(0))
        assertEquals("00:00:05", UsageStatsCollector.formatHms(5_000L))
        assertEquals("02:03:05", UsageStatsCollector.formatHms((2 * 3600 + 3 * 60 + 5) * 1000L))
        assertEquals("25:00:00", UsageStatsCollector.formatHms(25 * 3600_000L))  // 超 24 小时如实显示
    }
}
