using System;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
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
/// - TLS 握手与证书认证（SslStream）
/// - 4 字节长度前缀 + JSON 消息帧协议
/// - 接收数据（使用记录、状态上报）并写入数据库
/// - 主动下发（策略更新、公告推送）
///
/// P2P-FIX: 实现 TLS 服务端 + 长度前缀帧协议，匹配儿童端 TLS 1.3 客户端。
/// </summary>
public class P2PListenerService : IDisposable
{
    private readonly int _port;
    private readonly DatabaseService _databaseService;
    private readonly SyncService _syncService;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private bool _isRunning;

    /// <summary>自签名证书（用于 TLS 认证）</summary>
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

        System.Diagnostics.Debug.WriteLine($"[P2P] TLS 监听已启动，端口: {_port}");

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
    /// P2P-FIX: 每个连接使用 SslStream 进行 TLS 加密
    /// </summary>
    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener != null)
        {
            try
            {
                var client = await _listener.AcceptTcpClientAsync(ct);

                // 为每个连接启动独立处理任务
                _ = Task.Run(() => HandleConnectionWithTlsAsync(client), ct);
            }
            catch (OperationCanceledException)
            {
                break; // 正常取消
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"P2P 监听异常: {ex.Message}");
            }
        }
    }

    /// <summary>
    /// P2P-FIX: 使用 SslStream 处理 TLS 加密连接
    /// 协议：4 字节大端长度前缀 + JSON 消息体
    /// </summary>
    private async Task HandleConnectionWithTlsAsync(TcpClient client)
    {
        try
        {
            using (client)
            using (var sslStream = new System.Net.Security.SslStream(
                client.GetStream(), leaveInnerStreamOpen: false))
            {
                // TLS 服务端认证（使用自签名证书）
                await sslStream.AuthenticateAsServerAsync(
                    _certificate!,
                    clientCertificateRequired: false,  // 儿童端不提供客户端证书
                    enabledSslProtocols: SslProtocols.Tls13 | SslProtocols.Tls12,
                    checkCertificateRevocation: false);

                System.Diagnostics.Debug.WriteLine(
                    $"[P2P] TLS 握手完成: {client.Client.RemoteEndPoint}, " +
                    $"协议: {sslStream.SslProtocol}");

                // 循环接收消息帧
                while (client.Connected)
                {
                    var message = await ReadFrameAsync(sslStream);
                    if (message == null) break;  // 连接关闭

                    System.Diagnostics.Debug.WriteLine(
                        $"[P2P] 收到消息: {message[..Math.Min(message.Length, 200)]}");

                    // 处理消息并生成响应
                    var response = HandleSyncMessage(message);

                    // 发送响应帧
                    if (!string.IsNullOrEmpty(response))
                    {
                        await WriteFrameAsync(sslStream, response);
                    }
                }
            }
        }
        catch (AuthenticationException ex)
        {
            System.Diagnostics.Debug.WriteLine($"[P2P] TLS 握手失败: {ex.Message}");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[P2P] 连接处理异常: {ex.Message}");
        }
    }

    /// <summary>
    /// P2P-FIX: 读取 4 字节长度前缀 + JSON 消息帧
    /// 匹配儿童端 P2PConnectionService.sendMessage() 的帧格式
    /// </summary>
    private static async Task<string?> ReadFrameAsync(System.Net.Security.SslStream stream)
    {
        try
        {
            // 读取 4 字节大端长度
            var lengthBytes = new byte[4];
            var bytesRead = 0;
            while (bytesRead < 4)
            {
                var n = await stream.ReadAsync(lengthBytes, bytesRead, 4 - bytesRead);
                if (n == 0) return null;  // 连接关闭
                bytesRead += n;
            }

            // 大端字节序转 int
            if (BitConverter.IsLittleEndian)
                Array.Reverse(lengthBytes);
            var length = BitConverter.ToInt32(lengthBytes, 0);

            if (length <= 0 || length > 1_048_576)  // 最大 1MB
            {
                System.Diagnostics.Debug.WriteLine($"[P2P] 无效消息长度: {length}");
                return null;
            }

            // 读取消息体
            var bodyBytes = new byte[length];
            bytesRead = 0;
            while (bytesRead < length)
            {
                var n = await stream.ReadAsync(bodyBytes, bytesRead, length - bytesRead);
                if (n == 0) return null;
                bytesRead += n;
            }

            return Encoding.UTF8.GetString(bodyBytes);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[P2P] 读取帧异常: {ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// P2P-FIX: 写入 4 字节长度前缀 + JSON 消息帧
    /// 匹配儿童端 P2PConnectionService 的帧格式
    /// </summary>
    private static async Task WriteFrameAsync(System.Net.Security.SslStream stream, string json)
    {
        var jsonBytes = Encoding.UTF8.GetBytes(json);
        var lengthBytes = BitConverter.GetBytes(jsonBytes.Length);

        // 大端字节序
        if (BitConverter.IsLittleEndian)
            Array.Reverse(lengthBytes);

        // 发送长度 + 消息体
        await stream.WriteAsync(lengthBytes, 0, 4);
        await stream.WriteAsync(jsonBytes, 0, jsonBytes.Length);
        await stream.FlushAsync();
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
    /// P2P-FIX: SAN 包含局域网 IP 地址，支持儿童端通过 IP 直连验证
    /// </summary>
    private X509Certificate2 LoadOrCreateCertificate()
    {
        // LEGACY-e: 证书持久化，保证重启后指纹稳定（儿童端首次配对后保持不变）
        var dataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Xiaopacai");
        Directory.CreateDirectory(dataDir);
        var certPath = Path.Combine(dataDir, "p2p_cert.pfx");
        var keyPath = Path.Combine(dataDir, "p2p_cert.key");

        // 已有证书：加载复用
        if (File.Exists(certPath) && File.Exists(keyPath))
        {
            try
            {
                var pwd = File.ReadAllText(keyPath).Trim();
                return new X509Certificate2(certPath, pwd, X509KeyStorageFlags.Exportable);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"加载 P2P 证书失败，重新生成: {ex.Message}");
            }
        }

        // 首次：生成并持久化（密码文件与 .dbkey 同目录同风格）
        var cert = CreateSelfSignedCertificate();
        var password = Guid.NewGuid().ToString("N");
        try
        {
            var pfxBytes = cert.Export(X509ContentType.Pfx, password);
            File.WriteAllBytes(certPath, pfxBytes);
            File.WriteAllText(keyPath, password);
            try { File.SetAttributes(keyPath, FileAttributes.Hidden); } catch { }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"持久化 P2P 证书失败: {ex.Message}");
        }
        return cert;
    }

    /// <summary>
    /// P2P-FIX: 创建自签名证书（含局域网 IP SAN）
    /// </summary>
    private static X509Certificate2 CreateSelfSignedCertificate()
    {
        using var rsa = System.Security.Cryptography.RSA.Create(2048);
        var request = new CertificateRequest(
            "CN=xiaopacai-parent-local",
            rsa,
            System.Security.Cryptography.HashAlgorithmName.SHA256,
            System.Security.Cryptography.RSASignaturePadding.Pkcs1);

        // SAN 扩展：包含 localhost、局域网常用 IP 以及本机实际 IP
        var sanBuilder = new SubjectAlternativeNameBuilder();
        sanBuilder.AddIpAddress(IPAddress.Loopback);
        sanBuilder.AddIpAddress(IPAddress.Parse("127.0.0.1"));
        sanBuilder.AddDnsName("xiaopacai.local");
        sanBuilder.AddDnsName("localhost");

        // 添加本机所有 IPv4 地址到 SAN（支持局域网 IP 直连）
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus == OperationalStatus.Up)
                {
                    foreach (var addr in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (addr.Address.AddressFamily == AddressFamily.InterNetwork)
                        {
                            sanBuilder.AddIpAddress(addr.Address);
                        }
                    }
                }
            }
        }
        catch
        {
            // SAN 添加失败不影响主流程
        }

        request.CertificateExtensions.Add(sanBuilder.Build());

        // 基本信息
        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(false, false, 0, true));

        // 增强密钥用法：服务器认证
        request.CertificateExtensions.Add(
            new X509EnhancedKeyUsageExtension(
                new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") },  // serverAuth
                critical: true));

        // 有效期：1 年
        var certificate = request.CreateSelfSigned(
            DateTimeOffset.Now.AddDays(-1),
            DateTimeOffset.Now.AddYears(1));

        // 导出为 PFX 格式（含私钥），供 SslStream 使用
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
