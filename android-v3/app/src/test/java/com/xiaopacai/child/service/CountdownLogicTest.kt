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

    // ==================== [FIX-COUNTDOWN] 分钟粒度回跳修复 ====================

    @Test
    fun interactiveDelta_cappedAtCollectInterval() {
        // 距锚点超过一个采集周期 → 增量封顶 60s（防空闲/熄屏漂移）
        assertEquals(60_000L, UsageStatsCollector.interactiveDeltaMs(1_000_000L, 1_000_000L + 100_000L, true))
        // 熄屏 → 0
        assertEquals(0L, UsageStatsCollector.interactiveDeltaMs(1_000_000L, 1_000_000L + 100_000L, false))
        // 无锚点 → 0
        assertEquals(0L, UsageStatsCollector.interactiveDeltaMs(0L, 1_000_000L + 100_000L, true))
        // 正常子分钟增量
        assertEquals(5_000L, UsageStatsCollector.interactiveDeltaMs(1_000_000L, 1_000_000L + 5_000L, true))
    }

    @Test
    fun reconcile_carriesInteractiveDelta_whenCollectedStuck() {
        // 采集分钟值未进位但屏幕交互：估算延续交互增量（60s）并推进锚点 → 倒计时继续走（不冻结）
        val r = UsageStatsCollector.reconcileCollect(
            prevUsedMs = 30 * 60_000L,
            prevCollectedMs = 30 * 60_000L,
            prevAnchorMs = 1_000_000L,
            nowMs = 1_000_000L + 60_000L,
            collectedUsedMs = 30 * 60_000L,
            screenInteractive = true)
        assertEquals(31 * 60_000L, r.first)
        assertTrue(r.second)
    }

    @Test
    fun reconcile_screenOff_noCarry() {
        // 熄屏不累计：估算保持旧值、锚点不推进（避免夜间虚减）
        val r = UsageStatsCollector.reconcileCollect(
            prevUsedMs = 30 * 60_000L,
            prevCollectedMs = 30 * 60_000L,
            prevAnchorMs = 1_000_000L,
            nowMs = 1_000_000L + 5 * 60_000L,
            collectedUsedMs = 30 * 60_000L,
            screenInteractive = false)
        assertEquals(30 * 60_000L, r.first)
        assertFalse(r.second)
    }

    @Test
    fun reconcile_tickingMinute_usesCollected() {
        // 采集分钟值正常进位 → 采信新值并推进锚点
        val r = UsageStatsCollector.reconcileCollect(
            prevUsedMs = 30 * 60_000L,
            prevCollectedMs = 30 * 60_000L,
            prevAnchorMs = 1_000_000L,
            nowMs = 1_000_000L + 60_000L,
            collectedUsedMs = 31 * 60_000L,
            screenInteractive = true)
        assertEquals(31 * 60_000L, r.first)
        assertTrue(r.second)
    }

    @Test
    fun reconcile_resetDrop_trustsCollected() {
        // 家长重置限额/跨天：采集值大幅回落 → 采信新值并推进锚点
        val r = UsageStatsCollector.reconcileCollect(
            prevUsedMs = 45 * 60_000L,
            prevCollectedMs = 45 * 60_000L,
            prevAnchorMs = 1_000_000L,
            nowMs = 1_000_000L + 60_000L,
            collectedUsedMs = 0L,
            screenInteractive = true)
        assertEquals(0L, r.first)
        assertTrue(r.second)
    }

    @Test
    fun countdown_monotonic_whenCollectedMinuteStuck() {
        // [回归] 采集分钟值卡在 30m 三个采集周期：剩余必须单调递减、且每个周期真实推进 ~60s
        // （不回跳、不冻结；此前出现过「卡在一个分钟上循环」与「冻在 00:29:00」两种回归）
        val limit = 120 * 60_000L
        var used = 0L
        var collectedUsed = 0L
        var anchor = 0L
        var t = 1_000_000L
        val first = UsageStatsCollector.reconcileCollect(used, collectedUsed, anchor, t, 30 * 60_000L, true)
        used = first.first
        collectedUsed = 30 * 60_000L
        anchor = t
        var prevRemain = Long.MAX_VALUE / 4
        for (cycle in 1..3) {
            val remainAt59 = UsageStatsCollector.computeRemainingMillis(limit, used, anchor, t + 59_000L, true)
            assertTrue("剩余时长回跳（周期 $cycle，59s 时）: $remainAt59 > $prevRemain", remainAt59 <= prevRemain + 1_000L)
            t += 60_000L
            val next = UsageStatsCollector.reconcileCollect(used, collectedUsed, anchor, t, 30 * 60_000L, true)
            used = next.first
            if (next.second) anchor = t
            val remainAfter = UsageStatsCollector.computeRemainingMillis(limit, used, anchor, t, true)
            assertTrue("剩余时长回跳（周期 $cycle，采集后）: $remainAfter > $remainAt59", remainAfter <= remainAt59 + 1_000L)
            if (cycle > 1) {
                assertTrue("倒计时冻结未推进（周期 $cycle）: $remainAfter >= $prevRemain", remainAfter < prevRemain)
            }
            prevRemain = remainAfter
        }
    }
}
