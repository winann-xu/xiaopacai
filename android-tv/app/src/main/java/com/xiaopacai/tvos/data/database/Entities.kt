// [android-tv] 数据库实体类 — 包含所有 Room 表
// 小趴菜 TVOS 版 — 加密数据库

package com.xiaopacai.tvos.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 设备配置表
 */
@Entity(tableName = "device_config")
data class DeviceConfigEntity(
    @PrimaryKey val id: Long = 1L,
    val pinHash: String = "",
    val dailyLimitMinutes: Int = 60,
    val deviceName: String = "小趴菜TV",
    val cloudHost: String = "xpc.winann.com",
    val cloudPort: Int = 443,
    val deviceId: String = "",
    val bindToken: String = "",
    val autoStartEnabled: Boolean = true,
    val ignoreBatteryOpt: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 应用使用时长表
 */
@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String = "",
    val usedMinutesToday: Int = 0,
    val usedMinutesWeek: Int = 0,
    val usedMinutesMonth: Int = 0,
    val lastUsedTime: Long = 0,
    val totalUsageTime: Long = 0,
    val isWhitelisted: Boolean = false
)

/**
 * 使用记录明细表（按天记录）
 */
@Entity(tableName = "usage_log",
    indices = [androidx.room.Index(value = ["date"], unique = false)]
)
data class UsageLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,                // YYYY-MM-DD
    val packageName: String,
    val minutesUsed: Int,
    val startTime: Long = 0,
    val endTime: Long = 0
)

/**
 * 云端策略同步表（存储从云端下发的策略）
 */
@Entity(tableName = "cloud_policy")
data class CloudPolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val policyKey: String,            // 策略键名（daily_limit / whitelist / pin）
    val policyValue: String,          // 策略值（JSON 字符串）
    val syncTime: Long = System.currentTimeMillis(),
    val isServerManaged: Boolean = false // 是否由服务端管理（本地不可改）
)

/**
 * 系统应用信息表（已安装应用列表）
 */
@Entity(tableName = "installed_apps",
    indices = [androidx.room.Index(value = ["packageName"], unique = true)]
)
data class InstalledAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val iconUri: String = "",         // Base64 编码的图标
    val isSystemApp: Boolean = false,
    val installTime: Long = 0,
    val updateTime: Long = 0
)
