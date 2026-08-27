package com.xiaopacai.child.data.database

import org.junit.Assert.*
import org.junit.Test

/**
 * [TASK-TEST-ANDROID] UsageRecordDao / UsageRecordEntry 单元测试
 *
 * 测试 UsageRecordEntry 数据类和使用记录业务逻辑。
 * 注意：UsageRecordDao 依赖 SQLCipher 原生库，完整的 DAO CRUD 测试
 * 需要在 Android 仪器测试 (androidTest) 中执行。
 */
class UsageRecordDaoTest {

    // ==================== UsageRecordEntry 数据类 ====================

    @Test
    fun `UsageRecordEntry - construction and properties`() {
        val entry = UsageRecordEntry(
            packageName = "com.test.app",
            appName = "Test App",
            date = "2026-08-10",
            totalMinutes = 60L,
            category = "game"
        )
        assertEquals("com.test.app", entry.packageName)
        assertEquals("Test App", entry.appName)
        assertEquals("2026-08-10", entry.date)
        assertEquals(60L, entry.totalMinutes)
        assertEquals("game", entry.category)
    }

    @Test
    fun `UsageRecordEntry - default category is other`() {
        val entry = UsageRecordEntry(
            packageName = "com.test.app",
            appName = "Test",
            date = "2026-08-10",
            totalMinutes = 30L
        )
        assertEquals("other", entry.category)
    }

    @Test
    fun `UsageRecordEntry - all supported categories`() {
        val categories = listOf("game", "social", "video", "study", "other")
        for (cat in categories) {
            val entry = UsageRecordEntry(
                packageName = "com.xiaopacai.test",
                appName = "Test",
                date = "2026-08-10",
                totalMinutes = 10L,
                category = cat
            )
            assertEquals(cat, entry.category)
        }
    }

    @Test
    fun `UsageRecordEntry - equality and hashcode`() {
        val a = UsageRecordEntry("com.a", "A", "2026-08-10", 10L, "game")
        val b = UsageRecordEntry("com.a", "A", "2026-08-10", 10L, "game")
        val c = UsageRecordEntry("com.b", "B", "2026-08-10", 20L, "study")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `UsageRecordEntry - copy retains values`() {
        val original = UsageRecordEntry("com.a", "App A", "2026-08-10", 45L, "study")
        val copied = original.copy()
        assertEquals(original, copied)
    }

    @Test
    fun `UsageRecordEntry - copy with modifications`() {
        val original = UsageRecordEntry("com.a", "App A", "2026-08-10", 45L, "study")
        val modified = original.copy(totalMinutes = 90L, category = "game")
        assertEquals(90L, modified.totalMinutes)
        assertEquals("game", modified.category)
        assertEquals("com.a", modified.packageName)  // 其他字段不变
        assertEquals("App A", modified.appName)
    }

    @Test
    fun `UsageRecordEntry - handles long package names`() {
        val longPkg = "com." + "a".repeat(50) + ".app"
        val entry = UsageRecordEntry(longPkg, "Test", "2026-08-10", 10L)
        assertEquals(longPkg, entry.packageName)
    }

    @Test
    fun `UsageRecordEntry - zero minutes is valid`() {
        val entry = UsageRecordEntry("com.test", "Test", "2026-08-10", 0L)
        assertEquals(0L, entry.totalMinutes)
    }

    @Test
    fun `UsageRecordEntry - max safe minutes`() {
        // 24小时 = 1440 分钟
        val entry = UsageRecordEntry("com.test", "Test", "2026-08-10", 1440L)
        assertEquals(1440L, entry.totalMinutes)
    }

    // ==================== UsageRecordDao 存在性验证 ====================

    @Test
    fun `UsageRecordDao class exists and has expected methods`() {
        // 验证类存在（编译时检查）
        val clazz = UsageRecordDao::class.java
        assertNotNull(clazz)

        // 验证关键方法声明
        val methodNames = clazz.methods.map { it.name }.toSet()
        assertTrue(methodNames.contains("upsertUsageRecord"))
        assertTrue(methodNames.contains("batchUpsertUsageRecords"))
        assertTrue(methodNames.contains("getUsageRecordsByDate"))
        assertTrue(methodNames.contains("getTodayTotalMinutes"))
        assertTrue(methodNames.contains("updateDailySummary"))
        assertTrue(methodNames.contains("getDailySummary"))
        assertTrue(methodNames.contains("getUnsyncedRecords"))
        assertTrue(methodNames.contains("markAsSynced"))
    }
}
