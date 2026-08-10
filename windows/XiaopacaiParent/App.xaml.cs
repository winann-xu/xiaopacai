using System.Windows;

namespace XiaopacaiParent;

/// <summary>
/// [TASK-D1-03] 小趴菜家长端应用入口
/// </summary>
public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // TODO: [TASK-D1-03] 初始化加密数据库
        // TODO: [TASK-D1-03] 启动后台 P2P 监听服务
        // TODO: [TASK-D1-03] 加载配置
    }

    protected override void OnExit(ExitEventArgs e)
    {
        // 优雅关闭：停止 P2P 服务、关闭数据库连接
        base.OnExit(e);
    }
}
