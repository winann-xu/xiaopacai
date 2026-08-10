using System;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace XiaopacaiParent.Services;

/// <summary>
/// [TASK-D1-03] 小趴菜家长端 P2P 监听服务
///
/// 后台 TCP 监听服务，接受儿童端设备连接。
/// 负责：
/// - 监听指定端口等待儿童端连接
/// - TLS 握手与双向证书认证
/// - 接收数据（使用记录、状态上报）并写入数据库
/// - 主动下发（策略更新、公告推送）
///
/// 当前为骨架实现：启动监听 + 接受连接框架。
/// 完整 P2P 协议在 D1-04 实现。
/// </summary>
public class P2PListenerService : IDisposable
{
    private readonly int _port;
    private readonly DatabaseService _databaseService;
    private readonly SyncService _syncService;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private bool _isRunning;

    /// <summary>自签名证书（用于 TLS 双向认证）</summary>
    private X509Certificate2? _certificate;

    public bool IsRunning => _isRunning;

    /// <summary>数据库服务（用于同步数据处理）</summary>
    public DatabaseService? DatabaseService => _databaseService;

    /// <summary>同步服务</summary>
    public SyncService SyncService => _syncService;

    public P2PListenerService(DatabaseService databaseService, int port = 9527)
    {
        _databaseService = databaseService;
        _syncService = new SyncService(databaseService);
        _port = port;
    }

    /// <summary>
    /// 同步启动 P2P 监听（在当前线程启动后台监听）</summary>
    public void Start()
    {
        if (_isRunning) return;
        _ = StartAsync(CancellationToken.None);
    }

    /// <summary>
    /// 启动 P2P 监听服务
    /// </summary>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (_isRunning) return;

        _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

        // 初始化 TLS 证书
        _certificate = LoadOrCreateCertificate();

        // 启动 TCP 监听
        _listener = new TcpListener(IPAddress.Any, _port);
        _listener.Start();
        _isRunning = true;

        // 后台接受连接
        _ = Task.Run(() => AcceptLoopAsync(_cts.Token), _cts.Token);

        await Task.CompletedTask;
    }

    /// <summary>
    /// 停止 P2P 监听服务
    /// </summary>
    public void Stop()
    {
        _isRunning = false;
        _cts?.Cancel();
        _listener?.Stop();
    }

    /// <summary>
    /// 后台循环：持续接受儿童端连接
    /// TODO: [TASK-D1-04] 实现 TLS 握手 + 双向认证 + 协议解析
    /// </summary>
    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener != null)
        {
            try
            {
                var client = await _listener.AcceptTcpClientAsync(ct);

                // 为每个连接启动独立处理任务
                _ = Task.Run(() => HandleConnectionAsync(client), ct);
            }
            catch (OperationCanceledException)
            {
                break; // 正常取消
            }
            catch (Exception ex)
            {
                // TODO: [TASK-D1-03] 记录日志
                System.Diagnostics.Debug.WriteLine($"P2P 监听异常: {ex.Message}");
            }
        }
    }

    /// <summary>
    /// 处理单个儿童端连接
    /// [TASK-D2-05] 实现 JSON 协议解析 + 同步数据交换
    /// </summary>
    private async Task HandleConnectionAsync(TcpClient client)
    {
        try
        {
            using (client)
            {
                var stream = client.GetStream();
                var buffer = new byte[4096];
                var bytesRead = await stream.ReadAsync(buffer, 0, buffer.Length);

                if (bytesRead == 0) return;

                var json = Encoding.UTF8.GetString(buffer, 0, bytesRead);
                System.Diagnostics.Debug.WriteLine(
                    $"P2P 收到: {client.Client.RemoteEndPoint}, 长度: {json.Length}");

                // 解析 JSON 消息
                var response = HandleSyncMessage(json);

                // 回复响应
                var responseBytes = Encoding.UTF8.GetBytes(response);
                await stream.WriteAsync(responseBytes, 0, responseBytes.Length);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"连接处理异常: {ex.Message}");
        }
    }

    /// <summary>
    /// [TASK-D2-05] 处理同步消息并返回响应
    /// </summary>
    private string HandleSyncMessage(string json)
    {
        try
        {
            var doc = System.Text.Json.JsonDocument.Parse(json);
            var root = doc.RootElement;

            var type = root.TryGetProperty("type", out var typeProp)
                ? typeProp.GetString() : "unknown";

            var payload = root.TryGetProperty("payload", out var payloadProp)
                ? payloadProp : default;

            switch (type)
            {
                case "usage_report":
                    // 接收儿童端使用时长报告
                    var deviceId = payload.TryGetProperty("deviceId", out var did)
                        ? did.GetString() ?? "unknown" : "unknown";
                    var recordsJson = payload.TryGetProperty("records", out var rec)
                        ? rec.GetString() ?? "[]" : "[]";
                    var count = _syncService.HandleUsageReport(deviceId, recordsJson);
                    return _syncService.BuildSyncAck(count);

                case "handshake":
                    // 握手消息：回复策略 + 公告推送
                    var handshakeDeviceId = payload.TryGetProperty("deviceId", out var hdid)
                        ? hdid.GetString() ?? "" : "";
                    return _syncService.BuildPolicyPushMessage(handshakeDeviceId);

                case "heartbeat":
                    // 心跳：保持连接活跃
                    return "{\"type\":\"heartbeat_ack\",\"payload\":{}}";

                default:
                    System.Diagnostics.Debug.WriteLine($"未知消息类型: {type}");
                    return "{\"type\":\"error\",\"payload\":{\"message\":\"unknown type\"}}";
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"消息处理异常: {ex.Message}");
            return $"{{\"type\":\"error\",\"payload\":{{\"message\":\"{ex.Message}\"}}}}";
        }
    }

    /// <summary>
    /// 加载或创建 TLS 自签名证书
    /// TODO: [TASK-D1-04] 实现自签名证书生成（RSA-2048 + SHA-256）
    /// </summary>
    private X509Certificate2 LoadOrCreateCertificate()
    {
        // 骨架返回：实际应在 D1-04 实现证书生成
        // 创建一个临时自签名证书用于测试
        return CreateSelfSignedCertificate();
    }

    /// <summary>
    /// 创建临时自签名证书（开发阶段占位）
    /// TODO: [TASK-D1-04] 替换为正式证书生成逻辑
    /// </summary>
    private static X509Certificate2 CreateSelfSignedCertificate()
    {
        // 使用 .NET 的 CertificateRequest 创建自签名证书
        using var rsa = System.Security.Cryptography.RSA.Create(2048);
        var request = new CertificateRequest(
            "CN=xiaopacai-parent-local",
            rsa,
            System.Security.Cryptography.HashAlgorithmName.SHA256,
            System.Security.Cryptography.RSASignaturePadding.Pkcs1);

        // SAN 扩展：允许 localhost 和局域网 IP
        var sanBuilder = new System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder();
        sanBuilder.AddIpAddress(IPAddress.Loopback);
        sanBuilder.AddIpAddress(IPAddress.Parse("127.0.0.1"));
        sanBuilder.AddDnsName("xiaopacai.local");
        request.CertificateExtensions.Add(sanBuilder.Build());

        // 基本信息
        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(false, false, 0, true));

        // 有效期：1 年
        var certificate = request.CreateSelfSigned(
            DateTimeOffset.Now.AddDays(-1),
            DateTimeOffset.Now.AddYears(1));

        // 导出为 PFX 格式（含私钥）
        return new X509Certificate2(
            certificate.Export(X509ContentType.Pfx),
            (string?)null,
            X509KeyStorageFlags.Exportable);
    }

    public void Dispose()
    {
        Stop();
        _certificate?.Dispose();
        _cts?.Dispose();
        GC.SuppressFinalize(this);
    }
}
