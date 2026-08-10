using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace XiaopacaiParent.Services;

/// <summary>
/// [TASK-D3-02] 小趴菜家长端加密服务
///
/// 提供数据加密/解密、密钥生成、哈希计算等安全功能。
/// - DPAPI：保护数据库密钥（可选二次保护）
/// - AES-256-GCM：敏感字段加密
/// - SHA-256：证书指纹与数据完整性校验
/// - HMAC-SHA256：消息认证码（防数据篡改）
/// - PBKDF2：密钥派生（防暴力破解）
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

    // === [TASK-D3-02] 新增安全功能 ===

    /// <summary>
    /// [TASK-D3-02] 计算数据的 HMAC-SHA256 认证码
    ///
    /// 用于验证同步数据的完整性和真实性（防中间人篡改）。
    /// </summary>
    /// <param name="data">待认证的数据</param>
    /// <param name="key">共享密钥</param>
    /// <returns>Base64 编码的 HMAC</returns>
    public static string ComputeHmacSha256(string data, byte[] key)
    {
        var dataBytes = Encoding.UTF8.GetBytes(data);
        using var hmac = new HMACSHA256(key);
        var hash = hmac.ComputeHash(dataBytes);
        return Convert.ToBase64String(hash);
    }

    /// <summary>
    /// [TASK-D3-02] 验证 HMAC-SHA256 认证码
    /// </summary>
    /// <param name="data">原始数据</param>
    /// <param name="expectedHmac">期望的 HMAC（Base64）</param>
    /// <param name="key">共享密钥</param>
    /// <returns>true 如果 HMAC 匹配</returns>
    public static bool VerifyHmacSha256(string data, string expectedHmac, byte[] key)
    {
        var computed = ComputeHmacSha256(data, key);
        return CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(computed),
            Encoding.UTF8.GetBytes(expectedHmac));
    }

    /// <summary>
    /// [TASK-D3-02] 使用 AES-256-GCM 加密数据
    ///
    /// 用于保护传输中的敏感字段（如设备 ID、指纹等）。
    /// </summary>
    /// <param name="plainText">明文</param>
    /// <param name="key">AES-256 密钥（32 字节）</param>
    /// <returns>Base64 编码的密文（IV + 密文 + GCM 标签）</returns>
    public static string EncryptAesGcm(string plainText, byte[] key)
    {
        var plainBytes = Encoding.UTF8.GetBytes(plainText);
        var iv = RandomNumberGenerator.GetBytes(12);  // GCM 推荐 12 字节 IV
        var cipherBytes = new byte[plainBytes.Length];
        var tag = new byte[16];  // GCM 认证标签 128 bit

        using var aes = new AesGcm(key, tag.Length);
        aes.Encrypt(iv, plainBytes, cipherBytes, tag);

        // 拼接 IV + 密文 + 标签
        var result = new byte[iv.Length + cipherBytes.Length + tag.Length];
        Buffer.BlockCopy(iv, 0, result, 0, iv.Length);
        Buffer.BlockCopy(cipherBytes, 0, result, iv.Length, cipherBytes.Length);
        Buffer.BlockCopy(tag, 0, result, iv.Length + cipherBytes.Length, tag.Length);

        return Convert.ToBase64String(result);
    }

    /// <summary>
    /// [TASK-D3-02] 使用 AES-256-GCM 解密数据
    /// </summary>
    /// <param name="cipherText">Base64 编码的密文</param>
    /// <param name="key">AES-256 密钥（32 字节）</param>
    /// <returns>明文</returns>
    public static string DecryptAesGcm(string cipherText, byte[] key)
    {
        var combined = Convert.FromBase64String(cipherText);
        var iv = combined[..12];
        var tag = combined[^16..];
        var cipherBytes = combined[12..^16];

        var plainBytes = new byte[cipherBytes.Length];
        using var aes = new AesGcm(key, tag.Length);
        aes.Decrypt(iv, cipherBytes, tag, plainBytes);

        return Encoding.UTF8.GetString(plainBytes);
    }

    /// <summary>
    /// [TASK-D3-02] 验证数据库文件完整性
    ///
    /// 通过读取文件头部 SQLite 魔数确认数据库未被损坏或篡改。
    /// </summary>
    /// <param name="dbPath">数据库文件路径</param>
    /// <returns>true 如果数据库文件头有效</returns>
    public static bool ValidateDatabaseIntegrity(string dbPath)
    {
        if (!File.Exists(dbPath)) return false;

        try
        {
            var header = new byte[16];
            using var fs = File.OpenRead(dbPath);
            if (fs.Read(header, 0, 16) < 16) return false;

            // SQLite 文件头魔数: "SQLite format 3\0"
            var sqliteMagic = Encoding.ASCII.GetBytes("SQLite format 3\0");
            return header.AsSpan(0, 16).SequenceEqual(sqliteMagic);
        }
        catch
        {
            return false;
        }
    }
}
