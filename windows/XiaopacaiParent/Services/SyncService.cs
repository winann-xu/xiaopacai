using System;
using System.Collections.Generic;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using XiaopacaiParent.Models;

namespace XiaopacaiParent.Services;

/// <summary>
/// [TASK-D2-05] P2P 数据同步服务（家长端）
///
/// 负责与儿童端的双向数据同步：
/// 1. 下行（家长→儿童）：策略配置推送、公告推送
/// 2. 上行（儿童→家长）：使用时长报告接收
///
/// 同步协议使用 JSON 消息格式，通过 P2P 连接传输。
/// </summary>
public class SyncService
{
    private readonly DatabaseService _db;
    private readonly PolicyEngineService _policyEngine;
    private readonly AnnouncementService _announcementService;

    public SyncService(DatabaseService db)
    {
        _db = db;
        _policyEngine = new PolicyEngineService(db);
        _announcementService = new AnnouncementService(db);
    }

    // ==================== 下行：策略推送 ====================

    /// <summary>
    /// 构建策略推送消息（全部有效策略）
    /// </summary>
    public string BuildPolicyPushMessage(string deviceId)
    {
        var export = _policyEngine.ExportForSync(deviceId);
        var message = new Dictionary<string, object>
        {
            ["type"] = "policy_update",
            ["payload"] = new Dictionary<string, object>
            {
                ["deviceId"] = deviceId,
                ["policies"] = export["policies"],
                ["timestamp"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
            }
        };
        return JsonSerializer.Serialize(message);
    }

    // ==================== 下行：公告推送 ====================

    /// <summary>
    /// 构建公告推送消息
    /// </summary>
    public string BuildAnnouncementPushMessage(string? deviceId = null)
    {
        var announcements = _announcementService.ExportForSync(deviceId);
        var message = new Dictionary<string, object>
        {
            ["type"] = "announcement_push",
            ["payload"] = new Dictionary<string, object>
            {
                ["announcements"] = announcements,
                ["timestamp"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
            }
        };
        return JsonSerializer.Serialize(message);
    }

    // ==================== 上行：使用时长接收 ====================

    /// <summary>
    /// 处理从儿童端接收的使用时长报告
    /// </summary>
    public int HandleUsageReport(string deviceId, string recordsJson)
    {
        try
        {
            var records = JsonSerializer.Deserialize<List<UsageReportEntry>>(recordsJson);
            if (records == null) return 0;

            int count = 0;
            using var conn = _db.GetConnection();

            foreach (var record in records)
            {
                using var cmd = conn.CreateCommand();
                cmd.CommandText = @"
                    INSERT OR REPLACE INTO usage_records
                    (device_id, package_name, app_name, date, total_minutes, category, synced_at)
                    VALUES (@deviceId, @pkg, @app, @date, @minutes, @category, @syncedAt)";
                cmd.Parameters.AddWithValue("@deviceId", deviceId);
                cmd.Parameters.AddWithValue("@pkg", record.PackageName);
                cmd.Parameters.AddWithValue("@app", record.AppName);
                cmd.Parameters.AddWithValue("@date", record.Date);
                cmd.Parameters.AddWithValue("@minutes", record.TotalMinutes);
                cmd.Parameters.AddWithValue("@category", record.Category);
                cmd.Parameters.AddWithValue("@syncedAt", DateTimeOffset.UtcNow.ToUnixTimeSeconds());

                count += cmd.ExecuteNonQuery();
            }

            return count;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[SyncService] 使用报告处理失败: {ex.Message}");
            return 0;
        }
    }

    /// <summary>
    /// 构建同步确认消息
    /// </summary>
    public string BuildSyncAck(int syncedCount)
    {
        var message = new Dictionary<string, object>
        {
            ["type"] = "sync_ack",
            ["payload"] = new Dictionary<string, object>
            {
                ["syncedCount"] = syncedCount,
                ["timestamp"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
            }
        };
        return JsonSerializer.Serialize(message);
    }
}

/// <summary>
/// [TASK-D2-05] 使用报告条目（从儿童端接收）
/// </summary>
public class UsageReportEntry
{
    public string PackageName { get; set; } = string.Empty;
    public string AppName { get; set; } = string.Empty;
    public string Date { get; set; } = string.Empty;
    public long TotalMinutes { get; set; }
    public string Category { get; set; } = "other";
}
