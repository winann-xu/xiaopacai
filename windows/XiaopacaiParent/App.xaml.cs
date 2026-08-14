using System;
using System.Windows;
using XiaopacaiParent.Services;

namespace XiaopacaiParent;

/// <summary>
/// [TASK-D1-03][TASK-D2-02] 小趴菜家长端应用入口
///
/// [SEC-P1] 单数据库单监听改造（原双初始化缺陷，红线 R4.x/R11.x）：
/// - 原 OnStartup 在此处初始化第二套 DatabaseService（明文 .dbkey）与第二个
///   P2PListenerService（9527 端口绑定失败被静默吞掉），导致策略/公告视图读写
///   一个库、仪表盘/报告读写另一个库，儿童端数据落在明文密钥保护的库里。
/// - 现改为：MainWindow 统一创建（DPAPI 保护的 XiaopacaiParent 目录），
///   创建后注入本类的全局属性，供 PolicyView/AnnouncementView 等取用。
/// </summary>
public partial class App : Application
{
    /// <summary>加密数据库服务（全局单例，由 MainWindow 创建后注入）</summary>
    public DatabaseService? DatabaseService { get; set; }

    /// <summary>P2P 监听服务（全局单例，由 MainWindow 创建后注入）</summary>
    public P2PListenerService? P2PService { get; set; }

    protected override void OnExit(ExitEventArgs e)
    {
        // 优雅关闭（Dispose 幂等，与 MainWindow.OnClosed 重复调用无害）
        P2PService?.Dispose();
        DatabaseService?.Dispose();
        base.OnExit(e);
    }
}
