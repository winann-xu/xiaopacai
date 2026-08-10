using System;
using System.Collections.Generic;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;
using QRCoder;

namespace XiaopacaiParent.Services.P2P;

/// <summary>
/// [TASK-D1-04] QR 码生成服务
///
/// 生成配对二维码，内含家长端信息：
/// - 设备 ID
/// - 监听端口
/// - 证书指纹
/// - 配对码
/// - 局域网 IP 列表
/// </summary>
public static class QRCodeService
{
    /// <summary>
    /// 生成配对二维码（PNG 字节数组）
    /// 儿童端扫描此二维码即可快速配对
    /// </summary>
    /// <param name="deviceId">家长端设备 ID</param>
    /// <param name="deviceName">家长端设备名称</param>
    /// <param name="port">TCP 监听端口</param>
    /// <param name="fingerprint">证书 SHA-256 指纹</param>
    /// <param name="pairingCode">6 位配对码</param>
    /// <returns>PNG 格式的二维码图片字节数组</returns>
    public static byte[] GeneratePairingQRCode(
        string deviceId,
        string deviceName,
        int port,
        string fingerprint,
        string pairingCode)
    {
        // 1. 获取局域网 IP 列表
        var localIps = GetLocalIPAddresses();

        // 2. 构建二维码内容 JSON
        var qrContent = new
        {
            type = "pairing",
            deviceId,
            deviceName,
            port,
            fingerprint,
            pairingCode,
            ips = localIps,
            version = "1.0",
            timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
        };

        var json = JsonSerializer.Serialize(qrContent,
            new JsonSerializerOptions { WriteIndented = false });

        // 3. 生成二维码
        using var qrGenerator = new QRCodeGenerator();
        using var qrCodeData = qrGenerator.CreateQrCode(json, QRCodeGenerator.ECCLevel.M);
        using var qrCode = new PngByteQRCode(qrCodeData);

        return qrCode.GetGraphic(10);  // 10px 每模块
    }

    /// <summary>
    /// 获取本机所有局域网 IPv4 地址
    /// </summary>
    private static List<string> GetLocalIPAddresses()
    {
        var ips = new List<string>();

        foreach (var networkInterface in NetworkInterface.GetAllNetworkInterfaces())
        {
            // 跳过回环和隧道接口
            if (networkInterface.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                continue;
            if (networkInterface.OperationalStatus != OperationalStatus.Up)
                continue;

            foreach (var address in networkInterface.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily == AddressFamily.InterNetwork &&
                    !IPAddress.IsLoopback(address.Address))
                {
                    ips.Add(address.Address.ToString());
                }
            }
        }

        return ips;
    }
}
