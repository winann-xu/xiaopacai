using System;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
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
///
/// [SEC-P1] 零认证监听加固（红线 R3.x/R11.x）：
/// - mTLS：clientCertificateRequired=true，传输层接受任意客户端证书，
///   应用层以客户端证书 SHA-256 指纹绑定设备身份（儿童端自 5fcbd63 起始终携带客户端证书）
/// - 已注册设备：握手 deviceId 的已存指纹与 TLS 对端指纹恒定时间比对，不匹配即拒绝
/// - 新设备：必须提供家长端生成的一次性配对码（6 位、5 分钟有效、5 次试错、5 分钟锁定）
/// - 握手通过前忽略一切业务消息（usage_report/heartbeat 均不处理）
/// - 并发连接上限 20（防资源耗尽）
/// </summary>
public class P2PListenerService : IDisposable
{
    /// <summary>最大并发连接数（防资源耗尽）</summary>
    private const int MaxConnections = 20;

    /// <summary>配对码有效期（分钟）</summary>
    private const int PairingCodeValidityMinutes = 5;

    /// <summary>配对码最大试错次数</summary>
    private const int MaxPairingCodeAttempts = 5;

    /// <summary>配对码锁定时长（分钟）</summary>
    private const int PairingCodeLockoutMinutes = 5;

    private readonly int _port;
    private readonly DatabaseService _databaseService;
    private readonly SyncService _syncService;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private bool _isRunning;

    /// <summary>当前并发连接数（Interlocked 原子操作）</summary>
    private int _activeConnections;

    /// <summary>自签名证书（用于 TLS 认证）</summary>
    private X509Certificate2? _certificate;

    // === [SEC-P1] 配对码状态机（仅内存保存，家长端 UI 生成） ===
    private string? _pendingPairingCode;
    private DateTime _pairingCodeExpiry = DateTime.MinValue;
    private int _pairingCodeAttempts;
    private DateTime _pairingCodeLockoutUntil = DateTime.MinValue;

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
    /// [SEC-P1] 生成一次性配对码（家长端 UI 调用）
    ///
    /// 6 位数字，5 分钟有效，最多 5 次试错后锁定 5 分钟。
    /// 生成新码会重置试错计数与锁定状态。
    /// </summary>
    /// <returns>6 位数字配对码</returns>
    public string GeneratePairingCode()
    {
        var code = CryptoService.GeneratePairingCode();
        _pendingPairingCode = code;
        _pairingCodeExpiry = DateTime.UtcNow.AddMinutes(PairingCodeValidityMinutes);
        _pairingCodeAttempts = 0;
        _pairingCodeLockoutUntil = DateTime.MinValue;
        System.Diagnostics.Debug.WriteLine($"[P2P] 已生成配对码（{PairingCodeValidityMinutes} 分钟内有效）");
        return code;
    }

    /// <summary>
    /// [SEC-P1] 获取本机 P2P 服务器证书指纹（供 UI 展示与儿童端扫码配对）
    /// </summary>
    public string? GetCertificateFingerprint()
    {
        if (_certificate == null)
        {
            _certificate = LoadOrCreateCertificate();
        }
        return CryptoService.ComputeCertFingerprint(_certificate.GetRawCertData());
    }

    /// <summary>
    /// [SEC-P1] 校验配对码（6 位数字、有效期、试错上限、锁定）
    /// 校验成功即一次性消费（清除待配对码）。
    /// </summary>
    private bool VerifyPairingCode(string code)
    {
        if (_pairingCodeLockoutUntil > DateTime.UtcNow)
        {
            System.Diagnostics.Debug.WriteLine("[P2P] 配对码校验处于锁定期，拒绝");
            return false;
        }

        if (string.IsNullOrEmpty(_pendingPairingCode) || _pairingCodeExpiry <= DateTime.UtcNow)
        {
            System.Diagnostics.Debug.WriteLine("[P2P] 无有效待配对码（未生成或已过期），拒绝");
            return false;
        }

        if (++_pairingCodeAttempts > MaxPairingCodeAttempts)
        {
            _pairingCodeLockoutUntil = DateTime.UtcNow.AddMinutes(PairingCodeLockoutMinutes);
            _pairingCodeAttempts = 0;
            System.Diagnostics.Debug.WriteLine(
                $"[P2P] 配对码连续试错超 {MaxPairingCodeAttempts} 次，锁定 {PairingCodeLockoutMinutes} 分钟");
            return false;
        }

        if (!CryptoService.FixedTimeStringEquals(_pendingPairingCode, code))
        {
            return false;
        }

        // 一次性：校验成功即消费
        _pendingPairingCode = null;
        _pairingCodeExpiry = DateTime.MinValue;
        _pairingCodeAttempts = 0;
        return true;
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

                // [SEC-P1] 连接上限：超过则立即关闭新连接
                if (Interlocked.Increment(ref _activeConnections) > MaxConnections)
                {
                    Interlocked.Decrement(ref _activeConnections);
                    System.Diagnostics.Debug.WriteLine(
                        $"[P2P] 并发连接超过上限 {MaxConnections}，拒绝 {client.Client.RemoteEndPoint}");
                    client.Close();
                    continue;
                }

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
    ///
    /// [SEC-P1] mTLS 双向认证：要求客户端证书（传输层接受，应用层指纹校验）
    /// </summary>
    private async Task HandleConnectionWithTlsAsync(TcpClient client)
    {
        try
        {
            using (client)
            using (var sslStream = new System.Net.Security.SslStream(
                client.GetStream(),
                leaveInnerStreamOpen: false,
                // mTLS：传输层接受任意客户端证书，认证在应用层按指纹完成
                // （无法用系统证书链验证自签名儿童端证书，故在校验回调中放行）
                userCertificateValidationCallback: (_, _, _, _) => true))
            {
                // TLS 服务端认证 + 要求客户端证书
                await sslStream.AuthenticateAsServerAsync(
                    _certificate!,
                    clientCertificateRequired: true,
                    enabledSslProtocols: SslProtocols.Tls13 | SslProtocols.Tls12,
                    checkCertificateRevocation: false);

                // [SEC-P1] 提取客户端证书指纹（儿童端自 5fcbd63 起始终携带客户端证书）
                var peerFingerprint = sslStream.RemoteCertificate is X509Certificate2 remoteCert
                    ? CryptoService.ComputeCertFingerprint(remoteCert.GetRawCertData())
                    : "";

                if (string.IsNullOrEmpty(peerFingerprint))
                {
                    System.Diagnostics.Debug.WriteLine(
                        $"[P2P] 拒绝连接：客户端未提供证书 {client.Client.RemoteEndPoint}");
                    return;
                }

                System.Diagnostics.Debug.WriteLine(
                    $"[P2P] TLS 握手完成: {client.Client.RemoteEndPoint}, " +
                    $"协议: {sslStream.SslProtocol}, 客户端指纹: {peerFingerprint[..16]}...");

                // [SEC-P1] 握手通过前拒绝一切业务消息
                var handshakeAccepted = false;

                // 循环接收消息帧
                while (client.Connected)
                {
                    var message = await ReadFrameAsync(sslStream);
                    if (message == null) break;  // 连接关闭

                    System.Diagnostics.Debug.WriteLine(
                        $"[P2P] 收到消息: {message[..Math.Min(message.Length, 200)]}");

                    // 处理消息并生成响应
                    var (response, closeConnection) =
                        HandleSyncMessage(message, peerFingerprint, ref handshakeAccepted);

                    // 发送响应帧
                    if (!string.IsNullOrEmpty(response))
                    {
                        await WriteFrameAsync(sslStream, response);
                    }

                    // 拒绝后立即关闭连接（不再处理后续帧）
                    if (closeConnection) break;
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
        finally
        {
            Interlocked.Decrement(ref _activeConnections);
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
    /// [TASK-D2-05][SEC-P1] 处理同步消息并返回响应
    ///
    /// 返回 (响应 JSON, 是否关闭连接)。
    /// 握手认证（指纹绑定 + 配对码）通过前拒绝一切业务消息。
    /// </summary>
    private (string response, bool closeConnection) HandleSyncMessage(
        string json, string peerFingerprint, ref bool handshakeAccepted)
    {
        try
        {
            var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            var type = root.TryGetProperty("type", out var typeProp)
                ? typeProp.GetString() : "unknown";

            var payload = root.TryGetProperty("payload", out var payloadProp)
                ? payloadProp : default;

            switch (type)
            {
                case "handshake":
                {
                    var deviceId = payload.TryGetProperty("deviceId", out var did)
                        ? did.GetString() ?? "" : "";
                    var deviceName = payload.TryGetProperty("deviceName", out var dname)
                        ? dname.GetString() ?? "未知设备" : "未知设备";
                    var pairingCode = payload.TryGetProperty("pairingCode", out var pc)
                        ? pc.GetString() : null;

                    if (string.IsNullOrEmpty(deviceId))
                    {
                        return (BuildError("missing_device_id"), true);
                    }

                    // [SEC-P1] 已注册设备：指纹恒定时间比对（防中间人/证书重建）
                    var registered = _databaseService.GetDeviceFingerprint(deviceId);
                    if (!string.IsNullOrEmpty(registered))
                    {
                        if (!CryptoService.FixedTimeStringEquals(registered, peerFingerprint))
                        {
                            System.Diagnostics.Debug.WriteLine(
                                $"[P2P] 拒绝握手：设备 {deviceId} 证书指纹不匹配（可能为中间人或设备证书已重建，需重新配对）");
                            return (BuildError("fingerprint_mismatch"), true);
                        }
                    }
                    else
                    {
                        // [SEC-P1] 新设备：必须提供有效一次性配对码
                        if (string.IsNullOrEmpty(pairingCode) || !VerifyPairingCode(pairingCode))
                        {
                            System.Diagnostics.Debug.WriteLine(
                                $"[P2P] 拒绝握手：设备 {deviceId} 未注册且配对码缺失/无效");
                            return (BuildError("invalid_pairing_code"), true);
                        }

                        // 配对成功：绑定证书指纹
                        _databaseService.UpsertDevice(deviceId, deviceName, peerFingerprint);
                        System.Diagnostics.Debug.WriteLine(
                            $"[P2P] 新设备配对成功: {deviceId} ({deviceName})，指纹已绑定");
                    }

                    handshakeAccepted = true;

                    // 握手消息：回复策略 + 公告推送
                    return (_syncService.BuildPolicyPushMessage(deviceId), false);
                }

                case "usage_report":
                {
                    if (!handshakeAccepted) return ("", true);  // 未认证：忽略并关闭
                    // 接收儿童端使用时长报告
                    var deviceId = payload.TryGetProperty("deviceId", out var udid)
                        ? udid.GetString() ?? "unknown" : "unknown";
                    var recordsJson = payload.TryGetProperty("records", out var rec)
                        ? rec.GetString() ?? "[]" : "[]";
                    var count = _syncService.HandleUsageReport(deviceId, recordsJson);
                    return (_syncService.BuildSyncAck(count), false);
                }

                case "heartbeat":
                    if (!handshakeAccepted) return ("", true);  // 未认证：不响应心跳
                    // 心跳：保持连接活跃
                    return ("{\"type\":\"heartbeat_ack\",\"payload\":{}}", false);

                default:
                    System.Diagnostics.Debug.WriteLine($"未知消息类型: {type}");
                    return (BuildError("unknown type"), false);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"消息处理异常: {ex.Message}");
            return (BuildError(ex.Message), false);
        }
    }

    /// <summary>
    /// 构建错误响应帧（JsonSerializer 序列化，防消息内容注入破坏 JSON 结构）
    /// </summary>
    private static string BuildError(string message)
    {
        return JsonSerializer.Serialize(new
        {
            type = "error",
            payload = new { message }
        });
    }

    /// <summary>
    /// 加载或创建 TLS 自签名证书
    /// P2P-FIX: SAN 包含局域网 IP 地址，支持儿童端通过 IP 直连验证
    ///
    /// [SEC-P1] PFX 密码以 DPAPI 加密落盘（原为明文 p2p_cert.key，红线 R4.x）；
    /// 数据目录从 Xiaopacai 迁移到 XiaopacaiParent（与数据库同目录），
    /// 旧明文密钥文件在迁移成功后删除。
    /// </summary>
    private X509Certificate2 LoadOrCreateCertificate()
    {
        var dataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "XiaopacaiParent");
        Directory.CreateDirectory(dataDir);
        var certPath = Path.Combine(dataDir, "p2p_cert.pfx");
        var keyPath = Path.Combine(dataDir, "p2p_cert.key");

        // 已有证书：加载复用（密码 DPAPI 保护）
        if (File.Exists(certPath) && File.Exists(keyPath))
        {
            try
            {
                var encryptedPwd = File.ReadAllText(keyPath).Trim();
                var pwd = CryptoService.UnprotectWithDpapi(encryptedPwd);
                return new X509Certificate2(certPath, pwd, X509KeyStorageFlags.Exportable);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"加载 P2P 证书失败，重新生成: {ex.Message}");
            }
        }

        // [SEC-P1] 旧版本迁移：Xiaopacai 目录下的明文密码证书 → DPAPI 保护新目录
        var legacyDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Xiaopacai");
        var legacyCertPath = Path.Combine(legacyDir, "p2p_cert.pfx");
        var legacyKeyPath = Path.Combine(legacyDir, "p2p_cert.key");
        if (File.Exists(legacyCertPath) && File.Exists(legacyKeyPath))
        {
            try
            {
                var legacyPwd = File.ReadAllText(legacyKeyPath).Trim();
                var legacyCert = new X509Certificate2(
                    legacyCertPath, legacyPwd, X509KeyStorageFlags.Exportable);
                // 新随机密码 + DPAPI 落盘，证书内容（含指纹）不变，儿童端无需重新配对
                var newPassword = CryptoService.GenerateDatabaseKey(32);
                var pfxBytes = legacyCert.Export(X509ContentType.Pfx, newPassword);
                File.WriteAllBytes(certPath, pfxBytes);
                File.WriteAllText(keyPath, CryptoService.ProtectWithDpapi(newPassword));
                try { File.SetAttributes(keyPath, FileAttributes.Hidden); } catch { }
                // 迁移成功后删除旧明文密钥文件
                try { File.Delete(legacyKeyPath); } catch { }
                try { File.Delete(legacyCertPath); } catch { }
                System.Diagnostics.Debug.WriteLine("[P2P] 旧明文 P2P 证书已迁移至 DPAPI 保护");
                return legacyCert;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"旧 P2P 证书迁移失败: {ex.Message}");
            }
        }

        // 首次：生成并持久化（密码 DPAPI 保护）
        var cert = CreateSelfSignedCertificate();
        var password = CryptoService.GenerateDatabaseKey(32);
        try
        {
            var pfxBytes = cert.Export(X509ContentType.Pfx, password);
            File.WriteAllBytes(certPath, pfxBytes);
            File.WriteAllText(keyPath, CryptoService.ProtectWithDpapi(password));
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
