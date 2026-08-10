using System;
using System.Windows;
using System.Windows.Controls;

namespace XiaopacaiParent.Views;

/// <summary>
/// [TASK-D1-03] 小趴菜家长端仪表盘页面
///
/// 展示已连接设备数、今日使用总览、超时停用状态、
/// 公告统计等关键指标，并提供快捷操作入口。
/// </summary>
public partial class DashboardView : Page
{
    public DashboardView()
    {
        InitializeComponent();

        // 加载摘要数据（当前为骨架占位）
        LoadSummary();
    }

    /// <summary>
    /// 加载仪表盘摘要数据
    /// TODO: [TASK-D1-05] 从数据库实时读取设备与使用数据
    /// </summary>
    private void LoadSummary()
    {
        // 骨架数据
        ConnectedCountText.Text = "--";
        TodayUsageText.Text = "--";
        TimeoutCountText.Text = "--";
        AnnouncementCountText.Text = "--";
    }

    // === 快捷操作事件处理 ===

    private void OnTimeoutConfigClick(object sender, RoutedEventArgs e)
    {
        // TODO: [TASK-D1-05] 跳转到超时处理配置页面
        MessageBox.Show("超时处理配置将在 D1-05 实现", "提示",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void OnAddDeviceClick(object sender, RoutedEventArgs e)
    {
        // TODO: [TASK-D1-04] 跳转到设备配对页面
        MessageBox.Show("设备添加需要 P2P 配对（D1-04 实现）", "提示",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void OnSendAnnouncementClick(object sender, RoutedEventArgs e)
    {
        // TODO: [TASK-D2-04] 跳转到公告编辑页面
        MessageBox.Show("公告功能将在 D2-04 实现", "提示",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void OnViewReportClick(object sender, RoutedEventArgs e)
    {
        // TODO: [TASK-D3-01] 跳转到报告页面
        MessageBox.Show("报告功能将在 D3-01 实现", "提示",
            MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
