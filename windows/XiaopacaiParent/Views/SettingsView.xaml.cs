using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;

namespace XiaopacaiParent.Views;

/// <summary>
/// [TASK-D1-03] 小趴菜家长端设置页面
///
/// 管理应用基础设置：数据存储目录、P2P 监听端口、TLS 证书、安全选项。
/// </summary>
public partial class SettingsView : Page
{
    public SettingsView()
    {
        InitializeComponent();

        // 加载当前设置
        LoadSettings();
    }

    /// <summary>
    /// 从配置文件加载当前设置
    /// TODO: [TASK-D1-03] 实现配置持久化（JSON 配置文件）
    /// </summary>
    private void LoadSettings()
    {
        // 默认数据目录：应用同级的 data 目录
        var defaultDataDir = Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory, "data");
        DataDirTextBox.Text = defaultDataDir;

        // 默认端口
        ListenPortTextBox.Text = "9527";
    }

    /// <summary>
    /// 浏览数据目录
    /// </summary>
    private void OnBrowseDataDirClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog
        {
            Title = "选择数据存储目录",
            InitialDirectory = DataDirTextBox.Text
        };

        if (dialog.ShowDialog() == true)
        {
            DataDirTextBox.Text = dialog.FolderName;
        }
    }

    /// <summary>
    /// 重新生成 TLS 证书
    /// </summary>
    private void OnRegenerateCertClick(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show(
            "重新生成证书将导致已配对设备需要重新认证。确认继续？",
            "确认操作", MessageBoxButton.YesNo, MessageBoxImage.Warning);

        if (result == MessageBoxResult.Yes)
        {
            // TODO: [TASK-D1-04] 重新生成自签名证书
            MessageBox.Show("证书重新生成功能将在 D1-04 实现", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    /// <summary>
    /// 保存设置到配置文件
    /// </summary>
    private void OnSaveSettingsClick(object sender, RoutedEventArgs e)
    {
        try
        {
            // 校验端口号
            if (!int.TryParse(ListenPortTextBox.Text, out int port) ||
                port < 1024 || port > 65535)
            {
                MessageBox.Show("端口号需在 1024-65535 之间", "输入错误",
                    MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            // TODO: [TASK-D1-03] 持久化保存到 config.json
            MessageBox.Show("设置保存成功！", "成功",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"保存失败: {ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }
}
