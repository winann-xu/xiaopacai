using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Data.Sqlite;
using XiaopacaiParent.Services;
using XiaopacaiParent.Services.P2P;
using XiaopacaiParent.Views;

namespace XiaopacaiParent;

/// <summary>
/// [TASK-D1-03] 小趴菜家长端主窗口
///
/// 左侧导航栏 + 右侧内容区布局。
/// 子页面通过 Frame 导航加载，保持页面状态。
/// 管理所有服务实例的生命周期。
/// </summary>
public partial class MainWindow : Window
{
    /// <summary>数据库服务（全局单例）</summary>
    private readonly DatabaseService _databaseService;

    /// <summary>报告服务</summary>
    private readonly ReportService _reportService;

    /// <summary>P2P 监听服务</summary>
    private P2PListenerService? _p2pService;

    /// <summary>UDP 广播服务（局域网发现）</summary>
    private P2PBroadcastService? _broadcastService;

    public MainWindow()
    {
        // [TASK-D3-02] 从加密配置文件加载数据库密码（DPAPI 保护）
        var appDataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "XiaopacaiParent");
        var dbPassword = GetOrCreateDbPassword(appDataDir);

        _databaseService = new DatabaseService(appDataDir, dbPassword);
        // [SEC-P1] 旧版明文密钥库（Xiaopacai 目录）数据一次性迁移到本 DPAPI 库
        MigrateLegacyDatabase(_databaseService);
        _reportService = new ReportService(_databaseService);

        InitializeComponent();

        // 启动 P2P 监听
        StartP2PListener();

        // [SEC-P1] 单数据库单监听：注入全局服务，供 PolicyView/AnnouncementView/
        // SettingsView 等通过 ((App)Application.Current) 取用（原 App.OnStartup
        // 会另建一套明文密钥库与第二个 9527 监听，已删除）
        ((App)Application.Current).DatabaseService = _databaseService;
        ((App)Application.Current).P2PService = _p2pService;

        // [SEC-P1] 启动 UDP 广播（局域网发现）：广播指纹必须取监听证书的真实指纹，
        // #43 起儿童端对无指纹首连一律拒绝，广播与监听证书不一致会导致配对永不成功
        var parentDeviceId = DeviceIdentity.GetOrCreateId(appDataDir);
        var broadcastFingerprint = _p2pService?.GetCertificateFingerprint() ?? "";
        _broadcastService = new P2PBroadcastService(parentDeviceId, 9527, broadcastFingerprint);
        _broadcastService.StartAsync();

        // 启动后默认显示仪表盘
        ContentFrame.Navigate(new DashboardView(_databaseService, _reportService));
    }

    /// <summary>
    /// [SEC-P1] 迁移旧版明文密钥库的数据到新 DPAPI 库（一次性）
    ///
    /// 旧版本 App.xaml.cs 在 %LocalAppData%\Xiaopacai 目录用明文 .dbkey 初始化
    /// 第二套数据库（双库缺陷），儿童端同步数据与策略/公告落在此旧库。
    /// 迁移成功且 devices 表复制完成后删除旧明文密钥文件与旧库文件；
    /// 任一环节失败则保留原文件（数据优先，不静默丢弃）。
    /// </summary>
    private static void MigrateLegacyDatabase(DatabaseService target)
    {
        var legacyDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Xiaopacai");
        var legacyKeyFile = Path.Combine(legacyDir, ".dbkey");
        var legacyDbPath = Path.Combine(legacyDir, "xiaopacai_parent.db");

        // 无旧版明文库则直接返回
        if (!File.Exists(legacyKeyFile) || !File.Exists(legacyDbPath)) return;

        try
        {
            // 新库已有设备数据时不迁移（避免覆盖新配对数据）
            if (target.HasAnyDevices())
            {
                System.Diagnostics.Debug.WriteLine("[SEC-P1] 新库已有设备数据，跳过旧库迁移");
                return;
            }

            var legacyPassword = File.ReadAllText(legacyKeyFile).Trim();
            var legacyCs = new SqliteConnectionStringBuilder
            {
                DataSource = legacyDbPath,
                Mode = SqliteOpenMode.ReadWrite,  // WAL 库可能需要读写 -shm
                Password = legacyPassword
            }.ToString();

            using var legacy = new SqliteConnection(legacyCs);
            legacy.Open();

            // 逐表复制（表名固定白名单，防止 SQL 注入/越界）
            var tables = new[] { "devices", "policies", "usage_records", "announcements", "timeout_events" };
            var migratedTables = new List<string>();

            foreach (var table in tables)
            {
                try
                {
                    using var read = legacy.CreateCommand();
                    read.CommandText = $"SELECT * FROM {table};";
                    using var reader = read.ExecuteReader();
                    while (reader.Read())
                    {
                        var columns = new string[reader.FieldCount];
                        for (var i = 0; i < reader.FieldCount; i++) columns[i] = reader.GetName(i);

                        var insertSql = $"INSERT INTO {table} ({string.Join(",", columns)}) " +
                                        $"VALUES ({string.Join(",", columns.Select(c => "$" + c))});";
                        using var conn = target.GetConnection();
                        using var insert = conn.CreateCommand();
                        insert.CommandText = insertSql;
                        for (var i = 0; i < columns.Length; i++)
                        {
                            insert.Parameters.AddWithValue("$" + columns[i],
                                reader.IsDBNull(i) ? (object)DBNull.Value : reader.GetValue(i));
                        }
                        insert.ExecuteNonQuery();
                    }
                    migratedTables.Add(table);
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"[SEC-P1] 旧库 {table} 表迁移失败: {ex.Message}");
                }
            }

            // 核心表 devices 迁移成功后清理旧明文密钥与旧库文件（红线 R4.x：生产密钥禁止明文落盘）
            if (migratedTables.Contains("devices"))
            {
                try
                {
                    File.Delete(legacyKeyFile);
                    File.Delete(legacyDbPath);
                    File.Delete(legacyDbPath + "-wal");
                    File.Delete(legacyDbPath + "-shm");
                    System.Diagnostics.Debug.WriteLine("[SEC-P1] 旧明文密钥库已迁移并清理");
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"[SEC-P1] 旧库文件清理失败: {ex.Message}");
                }
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[SEC-P1] 旧库迁移失败（保留原文件）: {ex.Message}");
        }
    }

    /// <summary>
    /// [TASK-D3-02] 获取或创建受 DPAPI 保护的数据库密码
    ///
    /// 密码以 DPAPI 加密形式存储，仅当前 Windows 用户可解密。
    /// 首次运行时自动生成随机强密码。
    /// </summary>
    private static string GetOrCreateDbPassword(string appDataDir)
    {
        Directory.CreateDirectory(appDataDir);
        var keyFile = Path.Combine(appDataDir, ".dbkey");

        try
        {
            if (File.Exists(keyFile))
            {
                // 解密已存储的数据库密码
                var encryptedKey = File.ReadAllText(keyFile);
                return CryptoService.UnprotectWithDpapi(encryptedKey);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"读取数据库密钥失败，将重新生成: {ex.Message}");
        }

        // 首次运行或解密失败：生成新密码并 DPAPI 保护存储
        var newPassword = CryptoService.GenerateDatabaseKey(32);
        try
        {
            var encrypted = CryptoService.ProtectWithDpapi(newPassword);
            File.WriteAllText(keyFile, encrypted);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"存储数据库密钥失败: {ex.Message}");
        }
        return newPassword;
    }

    /// <summary>
    /// 启动 P2P 监听服务（后台）
    /// </summary>
    private void StartP2PListener()
    {
        try
        {
            _p2pService = new P2PListenerService(_databaseService, 9527);
            _p2pService.Start();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"P2P 监听启动失败: {ex.Message}");
        }
    }

    /// <summary>
    /// 导航到仪表盘页面
    /// </summary>
    private void OnDashboardClick(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(new DashboardView(_databaseService, _reportService));
    }

    /// <summary>
    /// 导航到设备管理页面
    /// </summary>
    private void OnDevicesClick(object sender, RoutedEventArgs e)
    {
        // TODO: [TASK-D3-03] 实现设备管理页面
        ContentFrame.Navigate(new DashboardView(_databaseService, _reportService)); // 临时占位
    }

    /// <summary>
    /// 导航到策略配置页面
    /// </summary>
    private void OnPolicyClick(object sender, RoutedEventArgs e)
    {
        // [TASK-D2-02] 导航到策略配置页面
        ContentFrame.Navigate(new PolicyView());
    }

    /// <summary>
    /// 导航到公告管理页面
    /// </summary>
    private void OnAnnouncementsClick(object sender, RoutedEventArgs e)
    {
        // [TASK-D2-04] 导航到公告管理页面
        ContentFrame.Navigate(new AnnouncementView());
    }

    /// <summary>
    /// 导航到使用报告页面 [TASK-D3-01]
    /// </summary>
    private void OnReportsClick(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(new ReportView(_reportService));
    }

    /// <summary>
    /// 导航到设置页面
    /// </summary>
    private void OnSettingsClick(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(new SettingsView());
    }

    /// <summary>
    /// 释放资源
    /// </summary>
    protected override void OnClosed(EventArgs e)
    {
        _broadcastService?.Dispose();
        _p2pService?.Dispose();
        _databaseService?.Dispose();
        base.OnClosed(e);
    }
}
