using System.Windows;
using System.Windows.Controls;
using XiaopacaiParent.Views;

namespace XiaopacaiParent;

/// <summary>
/// [TASK-D1-03] 小趴菜家长端主窗口
///
/// 左侧导航栏 + 右侧内容区布局。
/// 子页面通过 Frame 导航加载，保持页面状态。
/// </summary>
public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();

        // 启动后默认显示仪表盘
        ContentFrame.Navigate(new DashboardView());
    }

    /// <summary>
    /// 导航到仪表盘页面
    /// </summary>
    private void OnDashboardClick(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(new DashboardView());
    }

    /// <summary>
    /// 导航到设备管理页面
    /// </summary>
    private void OnDevicesClick(object sender, RoutedEventArgs e)
    {
        // TODO: [TASK-D1-03] 实现设备管理页面
        ContentFrame.Navigate(new DashboardView()); // 临时占位
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
        // TODO: [TASK-D2-04] 实现公告管理页面
        ContentFrame.Navigate(new DashboardView()); // 临时占位
    }

    /// <summary>
    /// 导航到使用报告页面
    /// </summary>
    private void OnReportsClick(object sender, RoutedEventArgs e)
    {
        // TODO: [TASK-D3-01] 实现使用报告页面
        ContentFrame.Navigate(new DashboardView()); // 临时占位
    }

    /// <summary>
    /// 导航到设置页面
    /// </summary>
    private void OnSettingsClick(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(new SettingsView());
    }
}
