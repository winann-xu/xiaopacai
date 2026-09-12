// [android-tv] 数据模型 — AppUsage
// 小趴菜 TVOS 版 — 应用使用时长记录

package com.xiaopacai.tvos.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单个应用的使用时长记录
 */
@Entity(tableName = "app_usage")
data class AppUsage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,           // 应用包名
    val appName: String = "",          // 应用名称
    val usedMinutesToday: Int = 0,     // 今日已用分钟
    val usedMinutesWeek: Int = 0,      // 本周已用分钟
    val usedMinutesMonth: Int = 0,     // 本月已用分钟
    val lastUsedTime: Long = 0,        // 上次使用时间
    val totalUsageTime: Long = 0,      // 累计使用总时长（分钟）
    val isWhitelisted: Boolean = false // 是否在白名单中
)
