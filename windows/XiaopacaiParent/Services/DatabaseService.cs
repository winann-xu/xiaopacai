using System;
using System.IO;
using Microsoft.Data.Sqlite;

namespace XiaopacaiParent.Services;

/// <summary>
/// [TASK-D1-03] 小趴菜家长端数据库服务
///
/// 使用 SQLite + SQLCipher 加密存储所有家长端数据：
/// - 儿童设备信息与配对凭据
/// - 使用时长记录（从儿童端同步）
/// - 策略配置（每日限额、白名单、就寝时段等）
/// - 公告历史
/// - 超时停用事件日志
///
/// 加密方案：SQLCipher 256-bit AES + 可选 DPAPI 二次保护
/// </summary>
public class DatabaseService : IDisposable
{
    /// <summary>数据库连接字符串模板</summary>
    private readonly string _connectionString;

    /// <summary>数据库文件路径</summary>
    private readonly string _dbPath;

    public DatabaseService(string dataDir, string password)
    {
        _dbPath = Path.Combine(dataDir, "xiaopacai_parent.db");

        // 确保数据目录存在
        Directory.CreateDirectory(dataDir);

        // 构建 SQLCipher 加密连接字符串
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = _dbPath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Password = password  // SQLCipher 密钥
        }.ToString();

        // 初始化数据库表结构
        InitializeDatabase();
    }

    /// <summary>
    /// 初始化数据库表结构
    /// 首次运行时创建所有必需的表与索引
    /// </summary>
    private void InitializeDatabase()
    {
        using var connection = new SqliteConnection(_connectionString);
        connection.Open();

        // 启用 WAL 模式（更好的并发性能）
        using var pragmaCmd = connection.CreateCommand();
        pragmaCmd.CommandText = "PRAGMA journal_mode=WAL;";
        pragmaCmd.ExecuteNonQuery();

        using var cmd = connection.CreateCommand();

        // === 表1：儿童设备信息 ===
        cmd.CommandText = @"
            CREATE TABLE IF NOT EXISTS devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL UNIQUE,          -- 设备唯一标识
                device_name TEXT NOT NULL DEFAULT '',    -- 设备名称（家长自定义）
                device_type TEXT NOT NULL DEFAULT 'android', -- 设备类型
                cert_fingerprint TEXT NOT NULL DEFAULT '',   -- 证书指纹（防中间人）
                last_ip TEXT NOT NULL DEFAULT '',        -- 最后已知 IP
                last_online_at INTEGER NOT NULL DEFAULT 0,   -- 最后在线时间
                pairing_code TEXT NOT NULL DEFAULT '',   -- 配对码（一次性）
                paired_at INTEGER NOT NULL DEFAULT 0,    -- 配对时间戳
                is_active INTEGER NOT NULL DEFAULT 1,    -- 是否活跃
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            );
        ";
        cmd.ExecuteNonQuery();

        // === 表2：每日使用策略 ===
        cmd.CommandText = @"
            CREATE TABLE IF NOT EXISTS policies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,                 -- 适用设备（'' 表示全局策略）
                policy_type TEXT NOT NULL,               -- 类型：daily_limit/sleep_time/whitelist
                policy_data TEXT NOT NULL,               -- JSON 格式的策略内容
                is_active INTEGER NOT NULL DEFAULT 1,   -- 是否启用
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            );
        ";
        cmd.ExecuteNonQuery();

        // === 表3：使用时长记录（从儿童端同步） ===
        cmd.CommandText = @"
            CREATE TABLE IF NOT EXISTS usage_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL DEFAULT '',
                date TEXT NOT NULL,                      -- 日期 yyyy-MM-dd
                total_minutes INTEGER NOT NULL DEFAULT 0,
                category TEXT NOT NULL DEFAULT 'other',
                synced_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                UNIQUE(device_id, package_name, date)
            );
        ";
        cmd.ExecuteNonQuery();

        // === 表4：公告历史 ===
        cmd.CommandText = @"
            CREATE TABLE IF NOT EXISTS announcements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                announcement_id TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL DEFAULT '',
                target_devices TEXT NOT NULL DEFAULT '[]', -- JSON 数组，[]=全部设备
                priority INTEGER NOT NULL DEFAULT 0,
                is_sent INTEGER NOT NULL DEFAULT 0,
                expires_at INTEGER NOT NULL DEFAULT 0,    -- 过期时间戳（0=永不过期）
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            );
        ";
        cmd.ExecuteNonQuery();

        // 兼容旧表：尝试添加 expires_at 列（如果不存在）
        try
        {
            cmd.CommandText = "ALTER TABLE announcements ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0;";
            cmd.ExecuteNonQuery();
        }
        catch { /* 列已存在则忽略 */ }

        // === 表5：超时停用事件日志 ===
        cmd.CommandText = @"
            CREATE TABLE IF NOT EXISTS timeout_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                event_type TEXT NOT NULL,                -- timeout_start/timeout_end/exemption
                stop_mode TEXT NOT NULL DEFAULT 'full',  -- full/partial
                triggered_by TEXT NOT NULL DEFAULT 'system', -- system/manual
                detail TEXT NOT NULL DEFAULT '{}',       -- JSON 格式的事件详情
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            );
        ";
        cmd.ExecuteNonQuery();

        // === 索引 ===
        cmd.CommandText = "CREATE INDEX IF NOT EXISTS idx_devices_active ON devices(is_active);";
        cmd.ExecuteNonQuery();

        cmd.CommandText = "CREATE INDEX IF NOT EXISTS idx_usage_device_date ON usage_records(device_id, date);";
        cmd.ExecuteNonQuery();

        cmd.CommandText = "CREATE INDEX IF NOT EXISTS idx_timeout_device ON timeout_events(device_id, created_at);";
        cmd.ExecuteNonQuery();
    }

    /// <summary>
    /// 获取数据库连接
    /// 每次调用返回新连接（线程安全，由 SQLite 连接池管理）
    /// </summary>
    public SqliteConnection GetConnection()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        return connection;
    }

    /// <summary>
    /// [SEC-P1] 获取已注册设备的证书指纹（P2P 指纹绑定，红线 R3.x）
    /// </summary>
    /// <param name="deviceId">设备唯一标识</param>
    /// <returns>64 位小写十六进制 SHA-256 指纹；未注册返回 null</returns>
    public string? GetDeviceFingerprint(string deviceId)
    {
        using var connection = GetConnection();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT cert_fingerprint FROM devices WHERE device_id = $id LIMIT 1;";
        cmd.Parameters.AddWithValue("$id", deviceId);

        var result = cmd.ExecuteScalar() as string;
        return string.IsNullOrEmpty(result) ? null : result;
    }

    /// <summary>
    /// [SEC-P1] 注册/更新设备（首次配对绑定证书指纹）
    ///
    /// 新设备插入；已存在设备更新设备名、证书指纹与最后在线时间
    /// （重新配对场景：儿童端数据重置后证书变化，凭配对码换绑新指纹）。
    /// </summary>
    /// <param name="deviceId">设备唯一标识</param>
    /// <param name="deviceName">设备名称</param>
    /// <param name="certFingerprint">客户端证书 SHA-256 指纹（64 位小写十六进制）</param>
    public void UpsertDevice(string deviceId, string deviceName, string certFingerprint)
    {
        using var connection = GetConnection();

        using (var update = connection.CreateCommand())
        {
            update.CommandText = @"
                UPDATE devices
                SET device_name = $name,
                    cert_fingerprint = $fp,
                    last_online_at = strftime('%s', 'now'),
                    updated_at = strftime('%s', 'now')
                WHERE device_id = $id;";
            update.Parameters.AddWithValue("$name", deviceName);
            update.Parameters.AddWithValue("$fp", certFingerprint);
            update.Parameters.AddWithValue("$id", deviceId);

            if (update.ExecuteNonQuery() > 0) return;  // 已存在：更新完成
        }

        // 新设备：插入
        using var insert = connection.CreateCommand();
        insert.CommandText = @"
            INSERT INTO devices
            (device_id, device_name, device_type, cert_fingerprint, last_online_at, paired_at, is_active)
            VALUES ($id, $name, 'android', $fp, strftime('%s', 'now'), strftime('%s', 'now'), 1);";
        insert.Parameters.AddWithValue("$id", deviceId);
        insert.Parameters.AddWithValue("$name", deviceName);
        insert.Parameters.AddWithValue("$fp", certFingerprint);
        insert.ExecuteNonQuery();
    }

    /// <summary>
    /// [SEC-P1] 检查 devices 表是否为空（用于旧库迁移时判断目标库是否已初始化）
    /// </summary>
    public bool HasAnyDevices()
    {
        using var connection = GetConnection();
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM devices;";
        return Convert.ToInt64(cmd.ExecuteScalar()) > 0;
    }

    public void Dispose()
    {
        // SQLite 连接由调用方管理，此处无需额外清理
        GC.SuppressFinalize(this);
    }
}
