package com.xiaopacai.child.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * [TASK-D2-02] 策略配置模型（Android/儿童端）
 *
 * 家长端策略引擎的核心数据结构。
 * 支持五种策略类型：每日限额、就寝时段、白名单、黑名单、分类限制。
 * 所有策略以 JSON 格式存储和同步。
 *
 * 与 Windows 端 PolicyConfig.cs 保持字段兼容，确保 P2P 同步互通。
 */
data class PolicyConfig(
    /** 策略主键 */
    val id: Int = 0,

    /** 策略类型：daily_limit / sleep_time / whitelist / blacklist / category_limit */
    val policyType: String = "",

    /** 适用设备 ID（空字符串表示全局策略） */
    val deviceId: String = "",

    /** 是否启用 */
    val isActive: Boolean = true,

    /** 策略版本号（增量同步用） */
    val version: Int = 1,

    /** 创建时间戳（Unix 秒） */
    val createdAt: Long = System.currentTimeMillis() / 1000,

    /** 更新时间戳（Unix 秒） */
    val updatedAt: Long = System.currentTimeMillis() / 1000,

    // === 具体策略数据（按类型不同使用不同字段） ===

    /** 每日限额（分钟），用于 daily_limit 类型 */
    val limitMinutes: Int = 120,

    /**
     * [TASK-OPT-12-P1] 超时限制模式，用于 daily_limit 类型
     *
     * full=全部停用 / partial=仅停用部分应用 / warn=仅警告不拦截。
     * 与 Web 端 OvertimeAction 对齐（full_lock→full、partial_lock→partial、warn_only→warn）。
     * 缺省为 full，兼容旧版本策略数据。
     */
    val restrictMode: String = "full",

    /** 就寝开始时间（HH:mm），用于 sleep_time 类型 */
    val sleepStart: String = "21:00",

    /** 就寝结束时间（HH:mm），用于 sleep_time 类型 */
    val sleepEnd: String = "07:00",

    /** 应用包名列表，用于 whitelist/blacklist 类型 */
    val packageNames: List<String> = emptyList(),

    /** 应用分类（game/social/video/study/other），用于 category_limit 类型 */
    val category: String = "",

    /** 分类限额（分钟），用于 category_limit 类型 */
    val categoryLimitMinutes: Int = 60,

    /** 策略标签（备注/说明） */
    val label: String = ""
) {
    companion object {
        /** 支持的策略类型列表 */
        val SUPPORTED_TYPES = setOf(
            "daily_limit", "sleep_time", "whitelist", "blacklist", "category_limit"
        )

        /** 支持的分类列表 */
        val SUPPORTED_CATEGORIES = setOf(
            "game", "social", "video", "study", "other"
        )

        /**
         * 从 JSON 字符串反序列化
         */
        fun fromJson(json: String): PolicyConfig {
            return try {
                val obj = JSONObject(json)
                PolicyConfig(
                    id = obj.optInt("id", 0),
                    policyType = obj.optString("policyType", ""),
                    deviceId = obj.optString("deviceId", ""),
                    isActive = obj.optBoolean("isActive", true),
                    version = obj.optInt("version", 1),
                    createdAt = obj.optLong("createdAt", 0),
                    updatedAt = obj.optLong("updatedAt", 0),
                    limitMinutes = obj.optInt("limitMinutes", 120),
                    restrictMode = obj.optString("restrictMode", "full"),
                    sleepStart = obj.optString("sleepStart", "21:00"),
                    sleepEnd = obj.optString("sleepEnd", "07:00"),
                    packageNames = parseStringList(obj.optJSONArray("packageNames")),
                    category = obj.optString("category", ""),
                    categoryLimitMinutes = obj.optInt("categoryLimitMinutes", 60),
                    label = obj.optString("label", "")
                )
            } catch (e: Exception) {
                PolicyConfig()
            }
        }

        private fun parseStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                result.add(arr.optString(i, ""))
            }
            return result
        }
    }

    /**
     * 序列化为 JSON 字符串（用于数据库存储和 P2P 同步）
     */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("policyType", policyType)
        obj.put("deviceId", deviceId)
        obj.put("isActive", isActive)
        obj.put("version", version)
        obj.put("createdAt", createdAt)
        obj.put("updatedAt", updatedAt)
        if (policyType == "daily_limit") {
            obj.put("limitMinutes", limitMinutes)
            // [TASK-OPT-12-P1] 超时限制模式（full/partial/warn），缺省 full
            obj.put("restrictMode", restrictMode)
        }
        if (policyType == "sleep_time") {
            obj.put("sleepStart", sleepStart)
            obj.put("sleepEnd", sleepEnd)
        }
        if (policyType in listOf("whitelist", "blacklist")) {
            val arr = JSONArray()
            packageNames.forEach { arr.put(it) }
            obj.put("packageNames", arr)
        }
        if (policyType == "category_limit") {
            obj.put("category", category)
            obj.put("categoryLimitMinutes", categoryLimitMinutes)
        }
        if (label.isNotEmpty()) {
            obj.put("label", label)
        }
        return obj.toString()
    }

    /**
     * 验证策略类型是否受支持
     */
    fun isValidType(): Boolean = policyType in SUPPORTED_TYPES

    /**
     * 验证分类是否受支持
     */
    fun isValidCategory(): Boolean {
        if (policyType != "category_limit") return true
        return category in SUPPORTED_CATEGORIES
    }

    /**
     * 验证整个策略配置的有效性
     */
    fun isValid(): Boolean {
        if (!isValidType()) return false
        if (!isValidCategory()) return false
        if (policyType == "sleep_time") {
            // 就寝时间格式验证
            val timeRegex = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            if (!timeRegex.matches(sleepStart) || !timeRegex.matches(sleepEnd)) return false
        }
        if (policyType == "daily_limit" && limitMinutes <= 0) return false
        if (policyType == "category_limit" && categoryLimitMinutes <= 0) return false
        return true
    }

    /**
     * 生成策略摘要（用于 UI 展示）
     */
    fun getSummary(): String {
        return when (policyType) {
            "daily_limit" -> "每日限额 $limitMinutes 分钟"
            "sleep_time" -> "就寝时段 $sleepStart-$sleepEnd"
            "whitelist" -> "白名单 ${packageNames.size} 个应用"
            "blacklist" -> "黑名单 ${packageNames.size} 个应用"
            "category_limit" -> "$category 类限额 $categoryLimitMinutes 分钟"
            else -> "未知策略类型"
        }
    }
}
