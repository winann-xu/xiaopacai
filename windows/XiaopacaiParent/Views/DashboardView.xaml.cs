using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Navigation;
using XiaopacaiParent.Services;

namespace XiaopacaiParent.Views;

/// <summary>
/// [TASK-D3-01] 小趴菜家长端仪表盘页面
///
/// 展示已连接设备数、今日使用总览、超时停用状态、
/// 公告统计等关键指标，并提供快捷操作入口。
/// 数据来源：加密 SQLite 数据库实时查询。
/// </summary>
public partial class DashboardView : Page
{
    private readonly DatabaseService _databaseService;
    private readonly ReportService _reportService;

    public DashboardView(DatabaseService databaseService, ReportService reportService)
    {
        InitializeComponent();
        _databaseService = databaseService;
        _reportService = reportService;

        // 加载实时摘要数据
        LoadSummary();
    }

    /// <summary>
    /// 从数据库加载仪表盘摘要数据 [TASK-D3-01]
    /// </summary>
    private void LoadSummary()
    {
        try
        {
            using var conn = _databaseService.GetConnection();

            // 1. 已连接设备数
            using (var cmd = conn.CreateCommand())
            {
                cmd.CommandText = "SELECT COUNT(*) FROM devices WHERE is_active = 1";
                var count = Convert.ToInt64(cmd.ExecuteScalar());
                ConnectedCountText.Text = count.ToString();
            }

            // 2. 今日使用时长
            var today = DateTime.Now.ToString("yyyy-MM-dd");
            using (var cmd = conn.CreateCommand())
            {
                cmd.CommandText = @"
                    SELECT COALESCE(SUM(total_minutes), 0)
                    FROM usage_records WHERE date = $date";
                cmd.Parameters.AddWithValue("$date", today);
                var total = Convert.ToInt64(cmd.ExecuteScalar());
                TodayUsageText.Text = $"{total} 分钟";
            }

            // 3. 超时停用事件数（今日）
            using (var cmd = conn.CreateCommand())
            {
                cmd.CommandText = @"
                    SELECT COUNT(*) FROM timeout_events
                    WHERE event_type = 'timeout_start'
                    AND date(created_at, 'unixepoch') = $date";
                cmd.Parameters.AddWithValue("$date", today);
                var count = Convert.ToInt64(cmd.ExecuteScalar());
                TimeoutCountText.Text = count.ToString();
            }

            // 4. 公告数量
            using (var cmd = conn.CreateCommand())
            {
                cmd.CommandText = "SELECT COUNT(*) FROM announcements WHERE is_sent = 1";
                var count = Convert.ToInt64(cmd.ExecuteScalar());
                AnnouncementCountText.Text = count.ToString();
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"加载仪表盘数据失败: {ex.Message}");
            // 出错时显示占位符
            ConnectedCountText.Text = "--";
            TodayUsageText.Text = "--";
            TimeoutCountText.Text = "--";
            AnnouncementCountText.Text = "--";
        }
    }

    // === 快捷操作事件处理 ===

    private void OnTimeoutConfigClick(object sender, RoutedEventArgs e)
    {
        // [TASK-D2-02] 跳转到策略配置页
        NavigationService?.Navigate(new PolicyView());
    }

    private void OnAddDeviceClick(object sender, RoutedEventArgs e)
    {
        // [TASK-D1-04] 跳转到设备配对页面
        MessageBox.Show("设备添加需要 P2P 配对功能。\n请在儿童端打开小趴菜 App 并在同一 WiFi 下自动发现。",
            "添加设备", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void OnSendAnnouncementClick(object sender, RoutedEventArgs e)
    {
        // [TASK-D2-04] 跳转到公告管理页
        NavigationService?.Navigate(new AnnouncementView());
    }

    private void OnViewReportClick(object sender, RoutedEventArgs e)
    {
        // [TASK-D3-01] 跳转到报告页面
        NavigationService?.Navigate(new ReportView(_reportService));
    }
}
