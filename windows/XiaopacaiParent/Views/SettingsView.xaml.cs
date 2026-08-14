using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using XiaopacaiParent.Services;
using XiaopacaiParent.Services.P2P;

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

        // [SEC-P1] 显示 P2P 服务器证书指纹（供儿童端扫码/手动配对比对）
        try
        {
            var p2p = ((App)Application.Current).P2PService;
            CertFingerprintTextBox.Text =
                p2p?.GetCertificateFingerprint() ?? "（P2P 服务未启动）";
        }
        catch
        {
            CertFingerprintTextBox.Text = "（读取失败）";
        }
    }

    /// <summary>
    /// [SEC-P1] 生成一次性配对码（5 分钟有效，仅限儿童端首次配对使用一次）
    /// </summary>
    private void OnGeneratePairingCodeClick(object sender, RoutedEventArgs e)
    {
        var p2p = ((App)Application.Current).P2PService;
        if (p2p == null)
        {
            MessageBox.Show("P2P 监听服务未启动，无法生成配对码", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var code = p2p.GeneratePairingCode();
        PairingCodeTextBox.Text = code;
        PairingCodeHintText.Text = "配对码 5 分钟内有效，仅限儿童端首次配对使用一次";
    }

    /// <summary>
    /// [SEC-P1] 显示配对二维码：内含设备 ID、局域网 IP、监听端口、
    /// 证书真实指纹与一次性配对码，儿童端扫码即自动完成安全配对。
    /// </summary>
    private void OnShowPairingQrClick(object sender, RoutedEventArgs e)
    {
        var p2p = ((App)Application.Current).P2PService;
        if (p2p == null)
        {
            MessageBox.Show("P2P 监听服务未启动，无法生成配对二维码", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        try
        {
            // 每次展示生成新配对码（旧码作废，防二维码泄露后重放）
            var code = p2p.GeneratePairingCode();
            PairingCodeTextBox.Text = code;
            PairingCodeHintText.Text = "配对码 5 分钟内有效，仅限儿童端首次配对使用一次";

            var dataDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "XiaopacaiParent");
            var deviceId = DeviceIdentity.GetOrCreateId(dataDir);
            var fingerprint = p2p.GetCertificateFingerprint() ?? "";

            var png = QRCodeService.GeneratePairingQRCode(
                deviceId, "小趴菜家长端", 9527, fingerprint, code);

            // 弹出窗口展示二维码
            var window = new Window
            {
                Title = "配对二维码（5 分钟有效）",
                Width = 380,
                Height = 460,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Owner = Window.GetWindow(this)
            };

            var image = new Image { Stretch = System.Windows.Media.Stretch.Uniform };
            using (var ms = new MemoryStream(png))
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.StreamSource = ms;
                bmp.EndInit();
                image.Source = bmp;
            }

            var stack = new StackPanel { Margin = new Thickness(12) };
            stack.Children.Add(image);
            stack.Children.Add(new TextBlock
            {
                Text = "儿童端「扫码配对」扫描此码即可自动完成配对",
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 8, 0, 0)
            });
            window.Content = stack;
            window.ShowDialog();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"生成配对二维码失败: {ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
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
