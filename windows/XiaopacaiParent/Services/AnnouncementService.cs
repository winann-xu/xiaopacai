using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Data.Sqlite;

namespace XiaopacaiParent.Services;

/// <summary>
/// [TASK-D2-04] 公告服务
///
/// 管理家长端公告的创建、编辑、发送和状态跟踪。
/// 支持按设备定向推送，优先级标记，过期自动清理。
///
/// 公告优先级：
/// - 0: 普通（蓝色）
/// - 1: 重要（橙色）
/// - 2: 紧急（红色）
/// </summary>
public class AnnouncementService
{
    private readonly DatabaseService _db;

    public AnnouncementService(DatabaseService db)
    {
        _db = db;
    }

    /// <summary>
    /// 创建新公告
    /// </summary>
    public AnnouncementRecord Create(string title, string content, int priority = 0,
        List<string>? targetDevices = null, long expiresAt = 0)
    {
        var announcement = new AnnouncementRecord
        {
            AnnouncementId = Guid.NewGuid().ToString("N")[..12],
            Title = title,
            Content = content,
            Priority = priority,
            TargetDevices = targetDevices ?? new List<string>(),
            CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            ExpiresAt = expiresAt,
            IsSent = false
        };

        Save(announcement);
        return announcement;
    }

    /// <summary>
    /// 保存或更新公告
    /// </summary>
    public bool Save(AnnouncementRecord record)
    {
        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        cmd.CommandText = @"
            INSERT OR REPLACE INTO announcements
            (announcement_id, title, content, target_devices, priority, is_sent, created_at, expires_at)
            VALUES (@id, @title, @content, @targets, @priority, @sent, @createdAt, @expiresAt)";
        cmd.Parameters.AddWithValue("@id", record.AnnouncementId);
        cmd.Parameters.AddWithValue("@title", record.Title);
        cmd.Parameters.AddWithValue("@content", record.Content);
        cmd.Parameters.AddWithValue("@targets", JsonSerializer.Serialize(record.TargetDevices));
        cmd.Parameters.AddWithValue("@priority", record.Priority);
        cmd.Parameters.AddWithValue("@sent", record.IsSent ? 1 : 0);
        cmd.Parameters.AddWithValue("@createdAt", record.CreatedAt);
        cmd.Parameters.AddWithValue("@expiresAt", record.ExpiresAt);

        return cmd.ExecuteNonQuery() > 0;
    }

    /// <summary>
    /// 获取所有公告（按时间倒序）
    /// </summary>
    public List<AnnouncementRecord> GetAll(int limit = 50)
    {
        var results = new List<AnnouncementRecord>();

        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        cmd.CommandText = @"
            SELECT announcement_id, title, content, target_devices, priority,
                   is_sent, created_at, expires_at
            FROM announcements
            ORDER BY created_at DESC LIMIT @limit";
        cmd.Parameters.AddWithValue("@limit", limit);

        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            results.Add(new AnnouncementRecord
            {
                AnnouncementId = reader.GetString(0),
                Title = reader.GetString(1),
                Content = reader.GetString(2),
                TargetDevices = JsonSerializer.Deserialize<List<string>>(reader.GetString(3)) ?? new(),
                Priority = reader.GetInt32(4),
                IsSent = reader.GetInt32(5) == 1,
                CreatedAt = reader.GetInt64(6),
                ExpiresAt = reader.GetInt64(7)
            });
        }

        return results;
    }

    /// <summary>
    /// 标记公告为已发送
    /// </summary>
    public bool MarkSent(string announcementId)
    {
        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        cmd.CommandText = "UPDATE announcements SET is_sent = 1 WHERE announcement_id = @id";
        cmd.Parameters.AddWithValue("@id", announcementId);
        return cmd.ExecuteNonQuery() > 0;
    }

    /// <summary>
    /// 删除公告
    /// </summary>
    public bool Delete(string announcementId)
    {
        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        cmd.CommandText = "DELETE FROM announcements WHERE announcement_id = @id";
        cmd.Parameters.AddWithValue("@id", announcementId);
        return cmd.ExecuteNonQuery() > 0;
    }

    /// <summary>
    /// 清除过期公告
    /// </summary>
    public int CleanExpired()
    {
        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        cmd.CommandText = "DELETE FROM announcements WHERE expires_at > 0 AND expires_at < @now";
        cmd.Parameters.AddWithValue("@now", now);
        return cmd.ExecuteNonQuery();
    }

    /// <summary>
    /// 导出公告为 P2P 同步消息格式
    /// </summary>
    public List<Dictionary<string, object>> ExportForSync(string? deviceId = null)
    {
        var announcements = GetAll(20).FindAll(a =>
        {
            if (deviceId == null) return true;
            return a.TargetDevices.Count == 0 || a.TargetDevices.Contains(deviceId);
        });

        var result = new List<Dictionary<string, object>>();
        foreach (var a in announcements)
        {
            result.Add(new Dictionary<string, object>
            {
                ["id"] = a.AnnouncementId,
                ["title"] = a.Title,
                ["content"] = a.Content,
                ["priority"] = a.Priority,
                ["created_at"] = a.CreatedAt,
                ["expires_at"] = a.ExpiresAt
            });
        }
        return result;
    }
}

/// <summary>
/// [TASK-D2-04] 公告记录模型
/// </summary>
public class AnnouncementRecord
{
    public string AnnouncementId { get; set; } = string.Empty;
    public string Title { get; set; } = string.Empty;
    public string Content { get; set; } = string.Empty;
    public List<string> TargetDevices { get; set; } = new();
    public int Priority { get; set; } = 0;
    public bool IsSent { get; set; } = false;
    public long CreatedAt { get; set; }
    public long ExpiresAt { get; set; } = 0;
}
