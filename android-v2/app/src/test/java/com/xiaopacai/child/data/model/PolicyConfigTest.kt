package com.xiaopacai.child.data.model

import org.junit.Assert.*
import org.junit.Test

/**
 * [TASK-TEST-ANDROID] PolicyConfig 单元测试
 *
 * 测试策略配置模型的 JSON 序列化/反序列化、策略验证、摘要生成。
 * 与 Windows 端 PolicyConfig.cs 的字段兼容性验证。
 */
class PolicyConfigTest {

    // ==================== 默认构造 ====================

    @Test
    fun `default constructor - sensible defaults`() {
        val config = PolicyConfig()
        assertEquals(0, config.id)
        assertEquals("", config.policyType)
        assertEquals("", config.deviceId)
        assertTrue(config.isActive)
        assertEquals(1, config.version)
        assertEquals(120, config.limitMinutes)
        assertEquals("21:00", config.sleepStart)
        assertEquals("07:00", config.sleepEnd)
        assertTrue(config.packageNames.isEmpty())
        assertEquals("", config.category)
        assertEquals(60, config.categoryLimitMinutes)
        assertEquals("", config.label)
    }

    // ==================== JSON 序列化/反序列化 ====================

    @Test
    fun `toJson and fromJson - daily_limit roundtrip`() {
        val original = PolicyConfig(
            id = 1,
            policyType = "daily_limit",
            limitMinutes = 180,
            label = "测试"
        )
        val json = original.toJson()
        val restored = PolicyConfig.fromJson(json)
        assertEquals("daily_limit", restored.policyType)
        assertEquals(180, restored.limitMinutes)
        assertEquals("测试", restored.label)
    }

    @Test
    fun `toJson and fromJson - sleep_time roundtrip`() {
        val original = PolicyConfig(
            id = 2,
            policyType = "sleep_time",
            sleepStart = "22:00",
            sleepEnd = "06:30",
            label = "就寝策略"
        )
        val json = original.toJson()
        val restored = PolicyConfig.fromJson(json)
        assertEquals("sleep_time", restored.policyType)
        assertEquals("22:00", restored.sleepStart)
        assertEquals("06:30", restored.sleepEnd)
    }

    @Test
    fun `toJson and fromJson - whitelist roundtrip`() {
        val original = PolicyConfig(
            policyType = "whitelist",
            packageNames = listOf("com.android.dialer", "com.android.settings"),
            label = "系统白名单"
        )
        val json = original.toJson()
        val restored = PolicyConfig.fromJson(json)
        assertEquals("whitelist", restored.policyType)
        assertEquals(2, restored.packageNames.size)
        assertTrue(restored.packageNames.contains("com.android.dialer"))
    }

    @Test
    fun `toJson and fromJson - category_limit roundtrip`() {
        val original = PolicyConfig(
            policyType = "category_limit",
            category = "game",
            categoryLimitMinutes = 90,
            label = "游戏限额"
        )
        val json = original.toJson()
        val restored = PolicyConfig.fromJson(json)
        assertEquals("category_limit", restored.policyType)
        assertEquals("game", restored.category)
        assertEquals(90, restored.categoryLimitMinutes)
    }

    @Test
    fun `fromJson - invalid json returns default`() {
        val result = PolicyConfig.fromJson("not valid json")
        assertEquals("", result.policyType)
    }

    @Test
    fun `fromJson - empty json returns default`() {
        val result = PolicyConfig.fromJson("{}")
        assertEquals("", result.policyType)
        assertEquals(120, result.limitMinutes)
    }

    // ==================== 策略验证 ====================

    @Test
    fun `isValid - daily_limit with positive minutes`() {
        val config = PolicyConfig(policyType = "daily_limit", limitMinutes = 120)
        assertTrue(config.isValid())
    }

    @Test
    fun `isValid - daily_limit with zero minutes`() {
        val config = PolicyConfig(policyType = "daily_limit", limitMinutes = 0)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid - sleep_time with valid times`() {
        val config = PolicyConfig(policyType = "sleep_time", sleepStart = "21:00", sleepEnd = "07:00")
        assertTrue(config.isValid())
    }

    @Test
    fun `isValid - sleep_time with invalid times`() {
        val config1 = PolicyConfig(policyType = "sleep_time", sleepStart = "25:00", sleepEnd = "07:00")
        assertFalse(config1.isValid())
        val config2 = PolicyConfig(policyType = "sleep_time", sleepStart = "21:00", sleepEnd = "7:00")
        assertFalse(config2.isValid())
    }

    @Test
    fun `isValid - invalid policy type`() {
        val config = PolicyConfig(policyType = "invalid_type")
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid - category_limit with valid category`() {
        val config = PolicyConfig(policyType = "category_limit", category = "game", categoryLimitMinutes = 60)
        assertTrue(config.isValid())
    }

    @Test
    fun `isValid - category_limit with invalid category`() {
        val config = PolicyConfig(policyType = "category_limit", category = "invalid_cat", categoryLimitMinutes = 60)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid - category_limit with zero minutes`() {
        val config = PolicyConfig(policyType = "category_limit", category = "game", categoryLimitMinutes = 0)
        assertFalse(config.isValid())
    }

    @Test
    fun `isValid - whitelist without time fields is valid`() {
        val config = PolicyConfig(policyType = "whitelist", packageNames = listOf("com.example.app"))
        assertTrue(config.isValid())
    }

    // ==================== 策略摘要 ====================

    @Test
    fun `getSummary - daily_limit`() {
        val config = PolicyConfig(policyType = "daily_limit", limitMinutes = 120)
        assertTrue(config.getSummary().contains("120"))
        assertTrue(config.getSummary().contains("每日限额"))
    }

    @Test
    fun `getSummary - sleep_time`() {
        val config = PolicyConfig(policyType = "sleep_time", sleepStart = "21:00", sleepEnd = "07:00")
        val summary = config.getSummary()
        assertTrue(summary.contains("21:00"))
        assertTrue(summary.contains("07:00"))
    }

    @Test
    fun `getSummary - whitelist`() {
        val config = PolicyConfig(policyType = "whitelist", packageNames = listOf("a", "b", "c"))
        val summary = config.getSummary()
        assertTrue(summary.contains("3"))
        assertTrue(summary.contains("白名单"))
    }

    @Test
    fun `getSummary - category_limit`() {
        val config = PolicyConfig(policyType = "category_limit", category = "game", categoryLimitMinutes = 90)
        val summary = config.getSummary()
        assertTrue(summary.contains("game"))
        assertTrue(summary.contains("90"))
    }

    @Test
    fun `getSummary - unknown type`() {
        val config = PolicyConfig(policyType = "")
        assertEquals("未知策略类型", config.getSummary())
    }

    // ==================== SUPPORTED_TYPES ====================

    @Test
    fun `SUPPORTED_TYPES - contains all five types`() {
        assertEquals(5, PolicyConfig.SUPPORTED_TYPES.size)
        assertTrue(PolicyConfig.SUPPORTED_TYPES.contains("daily_limit"))
        assertTrue(PolicyConfig.SUPPORTED_TYPES.contains("sleep_time"))
        assertTrue(PolicyConfig.SUPPORTED_TYPES.contains("whitelist"))
        assertTrue(PolicyConfig.SUPPORTED_TYPES.contains("blacklist"))
        assertTrue(PolicyConfig.SUPPORTED_TYPES.contains("category_limit"))
    }

    // ==================== SUPPORTED_CATEGORIES ====================

    @Test
    fun `SUPPORTED_CATEGORIES - contains all five categories`() {
        assertEquals(5, PolicyConfig.SUPPORTED_CATEGORIES.size)
        assertTrue(PolicyConfig.SUPPORTED_CATEGORIES.contains("game"))
        assertTrue(PolicyConfig.SUPPORTED_CATEGORIES.contains("social"))
        assertTrue(PolicyConfig.SUPPORTED_CATEGORIES.contains("video"))
        assertTrue(PolicyConfig.SUPPORTED_CATEGORIES.contains("study"))
        assertTrue(PolicyConfig.SUPPORTED_CATEGORIES.contains("other"))
    }

    // ==================== 兼容 Windows PolicyConfig.cs ====================

    @Test
    fun `compatibility - daily_limit fields match Windows model`() {
        val config = PolicyConfig(
            id = 42,
            policyType = "daily_limit",
            deviceId = "XP-ABCDEF123456",
            isActive = false,
            version = 3,
            createdAt = 1723276800L,
            updatedAt = 1723276900L,
            limitMinutes = 240,
            label = "兼容测试"
        )

        val json = config.toJson()
        val restored = PolicyConfig.fromJson(json)

        // daily_limit 类型序列化的字段
        assertEquals(config.id, restored.id)
        assertEquals(config.policyType, restored.policyType)
        assertEquals(config.deviceId, restored.deviceId)
        assertEquals(config.isActive, restored.isActive)
        assertEquals(config.version, restored.version)
        assertEquals(config.createdAt, restored.createdAt)
        assertEquals(config.updatedAt, restored.updatedAt)
        assertEquals(config.limitMinutes, restored.limitMinutes)
        assertEquals(config.label, restored.label)
    }

    @Test
    fun `compatibility - whitelist fields match Windows model`() {
        val config = PolicyConfig(
            id = 43,
            policyType = "whitelist",
            deviceId = "XP-ABCDEF123456",
            isActive = true,
            version = 2,
            packageNames = listOf("com.android.dialer", "com.android.settings"),
            label = "系统白名单"
        )

        val json = config.toJson()
        val restored = PolicyConfig.fromJson(json)

        assertEquals(config.id, restored.id)
        assertEquals(config.policyType, restored.policyType)
        assertEquals(config.deviceId, restored.deviceId)
        assertEquals(config.isActive, restored.isActive)
        assertEquals(config.version, restored.version)
        assertEquals(config.packageNames, restored.packageNames)
        assertEquals(config.label, restored.label)
    }

    @Test
    fun `compatibility - category_limit fields match Windows model`() {
        val config = PolicyConfig(
            id = 44,
            policyType = "category_limit",
            category = "game",
            categoryLimitMinutes = 120,
            label = "游戏限额"
        )

        val json = config.toJson()
        val restored = PolicyConfig.fromJson(json)

        assertEquals(config.policyType, restored.policyType)
        assertEquals(config.category, restored.category)
        assertEquals(config.categoryLimitMinutes, restored.categoryLimitMinutes)
        assertEquals(config.label, restored.label)
    }
}
