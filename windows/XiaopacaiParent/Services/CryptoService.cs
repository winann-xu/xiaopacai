using System;
using System.Security.Cryptography;
using System.Text;

namespace XiaopacaiParent.Services;

/// <summary>
/// [TASK-D1-03] 小趴菜家长端加密服务
///
/// 提供数据加密/解密、密钥生成、哈希计算等安全功能。
/// - DPAPI：保护数据库密钥（可选二次保护）
/// - AES-256-GCM：敏感字段加密
/// - SHA-256：证书指纹与数据完整性校验
/// </summary>
public static class CryptoService
{
    /// <summary>
    /// 使用 DPAPI 加密数据库密码（Windows 用户级别保护）
    /// 只有当前 Windows 用户账户可以解密
    /// </summary>
    /// <param name="plainText">明文密码</param>
    /// <returns>Base64 编码的密文</returns>
    public static string ProtectWithDpapi(string plainText)
    {
        var plainBytes = Encoding.UTF8.GetBytes(plainText);
        var protectedBytes = ProtectedData.Protect(
            plainBytes,
            null,  // 可选熵（额外密钥材料）
            DataProtectionScope.CurrentUser);  // 当前用户级别

        return Convert.ToBase64String(protectedBytes);
    }

    /// <summary>
    /// 使用 DPAPI 解密数据库密码
    /// </summary>
    /// <param name="cipherText">Base64 编码的密文</param>
    /// <returns>明文密码</returns>
    public static string UnprotectWithDpapi(string cipherText)
    {
        var protectedBytes = Convert.FromBase64String(cipherText);
        var plainBytes = ProtectedData.Unprotect(
            protectedBytes,
            null,
            DataProtectionScope.CurrentUser);

        return Encoding.UTF8.GetString(plainBytes);
    }

    /// <summary>
    /// 生成随机数据库加密密钥
    /// </summary>
    /// <param name="length">密钥长度（字节），默认 32（AES-256）</param>
    /// <returns>Base64 编码的随机密钥</returns>
    public static string GenerateDatabaseKey(int length = 32)
    {
        var keyBytes = RandomNumberGenerator.GetBytes(length);
        // 使用 URL 安全的 Base64 编码（不含 +/）
        return Convert.ToBase64String(keyBytes)
            .Replace('+', '-')
            .Replace('/', '_')
            .TrimEnd('=');
    }

    /// <summary>
    /// 计算证书 SHA-256 指纹
    /// 用于配对时比对证书指纹
    /// </summary>
    /// <param name="certDer">证书 DER 编码字节</param>
    /// <returns>十六进制指纹字符串</returns>
    public static string ComputeCertFingerprint(byte[] certDer)
    {
        var hash = SHA256.HashData(certDer);
        return BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
    }

    /// <summary>
    /// 生成一次性配对码
    /// 6 位数字，用于家长端与儿童端的初始配对
    /// </summary>
    /// <returns>6 位数字字符串</returns>
    public static string GeneratePairingCode()
    {
        // 使用密码学安全的随机数生成器
        var randomNumber = RandomNumberGenerator.GetInt32(0, 1_000_000);
        return randomNumber.ToString("D6");  // 补零到 6 位
    }

    /// <summary>
    /// 生成设备唯一标识
    /// 基于时间戳 + 随机数
    /// </summary>
    /// <returns>设备 UUID 格式字符串</returns>
    public static string GenerateDeviceId()
    {
        var guid = Guid.NewGuid();
        return $"XP-{guid.ToString("N")[..12].ToUpperInvariant()}";  // 如 XP-ABCD1234EF56
    }
}
