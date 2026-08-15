package com.xiaopacai.child.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TASK-MILESTONE-V3] 需求 10+11：家长端云端同步纯函数单元测试
 *
 * 覆盖：优先级 int↔string 映射、超时动作 UI↔服务端映射、分类键归一、
 * 三种周期报告响应归一化（日报/周报/30 天导出聚合）。
 */
class ParentCloudSyncTest {

    // ==================== 优先级映射 ====================

    @Test
    fun priorityToServer_mapsIntToServerString() {
        assertEquals("normal", ParentCloudSync.priorityToServer(0))
        assertEquals("important", ParentCloudSync.priorityToServer(1))
        assertEquals("urgent", ParentCloudSync.priorityToServer(2))
        assertEquals("normal", ParentCloudSync.priorityToServer(-1))
    }

    @Test
    fun priorityFromServer_mapsServerStringToInt() {
        assertEquals(0, ParentCloudSync.priorityFromServer("normal"))
        assertEquals(1, ParentCloudSync.priorityFromServer("important"))
        assertEquals(2, ParentCloudSync.priorityFromServer("urgent"))
        assertEquals(0, ParentCloudSync.priorityFromServer(null))
        assertEquals(0, ParentCloudSync.priorityFromServer("未知"))
    }

    // ==================== 超时动作映射 ====================

    @Test
    fun stopModeToServer_mapsUiValueToServerAction() {
        assertEquals("full_lock", ParentCloudSync.stopModeToServer("full"))
        assertEquals("partial_lock", ParentCloudSync.stopModeToServer("partial"))
        assertEquals("warn_only", ParentCloudSync.stopModeToServer("none"))
        assertEquals("full_lock", ParentCloudSync.stopModeToServer("未知"))
    }

    @Test
    fun stopModeFromServer_mapsServerActionToUiValue() {
        assertEquals("full", ParentCloudSync.stopModeFromServer("full_lock"))
        assertEquals("partial", ParentCloudSync.stopModeFromServer("partial_lock"))
        assertEquals("none", ParentCloudSync.stopModeFromServer("warn_only"))
        assertEquals("full", ParentCloudSync.stopModeFromServer(null))
        assertEquals("full", ParentCloudSync.stopModeFromServer(""))
    }

    // ==================== 分类键归一 ====================

    @Test
    fun normalizeCategoryKey_studyBecomesLearning() {
        assertEquals("learning", ParentCloudSync.normalizeCategoryKey("study"))
        assertEquals("learning", ParentCloudSync.normalizeCategoryKey("STUDY"))
        assertEquals("game", ParentCloudSync.normalizeCategoryKey("game"))
        assertEquals("other", ParentCloudSync.normalizeCategoryKey(""))
        assertEquals("other", ParentCloudSync.normalizeCategoryKey(null))
        assertEquals("other", ParentCloudSync.normalizeCategoryKey("  "))
    }

    // ==================== 报告归一化：日报 ====================

    @Test
    fun normalizeReport_daily_keepsServerTotalsAndCategories() {
        val body = """
            {
              "date": "2026-08-15",
              "totalMinutes": 95,
              "limitMinutes": 120,
              "remainingMinutes": 25,
              "rawAccumulated": true,
              "categories": [
                {"key": "game", "name": "游戏", "minutes": 60, "percent": 63.2},
                {"key": "learning", "name": "学习", "minutes": 35, "percent": 36.8}
              ]
            }
        """.trimIndent()

        val r = ParentCloudSync.normalizeReport(1, body)

        assertEquals(95L, r.optLong("totalMinutes"))
        assertEquals(120L, r.optLong("limitMinutes"))
        assertTrue(r.optBoolean("rawAccumulated"))
        assertEquals(1, r.optJSONArray("dailyTotals").length())
        assertEquals("2026-08-15", r.optJSONArray("dailyTotals").getJSONObject(0).optString("date"))
        assertEquals(2, r.optJSONArray("categories").length())
        assertEquals("游戏", r.optJSONArray("categories").getJSONObject(0).optString("name"))
    }

    // ==================== 报告归一化：周报 ====================

    @Test
    fun normalizeReport_weekly_usesDailyDetails() {
        val body = """
            {
              "weekStart": "2026-08-09",
              "weekEnd": "2026-08-15",
              "totalMinutes": 700,
              "limitMinutes": 840,
              "dailyDetails": [
                {"date": "2026-08-14", "totalMinutes": 120, "blockCount": 1},
                {"date": "2026-08-15", "totalMinutes": 80, "blockCount": 0}
              ],
              "categories": [{"key": "video", "name": "视频", "minutes": 400, "percent": 57.1}]
            }
        """.trimIndent()

        val r = ParentCloudSync.normalizeReport(7, body)

        assertEquals(700L, r.optLong("totalMinutes"))
        assertEquals(2, r.optJSONArray("dailyTotals").length())
        assertEquals(120L, r.optJSONArray("dailyTotals").getJSONObject(0).optLong("totalMinutes"))
        assertEquals("视频", r.optJSONArray("categories").getJSONObject(0).optString("name"))
    }

    // ==================== 报告归一化：30 天导出聚合 ====================

    @Test
    fun normalizeReport_export30_aggregatesDaysAndMergesCategories() {
        val body = """
            [
              {"date": "2026-08-14", "totalMinutes": 60, "blockCount": 1,
               "categories": [
                 {"key": "game", "name": "游戏", "minutes": 50, "percent": 83.3},
                 {"key": "study", "name": "学习", "minutes": 10, "percent": 16.7}
               ]},
              {"date": "2026-08-15", "totalMinutes": 40, "blockCount": 0,
               "categories": [
                 {"key": "game", "name": "游戏", "minutes": 30, "percent": 75.0},
                 {"key": "study", "name": "学习", "minutes": 10, "percent": 25.0}
               ]}
            ]
        """.trimIndent()

        val r = ParentCloudSync.normalizeReport(30, body)

        assertEquals(100L, r.optLong("totalMinutes"))
        assertEquals(2, r.optJSONArray("dailyTotals").length())
        val cats = r.optJSONArray("categories")
        assertEquals(2, cats.length())
        // 分类按 key 合并：game 80 分钟（80%），study 归一 learning 20 分钟（20%）
        val game = (0 until cats.length()).map { cats.getJSONObject(it) }
            .first { it.optString("key") == "game" }
        assertEquals(80L, game.optLong("minutes"))
        assertEquals(80.0, game.optDouble("percent"), 0.01)
        val learning = (0 until cats.length()).map { cats.getJSONObject(it) }
            .first { it.optString("key") == "learning" }
        assertEquals(20L, learning.optLong("minutes"))
        assertEquals(20.0, learning.optDouble("percent"), 0.01)
    }

    // ==================== 报告归一化：异常输入兜底 ====================

    @Test
    fun normalizeReport_malformedBody_returnsEmptyStructure() {
        val r = ParentCloudSync.normalizeReport(7, "not-json")
        assertEquals(0L, r.optLong("totalMinutes"))
        assertEquals(0, r.optJSONArray("dailyTotals").length())
        assertEquals(0, r.optJSONArray("categories").length())
        assertFalse(r.has("limitMinutes"))
    }
}
