using System;
using System.IO;

namespace XiaopacaiParent.Services;

/// <summary>
/// [SEC-P1] 家长端设备标识持久化
///
/// 广播/二维码配对携带的设备 ID 需跨重启稳定（儿童端以 parent_id 保存指纹，
/// 重连时按设备 ID 读取历史指纹做固定比对），首次运行生成后落盘复用。
/// </summary>
public static class DeviceIdentity
{
    /// <summary>
    /// 获取或创建家长端设备 ID（稳定，跨重启复用）
    /// </summary>
    /// <param name="dataDir">应用数据目录（XiaopacaiParent）</param>
    /// <returns>设备 ID（如 XP-ABCD1234EF56）</returns>
    public static string GetOrCreateId(string dataDir)
    {
        Directory.CreateDirectory(dataDir);
        var idFile = Path.Combine(dataDir, "device_id.txt");

        if (File.Exists(idFile))
        {
            var existing = File.ReadAllText(idFile).Trim();
            if (!string.IsNullOrEmpty(existing)) return existing;
        }

        var id = CryptoService.GenerateDeviceId();
        File.WriteAllText(idFile, id);
        return id;
    }
}
