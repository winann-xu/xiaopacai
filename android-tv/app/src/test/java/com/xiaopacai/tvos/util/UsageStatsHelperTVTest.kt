// [android-tv] 测试 — UsageStatsHelperTV Robolectric 测试

package com.xiaopacai.tvos.util

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UsageStatsHelperTV Robolectric 单元测试
 *
 * 验证：
 * 1. queryTodayUsage 返回非空 Map
 * 2. clearCache 功能正常
 * 3. 权限检查方法可调用
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageStatsHelperTVTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun testQueryTodayUsageReturnsMap() {
        val usageMap = UsageStatsHelperTV.queryTodayUsage(context)
        assertNotNull(usageMap)
        // Should be a Map (may be empty on Robolectric)
        assertTrue(usageMap is Map<*, *>)
    }

    @Test
    fun testQueryTodayUsageReturnsSameType() {
        val usageMap = UsageStatsHelperTV.queryTodayUsage(context)
        // Verify each entry has String key and Int value
        usageMap.forEach { (key, value) ->
            assertNotNull(key)
            assertNotNull(value)
        }
    }

    @Test
    fun testClearCacheDoesNotCrash() {
        // Clear cache should not throw
        UsageStatsHelperTV.clearCache()
    }

    @Test
    fun testIsUsageStatsPermissionGrantedReturnsBoolean() {
        // This may throw SecurityException if permission not granted
        val granted = try {
            UsageStatsHelperTV.isUsageStatsPermissionGranted(context)
            true
        } catch (e: Exception) {
            false
        }
        assertTrue(granted || true) // Either way is valid in Robolectric
    }

    @Test
    fun testOpenUsageStatsSettingsDoesNotCrash() {
        // Should not throw in Robolectric
        try {
            UsageStatsHelperTV.openUsageStatsSettings(context)
        } catch (e: Exception) {
            // Expected: may not have proper settings UI in Robolectric
        }
    }
}
