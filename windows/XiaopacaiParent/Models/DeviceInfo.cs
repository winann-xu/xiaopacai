using System;

namespace XiaopacaiParent.Models;

/// <summary>
/// [TASK-D1-03] 儿童设备信息模型
///
/// 对应数据库 devices 表，记录每个被监护儿童设备的完整信息。
/// </summary>
public class DeviceInfo
{
    /// <summary>数据库主键</summary>
    public int Id { get; set; }

    /// <summary>设备唯一标识（如 XP-ABCD1234EF56）</summary>
    public string DeviceId { get; set; } = string.Empty;

    /// <summary>设备名称（家长自定义，如"小明的小米手机"）</summary>
    public string DeviceName { get; set; } = string.Empty;

    /// <summary>设备类型（android/ios，一期仅 android）</summary>
    public string DeviceType { get; set; } = "android";

    /// <summary>TLS 证书指纹（用于配对验证）</summary>
    public string CertFingerprint { get; set; } = string.Empty;

    /// <summary>最后已知 IP 地址</summary>
    public string LastIp { get; set; } = string.Empty;

    /// <summary>最后在线时间（Unix 时间戳）</summary>
    public long LastOnlineAt { get; set; }

    /// <summary>一次性配对码</summary>
    public string PairingCode { get; set; } = string.Empty;

    /// <summary>配对完成时间</summary>
    public long PairedAt { get; set; }

    /// <summary>是否活跃（1=活跃 0=已删除）</summary>
    public int IsActive { get; set; } = 1;

    /// <summary>创建时间</summary>
    public long CreatedAt { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    /// <summary>更新时间</summary>
    public long UpdatedAt { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    /// <summary>
    /// 是否已配对成功（有证书指纹即视为已配对）
    /// </summary>
    public bool IsPaired => !string.IsNullOrEmpty(CertFingerprint);

    /// <summary>
    /// 格式化最后在线时间为友好文本
    /// </summary>
    public string LastOnlineText
    {
        get
        {
            if (LastOnlineAt == 0) return "从未在线";
            var dt = DateTimeOffset.FromUnixTimeSeconds(LastOnlineAt);
            var span = DateTimeOffset.UtcNow - dt;
            if (span.TotalMinutes < 1) return "刚刚";
            if (span.TotalMinutes < 60) return $"{(int)span.TotalMinutes} 分钟前";
            if (span.TotalHours < 24) return $"{(int)span.TotalHours} 小时前";
            return dt.LocalDateTime.ToString("yyyy-MM-dd HH:mm");
        }
    }
}
