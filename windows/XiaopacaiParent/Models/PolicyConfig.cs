using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace XiaopacaiParent.Models;

/// <summary>
/// [TASK-D2-02] 策略配置模型
///
/// 家长端策略引擎的核心数据结构。
/// 支持五种策略类型：每日限额、就寝时段、白名单、黑名单、分类限制。
/// 所有策略以 JSON 格式存储和同步。
/// </summary>
public class PolicyConfig
{
    /// <summary>策略主键</summary>
    [JsonPropertyName("id")]
    public int Id { get; set; }

    /// <summary>策略类型：daily_limit / sleep_time / whitelist / blacklist / category_limit</summary>
    [JsonPropertyName("policyType")]
    public string PolicyType { get; set; } = string.Empty;

    /// <summary>适用设备 ID（空字符串表示全局策略）</summary>
    [JsonPropertyName("deviceId")]
    public string DeviceId { get; set; } = string.Empty;

    /// <summary>是否启用</summary>
    [JsonPropertyName("isActive")]
    public bool IsActive { get; set; } = true;

    /// <summary>策略版本号（增量同步用）</summary>
    [JsonPropertyName("version")]
    public int Version { get; set; } = 1;

    /// <summary>创建时间戳</summary>
    [JsonPropertyName("createdAt")]
    public long CreatedAt { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    /// <summary>更新时间戳</summary>
    [JsonPropertyName("updatedAt")]
    public long UpdatedAt { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    // === 具体策略数据（按类型不同使用不同字段） ===

    /// <summary>每日限额（分钟），用于 daily_limit 类型</summary>
    [JsonPropertyName("limitMinutes")]
    public int LimitMinutes { get; set; } = 120;

    /// <summary>就寝开始时间（HH:mm），用于 sleep_time 类型</summary>
    [JsonPropertyName("sleepStart")]
    public string SleepStart { get; set; } = "21:00";

    /// <summary>就寝结束时间（HH:mm），用于 sleep_time 类型</summary>
    [JsonPropertyName("sleepEnd")]
    public string SleepEnd { get; set; } = "07:00";

    /// <summary>应用包名列表（JSON 数组），用于 whitelist/blacklist 类型</summary>
    [JsonPropertyName("packageNames")]
    public List<string> PackageNames { get; set; } = new();

    /// <summary>应用分类（game/social/video/study/other），用于 category_limit 类型</summary>
    [JsonPropertyName("category")]
    public string Category { get; set; } = string.Empty;

    /// <summary>分类限额（分钟），用于 category_limit 类型</summary>
    [JsonPropertyName("categoryLimitMinutes")]
    public int CategoryLimitMinutes { get; set; } = 60;

    /// <summary>策略标签（备注/说明）</summary>
    [JsonPropertyName("label")]
    public string Label { get; set; } = string.Empty;

    /// <summary>
    /// 序列化为 JSON 字符串（用于数据库存储和 P2P 同步）
    /// </summary>
    public string ToJson()
    {
        return JsonSerializer.Serialize(this, new JsonSerializerOptions
        {
            WriteIndented = false,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        });
    }

    /// <summary>
    /// 从 JSON 字符串反序列化
    /// </summary>
    public static PolicyConfig FromJson(string json)
    {
        return JsonSerializer.Deserialize<PolicyConfig>(json)
               ?? new PolicyConfig();
    }

    /// <summary>
    /// 生成策略摘要（用于 UI 展示）
    /// </summary>
    public string GetSummary()
    {
        return PolicyType switch
        {
            "daily_limit" => $"每日限额 {LimitMinutes} 分钟",
            "sleep_time" => $"就寝时段 {SleepStart}-{SleepEnd}",
            "whitelist" => $"白名单 {PackageNames.Count} 个应用",
            "blacklist" => $"黑名单 {PackageNames.Count} 个应用",
            "category_limit" => $"{Category} 类限额 {CategoryLimitMinutes} 分钟",
            _ => "未知策略类型"
        };
    }
}
