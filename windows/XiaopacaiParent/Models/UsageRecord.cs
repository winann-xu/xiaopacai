using System;

namespace XiaopacaiParent.Models;

/// <summary>
/// [TASK-D1-03] 应用使用记录模型
///
/// 记录单个应用在指定日期的使用时长。
/// 数据由儿童端采集并通过 P2P 同步到家长端。
/// </summary>
public class UsageRecord
{
    /// <summary>数据库主键</summary>
    public int Id { get; set; }

    /// <summary>设备 ID</summary>
    public string DeviceId { get; set; } = string.Empty;

    /// <summary>应用包名（如 com.tencent.mm）</summary>
    public string PackageName { get; set; } = string.Empty;

    /// <summary>应用名称（如"微信"）</summary>
    public string AppName { get; set; } = string.Empty;

    /// <summary>统计日期（yyyy-MM-dd）</summary>
    public string Date { get; set; } = string.Empty;

    /// <summary>累计使用分钟数</summary>
    public int TotalMinutes { get; set; }

    /// <summary>应用分类（game/social/video/study/other）</summary>
    public string Category { get; set; } = "other";

    /// <summary>同步时间</summary>
    public long SyncedAt { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
}
