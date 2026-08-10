package com.xiaopacai.child.service

import android.content.Context
import com.xiaopacai.child.data.database.AppDatabase
import com.xiaopacai.child.data.database.UsageRecordDao
import com.xiaopacai.child.util.DbPassphraseProvider
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * [TASK-D3-01] 使用报告生成器
 *
 * 从加密数据库读取使用记录，生成日报/周报 JSON 数据。
 * 报告包含：
 * - 总体使用时长统计
 * - 按分类汇总（游戏/社交/视频/学习/其他）
 * - 按应用 Top-N 排行
 * - 超时/限额触发次数
 * - 趋势对比（与昨日/上周对比）
 */
class ReportGenerator(private val context: Context) {

    companion object {
        private const val DATE_FORMAT = "yyyy-MM-dd"
        private const val TOP_N = 10  // Top-N 应用排行
    }

    private val dbHelper = AppDatabase.getInstance(
        context, DbPassphraseProvider.getPassphrase(context))
    private val dao = UsageRecordDao(dbHelper)
    private val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())

    /**
     * 生成日报
     *
     * @param date 报告日期（yyyy-MM-dd），默认今天
     * @param passphrase 数据库加密密钥
     * @return JSON 格式日报
     */
    fun generateDailyReport(
        date: String = dateFormat.format(Date()),
        passphrase: ByteArray
    ): JSONObject {
        val report = JSONObject()
        report.put("reportType", "daily")
        report.put("date", date)
        report.put("generatedAt", System.currentTimeMillis() / 1000)

        // 1. 当日使用记录
        val records = dao.getUsageRecordsByDate(date, passphrase)
        val recordsArray = JSONArray()
        var totalMinutes = 0L
        val categoryMinutes = mutableMapOf<String, Long>()

        for (rec in records) {
            val entry = JSONObject()
            entry.put("packageName", rec["packageName"] ?: "")
            entry.put("appName", rec["appName"] ?: "")
            entry.put("totalMinutes", rec["totalMinutes"] as? Long ?: 0L)
            entry.put("category", rec["category"] ?: "other")
            recordsArray.put(entry)

            val mins = rec["totalMinutes"] as? Long ?: 0L
            totalMinutes += mins
            val cat = rec["category"] as? String ?: "other"
            categoryMinutes[cat] = (categoryMinutes[cat] ?: 0L) + mins
        }

        report.put("totalMinutes", totalMinutes)
        report.put("totalHours", String.format("%.1f", totalMinutes / 60.0))

        // 2. 分类汇总
        val categorySummary = JSONObject()
        categoryMinutes.forEach { (cat, mins) ->
            val catObj = JSONObject()
            catObj.put("minutes", mins)
            catObj.put("hours", String.format("%.1f", mins / 60.0))
            catObj.put("percent", if (totalMinutes > 0)
                String.format("%.1f", mins * 100.0 / totalMinutes) else "0.0")
            categorySummary.put(cat, catObj)
        }
        report.put("categorySummary", categorySummary)

        // 3. Top-N 应用排行（按使用时长降序）
        val sortedRecords = records.sortedByDescending {
            it["totalMinutes"] as? Long ?: 0L
        }.take(TOP_N)
        val topApps = JSONArray()
        for (rec in sortedRecords) {
            val app = JSONObject()
            app.put("appName", rec["appName"] ?: "")
            app.put("packageName", rec["packageName"] ?: "")
            app.put("minutes", rec["totalMinutes"] as? Long ?: 0L)
            app.put("category", rec["category"] ?: "other")
            topApps.put(app)
        }
        report.put("topApps", topApps)

        // 4. 每日汇总（限额超时信息）
        val summary = dao.getDailySummary(date, passphrase)
        if (summary != null) {
            val summaryObj = JSONObject()
            summaryObj.put("totalMinutes", summary["totalMinutes"] ?: 0L)
            summaryObj.put("gameMinutes", summary["gameMinutes"] ?: 0L)
            summaryObj.put("studyMinutes", summary["studyMinutes"] ?: 0L)
            summaryObj.put("limitMinutes", summary["limitMinutes"] ?: 0L)
            summaryObj.put("limitExceeded", summary["limitExceeded"] ?: false)
            summaryObj.put("stopMode", summary["stopMode"] ?: "none")
            report.put("dailySummary", summaryObj)
        }

        // 5. 与昨日对比趋势
        val yesterday = getYesterdayDate(date)
        val yesterdayTotal = dao.getTodayTotalMinutes(yesterday, passphrase)
        val trend = JSONObject()
        trend.put("yesterdayTotal", yesterdayTotal)
        trend.put("todayTotal", totalMinutes)
        trend.put("change", totalMinutes - yesterdayTotal)
        trend.put("changePercent", if (yesterdayTotal > 0)
            String.format("%.1f", (totalMinutes - yesterdayTotal) * 100.0 / yesterdayTotal)
        else "N/A")
        report.put("trend", trend)

        return report
    }

    /**
     * 生成周报
     *
     * @param endDate 周报截止日期（yyyy-MM-dd），默认今天
     * @param passphrase 数据库加密密钥
     * @return JSON 格式周报
     */
    fun generateWeeklyReport(
        endDate: String = dateFormat.format(Date()),
        passphrase: ByteArray
    ): JSONObject {
        val report = JSONObject()
        report.put("reportType", "weekly")
        report.put("endDate", endDate)
        report.put("generatedAt", System.currentTimeMillis() / 1000)

        // 计算周起始日期（前7天）
        val calendar = Calendar.getInstance()
        calendar.time = dateFormat.parse(endDate) ?: Date()
        calendar.add(Calendar.DAY_OF_YEAR, -6)  // 含当天共7天
        val startDate = dateFormat.format(calendar.time)

        report.put("startDate", startDate)

        // 1. 每日汇总数组
        val dailySummaries = JSONArray()
        var weekTotalMinutes = 0L
        val weekCategoryMinutes = mutableMapOf<String, Long>()
        var exceedDays = 0

        calendar.time = dateFormat.parse(startDate) ?: Date()
        for (i in 0..6) {
            val dayDate = dateFormat.format(calendar.time)
            val dayRecords = dao.getUsageRecordsByDate(dayDate, passphrase)
            val dayTotal = dayRecords.sumOf {
                (it["totalMinutes"] as? Long ?: 0L).toLong()
            }

            val dayObj = JSONObject()
            dayObj.put("date", dayDate)
            dayObj.put("totalMinutes", dayTotal)
            dayObj.put("totalHours", String.format("%.1f", dayTotal / 60.0))
            dayObj.put("recordCount", dayRecords.size)

            // 检查是否超时
            val daySummary = dao.getDailySummary(dayDate, passphrase)
            val exceeded = if (daySummary?.get("limitExceeded") == true) {
                exceedDays++
                true
            } else false
            dayObj.put("limitExceeded", exceeded)

            // 分类汇总
            val dayCatObj = JSONObject()
            for (rec in dayRecords) {
                val cat = rec["category"] as? String ?: "other"
                val mins = rec["totalMinutes"] as? Long ?: 0L
                dayCatObj.put(cat, (dayCatObj.optLong(cat, 0L) + mins))
            }
            dayObj.put("categories", dayCatObj)

            dailySummaries.put(dayObj)
            weekTotalMinutes += dayTotal

            // 累加到周分类统计
            for (rec in dayRecords) {
                val cat = rec["category"] as? String ?: "other"
                val mins = rec["totalMinutes"] as? Long ?: 0L
                weekCategoryMinutes[cat] = (weekCategoryMinutes[cat] ?: 0L) + mins
            }

            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        report.put("dailySummaries", dailySummaries)
        report.put("weekTotalMinutes", weekTotalMinutes)
        report.put("weekTotalHours", String.format("%.1f", weekTotalMinutes / 60.0))
        report.put("exceedDays", exceedDays)

        // 2. 日均使用时长
        val avgDaily = weekTotalMinutes / 7.0
        report.put("averageDailyMinutes", String.format("%.1f", avgDaily))

        // 3. 周分类汇总
        val weekCategorySummary = JSONObject()
        weekCategoryMinutes.forEach { (cat, mins) ->
            val catObj = JSONObject()
            catObj.put("minutes", mins)
            catObj.put("hours", String.format("%.1f", mins / 60.0))
            catObj.put("percent", if (weekTotalMinutes > 0)
                String.format("%.1f", mins * 100.0 / weekTotalMinutes) else "0.0")
            weekCategorySummary.put(cat, catObj)
        }
        report.put("weekCategorySummary", weekCategorySummary)

        // 4. 周 Top-N 应用
        val appWeekMap = mutableMapOf<String, Pair<String, Long>>()  // packageName -> (appName, minutes)
        for (i in 0 until dailySummaries.length()) {
            val dayDate = dailySummaries.getJSONObject(i).getString("date")
            val dayRecs = dao.getUsageRecordsByDate(dayDate, passphrase)
            for (rec in dayRecs) {
                val pkg = rec["packageName"] as? String ?: continue
                val name = rec["appName"] as? String ?: ""
                val mins = rec["totalMinutes"] as? Long ?: 0L
                val existing = appWeekMap[pkg]
                appWeekMap[pkg] = Pair(
                    if (name.isNotEmpty()) name else (existing?.first ?: pkg),
                    (existing?.second ?: 0L) + mins
                )
            }
        }

        val topApps = JSONArray()
        appWeekMap.entries
            .sortedByDescending { it.value.second }
            .take(TOP_N)
            .forEach { (pkg, pair) ->
                val app = JSONObject()
                app.put("appName", pair.first)
                app.put("packageName", pkg)
                app.put("minutes", pair.second)
                app.put("hours", String.format("%.1f", pair.second / 60.0))
                topApps.put(app)
            }
        report.put("topApps", topApps)

        // 5. 使用趋势（与上周对比）
        val lastWeekStartCalendar = Calendar.getInstance()
        lastWeekStartCalendar.time = dateFormat.parse(startDate) ?: Date()
        lastWeekStartCalendar.add(Calendar.DAY_OF_YEAR, -7)
        var lastWeekTotal = 0L
        for (i in 0..6) {
            val dayDate = dateFormat.format(lastWeekStartCalendar.time)
            lastWeekTotal += dao.getTodayTotalMinutes(dayDate, passphrase)
            lastWeekStartCalendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        val trend = JSONObject()
        trend.put("lastWeekTotal", lastWeekTotal)
        trend.put("thisWeekTotal", weekTotalMinutes)
        trend.put("change", weekTotalMinutes - lastWeekTotal)
        trend.put("changePercent", if (lastWeekTotal > 0)
            String.format("%.1f", (weekTotalMinutes - lastWeekTotal) * 100.0 / lastWeekTotal)
        else "N/A")
        report.put("trend", trend)

        return report
    }

    /**
     * 获取前一天日期
     */
    private fun getYesterdayDate(today: String): String {
        val calendar = Calendar.getInstance()
        calendar.time = dateFormat.parse(today) ?: Date()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        return dateFormat.format(calendar.time)
    }

    /**
     * 将报告导出为纯文本格式（方便直接展示）
     */
    fun formatReportAsText(report: JSONObject): String {
        val sb = StringBuilder()
        val reportType = report.optString("reportType", "daily")

        sb.appendLine("========================================")
        if (reportType == "daily") {
            sb.appendLine("  📊 小趴菜日报 - ${report.optString("date")}")
        } else {
            sb.appendLine("  📊 小趴菜周报")
            sb.appendLine("  ${report.optString("startDate")} ~ ${report.optString("endDate")}")
        }
        sb.appendLine("========================================")
        sb.appendLine()

        // 总览
        if (reportType == "daily") {
            val totalH = report.optString("totalHours", "0")
            sb.appendLine("📱 今日总使用时长：${totalH} 小时")
            sb.appendLine()

            // 分类汇总
            val catSummary = report.optJSONObject("categorySummary")
            if (catSummary != null) {
                sb.appendLine("📂 分类分布：")
                for (key in catSummary.keys().asSequence().sorted()) {
                    val cat = catSummary.getJSONObject(key)
                    sb.appendLine("  • ${getCategoryLabel(key)}：${cat.optString("hours")}h (${cat.optString("percent")}%)")
                }
                sb.appendLine()
            }

            // Top 应用
            sb.appendLine("🏆 使用最多的应用：")
            val topApps = report.optJSONArray("topApps")
            if (topApps != null && topApps.length() > 0) {
                for (i in 0 until minOf(topApps.length(), 5)) {
                    val app = topApps.getJSONObject(i)
                    sb.appendLine("  ${i + 1}. ${app.optString("appName")} - ${app.optLong("minutes")}分钟")
                }
            }
            sb.appendLine()

            // 超时状态
            val ds = report.optJSONObject("dailySummary")
            if (ds != null && ds.optBoolean("limitExceeded", false)) {
                sb.appendLine("⚠️ 今日已超出每日限额！")
                sb.appendLine("   停用模式：${ds.optString("stopMode", "none")}")
                sb.appendLine()
            }

        } else {
            // 周报
            val totalH = report.optString("weekTotalHours", "0")
            val avgH = report.optString("averageDailyMinutes", "0")
            val exceedDays = report.optInt("exceedDays", 0)

            sb.appendLine("📱 本周总使用时长：${totalH} 小时")
            sb.appendLine("📊 日均使用时长：${avgH} 分钟")
            sb.appendLine("⚠️  超时天数：${exceedDays}/7")
            sb.appendLine()

            // 日趋势
            sb.appendLine("📈 每日趋势：")
            val dailySummaries = report.optJSONArray("dailySummaries")
            if (dailySummaries != null) {
                for (i in 0 until dailySummaries.length()) {
                    val day = dailySummaries.getJSONObject(i)
                    val bar = "█".repeat(minOf((day.optLong("totalMinutes") / 10).toInt(), 30))
                    sb.appendLine("  ${day.optString("date")} |$bar ${day.optLong("totalMinutes")}分钟")
                }
            }
            sb.appendLine()

            // 周分类汇总
            val weekCat = report.optJSONObject("weekCategorySummary")
            if (weekCat != null) {
                sb.appendLine("📂 本周分类分布：")
                for (key in weekCat.keys().asSequence().sorted()) {
                    val cat = weekCat.getJSONObject(key)
                    sb.appendLine("  • ${getCategoryLabel(key)}：${cat.optString("hours")}h (${cat.optString("percent")}%)")
                }
            }
        }

        // 趋势
        val trend = report.optJSONObject("trend")
        if (trend != null) {
            val change = trend.optLong("change", 0)
            val changePct = trend.optString("changePercent", "N/A")
            val arrow = if (change > 0) "↑" else if (change < 0) "↓" else "→"
            sb.appendLine()
            sb.appendLine("📉 趋势对比：$arrow ${kotlin.math.abs(change)}分钟 ($changePct%)")
        }

        sb.appendLine()
        sb.appendLine("========================================")
        sb.appendLine("  报告由小趴菜自动生成")
        sb.appendLine("  生成时间：${formatTimestamp(report.optLong("generatedAt", 0))}")
        sb.appendLine("========================================")

        return sb.toString()
    }

    /**
     * 获取分类中文标签
     */
    private fun getCategoryLabel(category: String): String {
        return when (category) {
            "game" -> "🎮 游戏"
            "social" -> "💬 社交"
            "video" -> "📺 视频"
            "study" -> "📚 学习"
            "other" -> "📱 其他"
            else -> "📱 $category"
        }
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return "未知"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp * 1000))
    }
}
