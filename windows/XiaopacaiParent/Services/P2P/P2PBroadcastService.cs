using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace XiaopacaiParent.Services.P2P;

/// <summary>
/// [TASK-D1-04] P2P UDP 广播服务
///
/// 当 mDNS 不可用时，通过 UDP 广播宣告家长端的存在。
/// 每 30 秒向局域网广播一次，包含设备 ID、端口、证书指纹。
/// </summary>
public class P2PBroadcastService : IDisposable
{
    private readonly string _deviceId;
    private readonly int _tcpPort;
    private readonly string _certFingerprint;
    private readonly int _broadcastPort;

    private UdpClient? _udpClient;
    private CancellationTokenSource? _cts;
    private bool _isRunning;

    public bool IsRunning => _isRunning;

    public P2PBroadcastService(
        string deviceId,
        int tcpPort = 9527,
        string certFingerprint = "",
        int broadcastPort = 9528)
    {
        _deviceId = deviceId;
        _tcpPort = tcpPort;
        _certFingerprint = certFingerprint;
        _broadcastPort = broadcastPort;
    }

    /// <summary>
    /// 启动 UDP 广播
    /// </summary>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (_isRunning) return;

        _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _udpClient = new UdpClient();
        _udpClient.Client.SetSocketOption(
            SocketOptionLevel.Socket,
            SocketOptionName.Broadcast,
            true);
        _isRunning = true;

        // 后台循环广播
        _ = Task.Run(() => BroadcastLoopAsync(_cts.Token), _cts.Token);

        await Task.CompletedTask;
    }

    /// <summary>
    /// 停止 UDP 广播
    /// </summary>
    public void Stop()
    {
        _isRunning = false;
        _cts?.Cancel();
        _udpClient?.Close();
    }

    /// <summary>
    /// 广播循环：每 30 秒发送一次
    /// </summary>
    private async Task BroadcastLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var packet = BuildBroadcastPacket();
                // [BUG-0810-04] 使用 ReadOnlyMemory<byte> 重载以支持 CancellationToken
                await _udpClient!.SendAsync(
                    new ReadOnlyMemory<byte>(packet),
                    new IPEndPoint(IPAddress.Broadcast, _broadcastPort),
                    ct);

                System.Diagnostics.Debug.WriteLine(
                    $"UDP 广播已发送: {_deviceId}@{_tcpPort}");

            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"UDP 广播异常: {ex.Message}");
            }

            // 等待 30 秒
            try { await Task.Delay(30_000, ct); }
            catch (OperationCanceledException) { break; }
        }
    }

    /// <summary>
    /// 构建 UDP 广播包
    /// 格式: "XPACAI" + JSON payload
    /// </summary>
    private byte[] BuildBroadcastPacket()
    {
        var payload = new
        {
            type = "announce",
            version = "1.0",
            deviceId = _deviceId,
            port = _tcpPort,
            fingerprint = _certFingerprint.Length >= 16
                ? _certFingerprint[..16]
                : _certFingerprint
        };

        var json = JsonSerializer.Serialize(payload);
        var data = Encoding.UTF8.GetBytes("XPACAI" + json);
        return data;
    }

    public void Dispose()
    {
        Stop();
        _udpClient?.Dispose();
        _cts?.Dispose();
        GC.SuppressFinalize(this);
    }
}
