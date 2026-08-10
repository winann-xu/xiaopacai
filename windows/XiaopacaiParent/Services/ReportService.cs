using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace XiaopacaiParent.Services;

/// <summary>
/// [TASK-D3-01] 报告服务 — 从家长端数据库生成使用报告
///
/// 从同步的儿童端使用数据生成日报/周报，支持：
/// - 按设备/日期聚合统计
/// - 分类占比计算
/// - Top-N 应用排行
/// - 趋势对比（环比）
/// - 报告导出为 JSON/文本
/// </summary>
public class ReportService
{
    private readonly DatabaseService _databaseService;

    public ReportService(DatabaseService databaseService)
    {
        _databaseService = databaseService;
    }

    /// <summary>
    /// 生成日报 — 汇总指定设备在指定日期的使用情况
    /// </summary>
    /// <param name="deviceId">设备 ID</param>
    /// <param name="date">日期（yyyy-MM-dd），默认今天</param>
    /// <returns>日报 JSON</returns>
    public string GenerateDailyReport(string deviceId, string? date = null)
    {
        date ??= DateTime.Now.ToString("yyyy-MM-dd");

        var report = new Dictionary<string, object?>
        {
            ["reportType"] = "daily",
            ["deviceId"] = deviceId,
            ["date"] = date,
            ["generatedAt"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
        };

        // 查询当日使用记录
        var records = GetUsageRecords(deviceId, date);
        var totalMinutes = records.Sum(r => r.TotalMinutes);

        report["totalMinutes"] = totalMinutes;
        report["totalHours"] = $"{totalMinutes / 60.0:F1}";

        // 分类汇总
        var categorySummary = records
            .GroupBy(r => r.Category)
            .ToDictionary(
                g => g.Key,
                g => new
                {
                    minutes = g.Sum(r => r.TotalMinutes),
                    hours = $"{g.Sum(r => r.TotalMinutes) / 60.0:F1}",
                    percent = totalMinutes > 0
                        ? $"{g.Sum(r => r.TotalMinutes) * 100.0 / totalMinutes:F1}"
                        : "0.0"
                });

        report["categorySummary"] = categorySummary;

        // Top-N 应用排行
        var topApps = records
            .OrderByDescending(r => r.TotalMinutes)
            .Take(10)
            .Select(r => new
            {
                appName = r.AppName,
                packageName = r.PackageName,
                minutes = r.TotalMinutes,
                category = r.Category
            })
            .ToList();

        report["topApps"] = topApps;

        // 与昨日对比
        var yesterday = DateTime.Parse(date).AddDays(-1).ToString("yyyy-MM-dd");
        var yesterdayRecords = GetUsageRecords(deviceId, yesterday);
        var yesterdayTotal = yesterdayRecords.Sum(r => r.TotalMinutes);

        report["trend"] = new
        {
            yesterdayTotal,
            todayTotal = totalMinutes,
            change = totalMinutes - yesterdayTotal,
            changePercent = yesterdayTotal > 0
                ? $"{(totalMinutes - yesterdayTotal) * 100.0 / yesterdayTotal:F1}"
                : "N/A"
        };

        return JsonSerializer.Serialize(report, new JsonSerializerOptions
        {
            WriteIndented = true,
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase
        });
    }

    /// <summary>
    /// 生成周报 — 汇总指定设备最近7天的使用情况
    /// </summary>
    /// <param name="deviceId">设备 ID</param>
    /// <param name="endDate">截止日期（yyyy-MM-dd），默认今天</param>
    /// <returns>周报 JSON</returns>
    public string GenerateWeeklyReport(string deviceId, string? endDate = null)
    {
        endDate ??= DateTime.Now.ToString("yyyy-MM-dd");
        var endDt = DateTime.Parse(endDate);
        var startDt = endDt.AddDays(-6);  // 含当天共7天

        var report = new Dictionary<string, object?>
        {
            ["reportType"] = "weekly",
            ["deviceId"] = deviceId,
            ["startDate"] = startDt.ToString("yyyy-MM-dd"),
            ["endDate"] = endDate,
            ["generatedAt"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
        };

        // 每日汇总
        var dailySummaries = new List<object>();
        var weekCategoryMinutes = new Dictionary<string, long>();
        long weekTotalMinutes = 0;
        int exceedDays = 0;

        for (var day = startDt; day <= endDt; day = day.AddDays(1))
        {
            var dayStr = day.ToString("yyyy-MM-dd");
            var dayRecords = GetUsageRecords(deviceId, dayStr);
            var dayTotal = dayRecords.Sum(r => (long)r.TotalMinutes);
            weekTotalMinutes += dayTotal;

            // 分类统计
            var dayCatDict = new Dictionary<string, long>();
            foreach (var r in dayRecords)
            {
                if (!dayCatDict.ContainsKey(r.Category))
                    dayCatDict[r.Category] = 0;
                dayCatDict[r.Category] += r.TotalMinutes;

                if (!weekCategoryMinutes.ContainsKey(r.Category))
                    weekCategoryMinutes[r.Category] = 0;
                weekCategoryMinutes[r.Category] += r.TotalMinutes;
            }

            dailySummaries.Add(new
            {
                date = dayStr,
                totalMinutes = dayTotal,
                totalHours = $"{dayTotal / 60.0:F1}",
                recordCount = dayRecords.Count,
                categories = dayCatDict
            });
        }

        report["dailySummaries"] = dailySummaries;
        report["weekTotalMinutes"] = weekTotalMinutes;
        report["weekTotalHours"] = $"{weekTotalMinutes / 60.0:F1}";
        report["averageDailyMinutes"] = $"{weekTotalMinutes / 7.0:F1}";
        report["exceedDays"] = exceedDays;

        // 周分类汇总
        var weekCatSummary = weekCategoryMinutes.ToDictionary(
            kv => kv.Key,
            kv => new
            {
                minutes = kv.Value,
                hours = $"{kv.Value / 60.0:F1}",
                percent = weekTotalMinutes > 0
                    ? $"{kv.Value * 100.0 / weekTotalMinutes:F1}"
                    : "0.0"
            });
        report["weekCategorySummary"] = weekCatSummary;

        // 周 Top-N 应用
        var weekAppMap = new Dictionary<string, (string AppName, long Minutes)>();
        for (var day = startDt; day <= endDt; day = day.AddDays(1))
        {
            var dayRecords = GetUsageRecords(deviceId, day.ToString("yyyy-MM-dd"));
            foreach (var r in dayRecords)
            {
                if (!weekAppMap.ContainsKey(r.PackageName))
                    weekAppMap[r.PackageName] = (r.AppName, 0);
                weekAppMap[r.PackageName] = (
                    r.AppName,
                    weekAppMap[r.PackageName].Minutes + r.TotalMinutes
                );
            }
        }

        var topApps = weekAppMap
            .OrderByDescending(kv => kv.Value.Minutes)
            .Take(10)
            .Select(kv => new
            {
                appName = kv.Value.AppName,
                packageName = kv.Key,
                minutes = kv.Value.Minutes,
                hours = $"{kv.Value.Minutes / 60.0:F1}"
            })
            .ToList();
        report["topApps"] = topApps;

        // 与上周对比
        var lastWeekStart = startDt.AddDays(-7);
        long lastWeekTotal = 0;
        for (var day = lastWeekStart; day < startDt; day = day.AddDays(1))
        {
            var dayRecords = GetUsageRecords(deviceId, day.ToString("yyyy-MM-dd"));
            lastWeekTotal += dayRecords.Sum(r => (long)r.TotalMinutes);
        }

        report["trend"] = new
        {
            lastWeekTotal,
            thisWeekTotal = weekTotalMinutes,
            change = weekTotalMinutes - lastWeekTotal,
            changePercent = lastWeekTotal > 0
                ? $"{(weekTotalMinutes - lastWeekTotal) * 100.0 / lastWeekTotal:F1}"
                : "N/A"
        };

        return JsonSerializer.Serialize(report, new JsonSerializerOptions
        {
            WriteIndented = true,
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase
        });
    }

    /// <summary>
    /// 获取所有已同步设备的 ID 列表
    /// </summary>
    public List<string> GetDeviceIds()
    {
        var deviceIds = new List<string>();
        try
        {
            using var conn = _databaseService.GetConnection();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "SELECT DISTINCT device_id FROM usage_records ORDER BY device_id";
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                deviceIds.Add(reader.GetString(0));
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"获取设备列表失败: {ex.Message}");
        }
        return deviceIds;
    }

    /// <summary>
    /// 获取指定设备的每日摘要（最近 N 天）
    /// </summary>
    public List<Dictionary<string, object?>> GetDeviceDailySummaries(
        string deviceId, int days = 7)
    {
        var summaries = new List<Dictionary<string, object?>>();
        try
        {
            using var conn = _databaseService.GetConnection();
            for (int i = days - 1; i >= 0; i--)
            {
                var date = DateTime.Now.AddDays(-i).ToString("yyyy-MM-dd");
                using var cmd = conn.CreateCommand();
                cmd.CommandText = @"
                    SELECT COALESCE(SUM(total_minutes), 0)
                    FROM usage_records
                    WHERE device_id = $deviceId AND date = $date";
                cmd.Parameters.AddWithValue("$deviceId", deviceId);
                cmd.Parameters.AddWithValue("$date", date);
                var total = Convert.ToInt64(cmd.ExecuteScalar());

                summaries.Add(new Dictionary<string, object?>
                {
                    ["date"] = date,
                    ["totalMinutes"] = total,
                    ["totalHours"] = $"{total / 60.0:F1}"
                });
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"获取摘要失败: {ex.Message}");
        }
        return summaries;
    }

    /// <summary>
    /// 查询使用记录
    /// </summary>
    private List<UsageRecordDto> GetUsageRecords(string deviceId, string date)
    {
        var records = new List<UsageRecordDto>();
        try
        {
            using var conn = _databaseService.GetConnection();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = @"
                SELECT package_name, app_name, total_minutes, category
                FROM usage_records
                WHERE device_id = $deviceId AND date = $date
                ORDER BY total_minutes DESC";
            cmd.Parameters.AddWithValue("$deviceId", deviceId);
            cmd.Parameters.AddWithValue("$date", date);

            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                records.Add(new UsageRecordDto
                {
                    PackageName = reader.GetString(0),
                    AppName = reader.GetString(1),
                    TotalMinutes = reader.GetInt32(2),
                    Category = reader.GetString(3)
                });
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"查询使用记录失败: {ex.Message}");
        }
        return records;
    }

    /// <summary>
    /// 使用记录 DTO（内部用）
    /// </summary>
    private class UsageRecordDto
    {
        public string PackageName { get; set; } = "";
        public string AppName { get; set; } = "";
        public int TotalMinutes { get; set; }
        public string Category { get; set; } = "other";
    }
}
