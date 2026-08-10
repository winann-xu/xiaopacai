using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.Data.Sqlite;
using XiaopacaiParent.Models;

namespace XiaopacaiParent.Services;

/// <summary>
/// [TASK-D2-02] 策略引擎服务
///
/// 家长端核心业务逻辑：策略的 CRUD、版本管理、分发标记。
/// 所有策略存储于加密 SQLite 数据库，通过 P2P 同步到儿童端。
///
/// 策略类型说明：
/// - daily_limit: 每日总使用时长上限
/// - sleep_time: 就寝时段（期间设备锁定）
/// - whitelist: 白名单（始终允许的应用）
/// - blacklist: 黑名单（始终禁止的应用）
/// - category_limit: 按分类限制（如游戏≤60分钟/天）
/// </summary>
public class PolicyEngineService
{
    private readonly DatabaseService _db;

    public PolicyEngineService(DatabaseService db)
    {
        _db = db;
    }

    // ==================== CRUD 操作 ====================

    /// <summary>
    /// 获取所有策略（可按设备过滤）
    /// </summary>
    public List<PolicyConfig> GetAllPolicies(string deviceId = "")
    {
        var policies = new List<PolicyConfig>();

        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        if (string.IsNullOrEmpty(deviceId))
        {
            cmd.CommandText = "SELECT policy_data FROM policies WHERE is_active = 1 ORDER BY updated_at DESC";
        }
        else
        {
            cmd.CommandText = @"
                SELECT policy_data FROM policies
                WHERE (device_id = @deviceId OR device_id = '')
                  AND is_active = 1
                ORDER BY updated_at DESC";
            cmd.Parameters.AddWithValue("@deviceId", deviceId);
        }

        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            var json = reader.GetString(0);
            policies.Add(PolicyConfig.FromJson(json));
        }

        return policies;
    }

    /// <summary>
    /// 获取指定类型的策略
    /// </summary>
    /// <param name="policyType">策略类型</param>
    /// <param name="deviceId">设备 ID（空字符串 = 全局）</param>
    /// <param name="category">分类（仅 category_limit 类型需要区分）</param>
    public PolicyConfig? GetPolicy(string policyType, string deviceId = "", string category = "")
    {
        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        cmd.CommandText = @"
            SELECT policy_data FROM policies
            WHERE policy_type = @type
              AND (device_id = @deviceId OR device_id = '')
              AND (json_extract(policy_data, '$.category') = @category OR @category = '')
              AND is_active = 1
            ORDER BY device_id DESC LIMIT 1";
        cmd.Parameters.AddWithValue("@type", policyType);
        cmd.Parameters.AddWithValue("@deviceId", deviceId);
        cmd.Parameters.AddWithValue("@category", category);

        var result = cmd.ExecuteScalar() as string;
        return result != null ? PolicyConfig.FromJson(result) : null;
    }

    /// <summary>
    /// 保存或更新策略（UPSERT）
    /// </summary>
    public bool SavePolicy(PolicyConfig policy)
    {
        policy.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        policy.Version++;

        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        // 先检查是否存在（category_limit 类型按 category 区分，防止不同分类互相覆盖）
        cmd.CommandText = @"
            SELECT id FROM policies
            WHERE policy_type = @type
              AND device_id = @deviceId
              AND (json_extract(policy_data, '$.category') = @category OR @category = '')";
        cmd.Parameters.AddWithValue("@type", policy.PolicyType);
        cmd.Parameters.AddWithValue("@deviceId", policy.DeviceId);
        cmd.Parameters.AddWithValue("@category", policy.Category);

        var existingId = cmd.ExecuteScalar();

        // [BUG-0810-05] 清空参数，防止 INSERT/UPDATE 路径复用时参数冲突
        cmd.Parameters.Clear();

        if (existingId != null)
        {
            // 更新
            cmd.CommandText = @"
                UPDATE policies
                SET policy_data = @data,
                    is_active = @active,
                    updated_at = @updatedAt
                WHERE id = @id";
            cmd.Parameters.AddWithValue("@data", policy.ToJson());
            cmd.Parameters.AddWithValue("@active", policy.IsActive ? 1 : 0);
            cmd.Parameters.AddWithValue("@updatedAt", policy.UpdatedAt);
            cmd.Parameters.AddWithValue("@id", (long)existingId);
        }
        else
        {
            // 新建
            cmd.CommandText = @"
                INSERT INTO policies (device_id, policy_type, policy_data, is_active, created_at, updated_at)
                VALUES (@deviceId, @type, @data, @active, @createdAt, @updatedAt)";
            cmd.Parameters.AddWithValue("@deviceId", policy.DeviceId);
            cmd.Parameters.AddWithValue("@type", policy.PolicyType);
            cmd.Parameters.AddWithValue("@data", policy.ToJson());
            cmd.Parameters.AddWithValue("@active", policy.IsActive ? 1 : 0);
            cmd.Parameters.AddWithValue("@createdAt", policy.CreatedAt);
            cmd.Parameters.AddWithValue("@updatedAt", policy.UpdatedAt);
        }

        return cmd.ExecuteNonQuery() > 0;
    }

    /// <summary>
    /// 停用策略（软删除）
    /// </summary>
    public bool DeactivatePolicy(string policyType, string deviceId = "", string category = "")
    {
        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        cmd.CommandText = @"
            UPDATE policies
            SET is_active = 0, updated_at = @updatedAt
            WHERE policy_type = @type
              AND device_id = @deviceId
              AND (json_extract(policy_data, '$.category') = @category OR @category = '')";
        cmd.Parameters.AddWithValue("@updatedAt", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        cmd.Parameters.AddWithValue("@type", policyType);
        cmd.Parameters.AddWithValue("@deviceId", deviceId);
        cmd.Parameters.AddWithValue("@category", category);

        return cmd.ExecuteNonQuery() > 0;
    }

    /// <summary>
    /// 获取所有已修改的策略（版本号大于指定值的）
    /// 用于增量同步
    /// </summary>
    public List<PolicyConfig> GetModifiedPolicies(int sinceVersion, string deviceId = "")
    {
        var policies = new List<PolicyConfig>();

        using var conn = _db.GetConnection();
        using var cmd = conn.CreateCommand();

        cmd.CommandText = @"
            SELECT policy_data FROM policies
            WHERE is_active = 1
              AND (device_id = @deviceId OR device_id = '')
              AND json_extract(policy_data, '$.version') > @version
            ORDER BY updated_at ASC";
        cmd.Parameters.AddWithValue("@deviceId", deviceId);
        cmd.Parameters.AddWithValue("@version", sinceVersion);

        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            var json = reader.GetString(0);
            policies.Add(PolicyConfig.FromJson(json));
        }

        return policies;
    }

    // ==================== 策略预设模板 ====================

    /// <summary>
    /// 创建默认策略集（首次使用时调用）
    /// </summary>
    public void CreateDefaultPolicies(string deviceId = "")
    {
        // 检查是否已存在策略
        var existing = GetAllPolicies(deviceId);
        if (existing.Count > 0) return;

        var defaults = new List<PolicyConfig>
        {
            // 每日限额：2 小时
            new()
            {
                PolicyType = "daily_limit",
                DeviceId = deviceId,
                LimitMinutes = 120,
                Label = "默认每日限额"
            },
            // 就寝时段：21:00 - 07:00
            new()
            {
                PolicyType = "sleep_time",
                DeviceId = deviceId,
                SleepStart = "21:00",
                SleepEnd = "07:00",
                Label = "默认就寝时段"
            },
            // 游戏分类限额：1 小时
            new()
            {
                PolicyType = "category_limit",
                DeviceId = deviceId,
                Category = "game",
                CategoryLimitMinutes = 60,
                Label = "游戏类限额"
            },
            // 社交分类限额：1.5 小时
            new()
            {
                PolicyType = "category_limit",
                DeviceId = deviceId,
                Category = "social",
                CategoryLimitMinutes = 90,
                Label = "社交类限额"
            }
        };

        foreach (var policy in defaults)
        {
            SavePolicy(policy);
        }
    }

    // ==================== 策略同步（供 P2P 层调用） ====================

    /// <summary>
    /// 导出策略为同步消息 Payload
    /// 将所有活跃策略打包为 JSON Map
    /// </summary>
    public Dictionary<string, object> ExportForSync(string deviceId = "")
    {
        var policies = GetAllPolicies(deviceId);
        var result = new Dictionary<string, object>
        {
            ["timestamp"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            ["deviceId"] = deviceId,
            ["policyCount"] = policies.Count,
            ["policies"] = policies.Select(p => p.ToJson()).ToList()
        };
        return result;
    }

    /// <summary>
    /// 从同步消息导入策略
    /// </summary>
    public int ImportFromSync(List<string> policyJsonList)
    {
        int count = 0;
        foreach (var json in policyJsonList)
        {
            try
            {
                var policy = PolicyConfig.FromJson(json);
                if (SavePolicy(policy)) count++;
            }
            catch { /* 跳过解析失败的条目 */ }
        }
        return count;
    }
}
