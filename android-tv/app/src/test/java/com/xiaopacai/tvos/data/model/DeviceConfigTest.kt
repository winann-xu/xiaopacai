// [android-tv] 测试 — DeviceConfig 数据模型单元测试

package com.xiaopacai.tvos.data.model

import org.junit.Assert.*
import org.junit.Test

/**
 * DeviceConfig 数据模型单元测试
 *
 * 验证：
 * 1. 默认值正确性
 * 2. copy() 方法正确性
 * 3. equals/hashCode 正确性
 * 4. toString 正确性
 */
class DeviceConfigTest {

    @Test
    fun testDefaultValues() {
        val config = DeviceConfig()
        assertEquals(1L, config.id)
        assertEquals("", config.pinHash)
        assertEquals(60, config.dailyLimitMinutes)
        assertEquals("小趴菜TV", config.deviceName)
        assertEquals("xpc.winann.com", config.cloudHost)
        assertEquals(443, config.cloudPort)
        assertEquals("", config.deviceId)
        assertEquals("", config.bindToken)
        assertTrue(config.autoStartEnabled)
        assertTrue(config.ignoreBatteryOpt)
    }

    @Test
    fun testCopyWithValues() {
        val original = DeviceConfig()
        val modified = original.copy(
            pinHash = "abc123",
            dailyLimitMinutes = 30,
            deviceName = "客厅电视",
            cloudHost = "dev.xpc.winann.com",
            cloudPort = 8443
        )

        assertEquals(1L, modified.id)
        assertEquals("abc123", modified.pinHash)
        assertEquals(30, modified.dailyLimitMinutes)
        assertEquals("客厅电视", modified.deviceName)
        assertEquals("dev.xpc.winann.com", modified.cloudHost)
        assertEquals(8443, modified.cloudPort)
    }

    @Test
    fun testCopyPreservesUnmodifiedFields() {
        val original = DeviceConfig(
            deviceId = "device-12345",
            autoStartEnabled = false
        )
        val modified = original.copy(dailyLimitMinutes = 120)

        assertEquals(original.deviceId, modified.deviceId)
        assertEquals(original.pinHash, modified.pinHash)
        assertEquals(original.cloudHost, modified.cloudHost)
        assertEquals(original.cloudPort, modified.cloudPort)
        assertEquals(original.bindToken, modified.bindToken)
        assertEquals(original.autoStartEnabled, modified.autoStartEnabled)
        assertEquals(original.ignoreBatteryOpt, modified.ignoreBatteryOpt)
        assertNotEquals(original.dailyLimitMinutes, modified.dailyLimitMinutes)
    }

    @Test
    fun testEqualsAndHashCode() {
        val c1 = DeviceConfig(id = 1L, deviceId = "dev-1")
        val c2 = DeviceConfig(id = 1L, deviceId = "dev-1")
        val c3 = DeviceConfig(id = 1L, deviceId = "dev-2")

        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
        assertNotEquals(c1, c3)
    }

    @Test
    fun testToString() {
        val config = DeviceConfig(
            deviceId = "device-abc",
            deviceName = "卧室电视"
        )
        val str = config.toString()
        assertTrue(str.contains("device-abc"))
        assertTrue(str.contains("卧室电视"))
        assertTrue(str.contains("dailyLimitMinutes=60"))
    }

    @Test
    fun testAutoStartDisabled() {
        val config = DeviceConfig(autoStartEnabled = false)
        assertFalse(config.autoStartEnabled)
        assertTrue(config.ignoreBatteryOpt) // 默认仍启用
    }

    @Test
    fun testCloudConfigOverride() {
        val config = DeviceConfig(
            cloudHost = "192.168.1.100",
            cloudPort = 8080
        )
        assertEquals("192.168.1.100", config.cloudHost)
        assertEquals(8080, config.cloudPort)
    }
}
