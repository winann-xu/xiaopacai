// [android-tv] 测试 — DeviceInfo 数据模型单元测试

package com.xiaopacai.tvos.data.model

import org.junit.Assert.*
import org.junit.Test

/**
 * DeviceInfo 数据模型单元测试
 *
 * 验证：
 * 1. 默认值正确性
 * 2. copy() 方法正确性
 * 3. equals/hashCode 正确性
 * 4. toString 正确性
 * 5. Android SDK 版本有效性
 */
class DeviceInfoTest {

    @Test
    fun testDefaultValues() {
        val info = DeviceInfo()
        assertFalse(info.brand.isEmpty())           // Build.BRAND 不应为空
        assertFalse(info.model.isEmpty())           // Build.MODEL 不应为空
        assertFalse(info.manufacturer.isEmpty())
        assertFalse(info.tvBrand.isEmpty())
        assertEquals("unknown", info.miuiVersion)
        assertEquals("", info.androidId)
        assertEquals("", info.macAddress)
        assertTrue(info.sdkVersion > 0)              // SDK 版本应 > 0
    }

    @Test
    fun testSdkVersionValid() {
        val info = DeviceInfo()
        // SDK_INT should be at least API 26 (Android 8.0)
        assertTrue(info.sdkVersion >= 26)
    }

    @Test
    fun testCopyWithCustomValues() {
        val original = DeviceInfo()
        val custom = original.copy(
            androidId = "abc123def456",
            macAddress = "02:00:00:00:00:00",
            tvBrand = "Xiaomi",
            miuiVersion = "TV V14"
        )

        assertEquals("abc123def456", custom.androidId)
        assertEquals("02:00:00:00:00:00", custom.macAddress)
        assertEquals("Xiaomi", custom.tvBrand)
        assertEquals("TV V14", custom.miuiVersion)
    }

    @Test
    fun testCopyPreservesHardwareInfo() {
        val original = DeviceInfo()
        val modified = original.copy(
            androidId = "new-id",
            tvBrand = "Sony"
        )

        assertEquals(original.brand, modified.brand)
        assertEquals(original.model, modified.model)
        assertEquals(original.manufacturer, modified.manufacturer)
        assertEquals(original.product, modified.product)
        assertEquals(original.sdkVersion, modified.sdkVersion)
        assertEquals(original.osVersion, modified.osVersion)
    }

    @Test
    fun testEqualsAndHashCode() {
        val d1 = DeviceInfo(androidId = "id-1", tvBrand = "Xiaomi")
        val d2 = DeviceInfo(androidId = "id-1", tvBrand = "Xiaomi")
        val d3 = DeviceInfo(androidId = "id-2", tvBrand = "Sony")

        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
        assertNotEquals(d1, d3)
    }

    @Test
    fun testToString() {
        val info = DeviceInfo(tvBrand = "Hisense", androidId = "abc")
        val str = info.toString()
        assertTrue(str.contains("Hisense"))
        assertTrue(str.contains("abc"))
        assertTrue(str.contains("sdkVersion"))
    }

    @Test
    fun testOsVersionNotEmpty() {
        val info = DeviceInfo()
        assertFalse(info.osVersion.isEmpty())
    }
}
