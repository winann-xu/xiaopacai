// [android-tv] 测试 — AppUsage 数据模型单元测试

package com.xiaopacai.tvos.data.model

import org.junit.Assert.*
import org.junit.Test

/**
 * AppUsage 数据模型单元测试
 *
 * 验证：
 * 1. 默认值正确性
 * 2. copy() 方法正确性
 * 3. equals/hashCode 正确性
 * 4. toString 正确性
 */
class AppUsageTest {

    @Test
    fun testDefaultValues() {
        val usage = AppUsage(packageName = "com.test.default")
        assertEquals(0L, usage.id)
        assertEquals("com.test.default", usage.packageName)
        assertEquals("", usage.appName)
        assertEquals(0, usage.usedMinutesToday)
        assertEquals(0, usage.usedMinutesWeek)
        assertEquals(0, usage.usedMinutesMonth)
        assertEquals(0L, usage.lastUsedTime)
        assertEquals(0L, usage.totalUsageTime)
        assertFalse(usage.isWhitelisted)
    }

    @Test
    fun testCopyWithValues() {
        val original = AppUsage(packageName = "com.test.original")
        val modified = original.copy(
            packageName = "com.android.chrome",
            appName = "Chrome",
            usedMinutesToday = 45,
            isWhitelisted = true
        )

        assertEquals(0L, modified.id)
        assertEquals("com.android.chrome", modified.packageName)
        assertEquals("Chrome", modified.appName)
        assertEquals(45, modified.usedMinutesToday)
        assertTrue(modified.isWhitelisted)
    }

    @Test
    fun testCopyPreservesUnmodifiedFields() {
        val original = AppUsage(
            id = 42L,
            packageName = "com.netflix.tv",
            usedMinutesWeek = 120
        )
        val modified = original.copy(usedMinutesToday = 30)

        assertEquals(original.id, modified.id)
        assertEquals(original.packageName, modified.packageName)
        assertEquals(original.appName, modified.appName)
        assertEquals(original.usedMinutesWeek, modified.usedMinutesWeek)
        assertEquals(original.usedMinutesMonth, modified.usedMinutesMonth)
        assertEquals(original.lastUsedTime, modified.lastUsedTime)
        assertEquals(original.totalUsageTime, modified.totalUsageTime)
        assertEquals(original.isWhitelisted, modified.isWhitelisted)
        assertNotEquals(original.usedMinutesToday, modified.usedMinutesToday)
    }

    @Test
    fun testEqualsAndHashCode() {
        val a1 = AppUsage(packageName = "com.test.app", appName = "Test")
        val a2 = AppUsage(packageName = "com.test.app", appName = "Test")
        val a3 = AppUsage(packageName = "com.other.app", appName = "Other")

        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
        assertNotEquals(a1, a3)
    }

    @Test
    fun testToString() {
        val usage = AppUsage(
            packageName = "com.netflix.tv",
            appName = "Netflix",
            usedMinutesToday = 60
        )
        val str = usage.toString()
        assertTrue(str.contains("com.netflix.tv"))
        assertTrue(str.contains("Netflix"))
        assertTrue(str.contains("usedMinutesToday=60"))
    }

    @Test
    fun testWhitelistToggle() {
        val usage = AppUsage(packageName = "com.netflix.tv")
        assertFalse(usage.isWhitelisted)

        val whitelisted = usage.copy(isWhitelisted = true)
        assertTrue(whitelisted.isWhitelisted)
    }
}
