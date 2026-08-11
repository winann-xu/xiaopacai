using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using XiaopacaiParent.Services;
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

    public MainWindow()
    {
        // [TASK-D3-02] 从加密配置文件加载数据库密码（DPAPI 保护）
        var appDataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "XiaopacaiParent");
        var dbPassword = GetOrCreateDbPassword(appDataDir);

        _databaseService = new DatabaseService(appDataDir, dbPassword);
        _reportService = new ReportService(_databaseService);

        InitializeComponent();

        // 启动 P2P 监听
        StartP2PListener();

        // 启动后默认显示仪表盘
        ContentFrame.Navigate(new DashboardView(_databaseService, _reportService));
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
        _p2pService?.Dispose();
        _databaseService?.Dispose();
        base.OnClosed(e);
    }
}
