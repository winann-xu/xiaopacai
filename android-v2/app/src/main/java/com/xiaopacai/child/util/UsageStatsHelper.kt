package com.xiaopacai.child.util

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * [TASK-D1-02] 应用使用时长统计工具
 *
 * 封装 Android UsageStatsManager API，提供每日应用使用时长查询。
 * 所有查询结果以分钟为单位返回。
 */
object UsageStatsHelper {

    /**
     * 查询指定日期的所有应用使用时长
     *
     * @param context 应用上下文
     * @param calendar 目标日期
     * @return Map<应用包名, 使用分钟数>
     */
    fun getDailyUsageMinutes(
        context: Context,
        calendar: Calendar = Calendar.getInstance()
    ): Map<String, Long> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return emptyMap()

        // 设置查询起止时间（00:00 ~ 23:59）
        val startCal = calendar.clone() as Calendar
        startCal.set(Calendar.HOUR_OF_DAY, 0)
        startCal.set(Calendar.MINUTE, 0)
        startCal.set(Calendar.SECOND, 0)
        startCal.set(Calendar.MILLISECOND, 0)

        val endCal = calendar.clone() as Calendar
        endCal.set(Calendar.HOUR_OF_DAY, 23)
        endCal.set(Calendar.MINUTE, 59)
        endCal.set(Calendar.SECOND, 59)
        endCal.set(Calendar.MILLISECOND, 999)

        val startTime = startCal.timeInMillis
        val endTime = endCal.timeInMillis

        // 查询使用情况统计
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        // 汇总各应用使用时长（转换为分钟）
        val result = mutableMapOf<String, Long>()
        usageStatsList.forEach { stats ->
            val totalMillis = stats.totalTimeInForeground
            if (totalMillis > 0) {
                // 按包名合并（可能出现同包多条记录）
                val existingMinutes = result[stats.packageName] ?: 0L
                result[stats.packageName] = existingMinutes + (totalMillis / 60_000)
            }
        }

        return result
    }

    /**
     * 查询今日总使用时长（分钟）
     *
     * @return 今日累计使用分钟数
     */
    fun getTodayTotalMinutes(context: Context): Long {
        val dailyUsage = getDailyUsageMinutes(context)
        return dailyUsage.values.sum()
    }
}
