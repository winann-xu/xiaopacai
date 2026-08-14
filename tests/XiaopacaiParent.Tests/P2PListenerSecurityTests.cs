using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.IO;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using XiaopacaiParent.Services;
using Xunit;

namespace XiaopacaiParent.Tests;

/// <summary>
/// [SEC] Windows 家长端 P2P 监听器安全复测（真实 TCP+TLS）：
/// 1. 未配对设备无配对码 → 拒绝 invalid_pairing_code
/// 2. 错误配对码 5 次 → 5 分钟锁定（正确码也拒绝）
/// 3. 已配对设备重连免码；证书指纹不匹配 → 拒绝 fingerprint_mismatch
/// 4. 并发连接上限 20（第 21 个被拒绝）
/// </summary>
public class P2PListenerSecurityTests : IDisposable
{
    private readonly string _dir;
    private readonly DatabaseService _db;
    private readonly P2PListenerService _listener;
    private readonly int _port;
    private X509Certificate2? _certA;
    private X509Certificate2? _certB;

    public P2PListenerSecurityTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "xpc-p2p-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _db = new DatabaseService(_dir, "testpass");
        _port = GetFreePort();
        _listener = new P2PListenerService(_db, _port);
        _listener.Start();
        Thread.Sleep(500);
        Assert.True(_listener.IsRunning, "P2P 监听器应已启动");
    }

    public void Dispose()
    {
        _listener.Stop();
        _db.Dispose();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private static int GetFreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    private X509Certificate2 CreateClientCert(string cn)
    {
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest(
            "CN=" + cn, rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        var cert = req.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddDays(1));
        // SChannel 需要可导出私钥：PFX 导出后以 EphemeralKeySet 导入
        var pfx = cert.Export(X509ContentType.Pkcs12, "xpc-test");
        // SChannel 不支持 EphemeralKeySet：使用默认密钥集（测试机用户存储）
        return new X509Certificate2(pfx, "xpc-test", X509KeyStorageFlags.Exportable);
    }

    private X509Certificate2 CertA => _certA ??= CreateClientCert("xpc-test-child-a");
    private X509Certificate2 CertB => _certB ??= CreateClientCert("xpc-test-child-b");

    private async Task<(SslStream Stream, TcpClient Tcp)> ConnectTlsAsync(X509Certificate2 clientCert)
    {
        var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, _port);
        var ssl = new SslStream(tcp.GetStream(), false, (_, _, _, _) => true);
        await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "localhost",
            ClientCertificates = new X509CertificateCollection { clientCert },
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
        });
        return (ssl, tcp);
    }

    private static async Task SendFrameAsync(Stream stream, string json)
    {
        var bytes = Encoding.UTF8.GetBytes(json);
        var len = BitConverter.GetBytes(bytes.Length);
        if (BitConverter.IsLittleEndian) Array.Reverse(len);
        await stream.WriteAsync(len);
        await stream.WriteAsync(bytes);
        await stream.FlushAsync();
    }

    private static async Task<string?> ReadFrameAsync(Stream stream, int timeoutMs = 5000)
    {
        stream.ReadTimeout = timeoutMs;
        var header = new byte[4];
        var n = 0;
        while (n < 4)
        {
            var r = await stream.ReadAsync(header.AsMemory(n, 4 - n));
            if (r <= 0) return null;
            n += r;
        }
        if (BitConverter.IsLittleEndian) Array.Reverse(header);
        var len = BitConverter.ToInt32(header, 0);
        if (len <= 0 || len > 1_000_000) return null;
        var body = new byte[len];
        n = 0;
        while (n < len)
        {
            var r = await stream.ReadAsync(body.AsMemory(n, len - n));
            if (r <= 0) return null;
            n += r;
        }
        return Encoding.UTF8.GetString(body);
    }

    private static string Handshake(string deviceId, string? pairingCode, string? fingerprint = null)
    {
        return JsonSerializer.Serialize(new
        {
            type = "handshake",
            payload = new
            {
                deviceId,
                deviceName = "test-child",
                pairingCode,
                certFingerprint = fingerprint,
            },
        });
    }

    private async Task<(string? Response, SslStream Stream, TcpClient Tcp)> DoHandshakeAsync(
        string deviceId, string? code, X509Certificate2 cert, string? payloadFp = null)
    {
        var (stream, tcp) = await ConnectTlsAsync(cert);
        await SendFrameAsync(stream, Handshake(deviceId, code, payloadFp));
        var resp = await ReadFrameAsync(stream);
        return (resp, stream, tcp);
    }

    [Fact]
    public async Task Unpaired_NoPairCode_Rejected()
    {
        var (resp, stream, tcp) = await DoHandshakeAsync("new-no-code", null, CertA);
        try { Assert.Contains("invalid_pairing_code", resp); }
        finally { stream.Dispose(); tcp.Dispose(); }
    }

    [Fact]
    public async Task WrongPairCode_FiveTimes_Locks_EvenCorrectCode()
    {
        var goodCode = _listener.GeneratePairingCode();
        for (var i = 0; i < 5; i++)
        {
            var (resp, stream, tcp) = await DoHandshakeAsync("new-lock-" + i, "000000", CertA);
            try { Assert.Contains("invalid_pairing_code", resp); }
            finally { stream.Dispose(); tcp.Dispose(); }
        }

        // 锁定期内，即使使用正确配对码也拒绝（5 分钟锁定）
        var (lockedResp, lockedStream, lockedTcp) = await DoHandshakeAsync("new-lock-5", goodCode, CertA);
        try { Assert.Contains("invalid_pairing_code", lockedResp); }
        finally { lockedStream.Dispose(); lockedTcp.Dispose(); }
    }

    [Fact]
    public async Task PairedDevice_ReconnectNoCode_Ok_FingerprintMismatch_Rejected()
    {
        var code = _listener.GeneratePairingCode();

        // 首次配对（证书 A）
        var (pairResp, pairStream, pairTcp) = await DoHandshakeAsync("dev-a", code, CertA, "payload-fp-a");
        try { Assert.Contains("policy_update", pairResp); }
        finally { pairStream.Dispose(); pairTcp.Dispose(); }

        // 已配对设备重连：免配对码，同指纹（证书 A）→ 通过
        var (reResp, reStream, reTcp) = await DoHandshakeAsync("dev-a", null, CertA);
        try { Assert.Contains("policy_update", reResp); }
        finally { reStream.Dispose(); reTcp.Dispose(); }

        // 证书指纹不匹配（证书 B）→ 拒绝 fingerprint_mismatch
        var (misResp, misStream, misTcp) = await DoHandshakeAsync("dev-a", null, CertB, "payload-fp-b");
        try { Assert.Contains("fingerprint_mismatch", misResp); }
        finally { misStream.Dispose(); misTcp.Dispose(); }
    }

    [Fact]
    public async Task ConcurrentConnections_Limit20_21stRejected()
    {
        var sockets = new List<TcpClient>();
        try
        {
            for (var i = 0; i < 20; i++)
            {
                var c = new TcpClient();
                await c.ConnectAsync(IPAddress.Loopback, _port);
                sockets.Add(c);
            }
            await Task.Delay(800); // 等服务端完成 20 个 accept 计数

            // 第 21 个连接应被立即关闭
            var c21 = new TcpClient();
            await c21.ConnectAsync(IPAddress.Loopback, _port);
            try
            {
                var buffer = new byte[1];
                var r = await c21.GetStream().ReadAsync(buffer.AsMemory(0, 1)).AsTask()
                    .WaitAsync(TimeSpan.FromSeconds(3));
                Assert.Equal(0, r); // EOF = 服务端拒绝关闭
            }
            catch (IOException)
            {
                // 连接被重置同样视为拒绝
            }
            finally
            {
                c21.Dispose();
            }
        }
        finally
        {
            foreach (var c in sockets) c.Dispose();
        }
    }
}
