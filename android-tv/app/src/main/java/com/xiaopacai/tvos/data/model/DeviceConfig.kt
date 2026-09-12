// [android-tv] 数据模型 — DeviceConfig
// 小趴菜 TVOS 版 — 设备配置（限额、PIN、白名单等）

package com.xiaopacai.tvos.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 设备配置 — 每日限额、PIN、设备名称、云端设置
 */
@Entity(tableName = "device_config")
data class DeviceConfig(
    @PrimaryKey val id: Long = 1L,  // 只有一条
    val pinHash: String = "",           // PIN 密码哈希
    val dailyLimitMinutes: Int = 60,    // 每日限额（分钟）
    val deviceName: String = "小趴菜TV", // 设备显示名称
    val cloudHost: String = "xpc.winann.com", // 云端服务器
    val cloudPort: Int = 443,           // 云端端口
    val deviceId: String = "",          // 设备唯一标识（UUID）
    val bindToken: String = "",         // 绑定令牌
    val autoStartEnabled: Boolean = true,  // 自启动开关
    val ignoreBatteryOpt: Boolean = true,    // 忽略电池优化
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
