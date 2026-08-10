using System;
using System.IO;
using System.Windows;
using XiaopacaiParent.Services;

namespace XiaopacaiParent;

/// <summary>
/// [TASK-D1-03][TASK-D2-02] 小趴菜家长端应用入口
///
/// 负责全局初始化：加密数据库、P2P 监听服务、配置加载。
/// 通过静态属性暴露全局服务实例。
/// </summary>
public partial class App : Application
{
    /// <summary>加密数据库服务（全局单例）</summary>
    public DatabaseService? DatabaseService { get; private set; }

    /// <summary>P2P 监听服务（全局单例）</summary>
    public P2PListenerService? P2PService { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // 1. 初始化加密数据库
        var dataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Xiaopacai"
        );
        var dbPassword = GetOrCreateDbPassword(dataDir);
        DatabaseService = new DatabaseService(dataDir, dbPassword);

        // 2. 初始化 P2P 监听服务（后台线程）
        P2PService = new P2PListenerService(DatabaseService);
        P2PService.Start();

        // 3. 加载配置
        // TODO: [TASK-D1-03] 加载用户偏好设置
    }

    protected override void OnExit(ExitEventArgs e)
    {
        // 优雅关闭
        P2PService?.Stop();
        DatabaseService?.Dispose();
        base.OnExit(e);
    }

    /// <summary>
    /// 获取或创建数据库加密密码
    /// 正式版应使用 DPAPI 保护密钥
    /// </summary>
    private static string GetOrCreateDbPassword(string dataDir)
    {
        var keyFile = Path.Combine(dataDir, ".dbkey");
        if (File.Exists(keyFile))
        {
            return File.ReadAllText(keyFile).Trim();
        }

        // 生成新密钥
        var key = Guid.NewGuid().ToString("N");
        Directory.CreateDirectory(dataDir);
        File.WriteAllText(keyFile, key);

        // 设置文件为隐藏
        try { File.SetAttributes(keyFile, FileAttributes.Hidden); }
        catch { /* 忽略设置失败 */ }

        return key;
    }
}
