# 小趴菜 P2P 通信协议规范 v1.0

> [TASK-D1-04] 本文档定义小趴菜家长端（Windows）与儿童端（Android）之间的 P2P 直连通信协议。

## 一、协议概览

```
┌──────────────────────────────────────────────────────────────┐
│                     P2P 通信层次                              │
├────────────┬─────────────────────────────────────────────────┤
│ 第4层: 应用 │ 数据同步 / 策略下发 / 公告推送 / 状态上报       │
│ 第3层: 传输 │ JSON 消息帧 (长度前缀 + 消息体)                 │
│ 第2层: 安全 │ TLS 1.3 双向认证 (自签名证书 + 指纹校验)        │
│ 第1层: 网络 │ TCP 直连 (mDNS/DNS-SD 发现 + UDP 广播兜底)      │
└────────────┴─────────────────────────────────────────────────┘
```

## 二、发现机制（第1层）

### 2.1 mDNS/DNS-SD 服务发现（主方案）

- **服务类型**: `_xiaopacai._tcp.local.`
- **服务名称**: `xiaopacai-parent-{deviceId}`
- **TXT 记录**:
  - `version=1.0` — 协议版本
  - `port=9527` — 服务端口
  - `deviceId=XP-XXXXXXXXXXXX` — 家长端设备 ID
  - `fingerprint=sha256:xxxx` — 证书指纹（前 16 字符）
- **发现流程**: 儿童端浏览器 → 查询 `_xiaopacai._tcp.local.` → 解析地址与端口 → TCP 连接

### 2.2 UDP 广播兜底

- **广播端口**: `9528`
- **广播频率**: 家长端每 30 秒发送一次
- **包格式**: 固定头部 `XPACAI` (6字节) + JSON payload
```json
{
  "type": "announce",
  "version": "1.0",
  "deviceId": "XP-XXXXXXXXXXXX",
  "port": 9527,
  "fingerprint": "sha256:xxxx"
}
```
- **注意**: 广播仅在 mDNS 不可用时启用，避免网络噪声

### 2.3 手动 IP 输入

- 儿童端"手动连接"界面支持输入家长端 IP:Port
- 适用于 mDNS 和 UDP 广播均不可用的场景（如 VLAN 隔离）

## 三、安全握手（第2层）

### 3.1 证书体系

- 家长端作为 TLS 服务端，持有自签名 X.509 证书
  - 密钥: RSA-2048
  - 签名: SHA-256
  - CN: `xiaopacai-parent-{deviceId}`
  - SAN: `xiaopacai.local, 127.0.0.1, <局域网IP>`
  - 有效期: 365 天
- 儿童端连接时:
  1. 接受服务器证书
  2. 计算证书 SHA-256 指纹
  3. 与配对时记录的指纹比对
  4. 不匹配 → 拒绝连接（可能有中间人）

### 3.2 配对流程

```
家长端                              儿童端
  │                                   │
  │  1. 生成配对码（6位数字）          │
  │  2. 显示配对码 + 二维码           │
  │     (二维码内容: JSON)             │
  │                                   │
  │              3. 扫描二维码/输入配对码
  │                                   │
  │  4. TCP+TLS 连接建立              │
  │◄──────────────────────────────────│
  │                                   │
  │  5. 验证配对码                    │
  │  6. 记录儿童端证书指纹            │
  │  7. 发送配对确认                  │
  │──────────────────────────────────►│
  │                                   │
  │  8. 双方保存证书指纹，配对完成     │
  │                                   │
```

### 3.3 二维码格式

```json
{
  "type": "pairing",
  "deviceId": "XP-XXXXXXXXXXXX",
  "deviceName": "小明的小米手机",
  "port": 9527,
  "fingerprint": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "pairingCode": "123456",
  "ips": ["192.168.1.100", "10.0.0.5"]
}
```

## 四、消息帧协议（第3层）

### 4.1 帧格式

```
┌──────────────┬────────────────────────────────┐
│ 长度 (4字节)  │ 消息体 (JSON, UTF-8)            │
│ Big-Endian   │ 变长                            │
└──────────────┴────────────────────────────────┘
```

### 4.2 消息类型

| 类型 | 方向 | 说明 |
|------|------|------|
| `handshake` | 双向 | TLS 握手完成后发送，携带设备信息与协议版本 |
| `usage_report` | 儿童→家长 | 上报应用使用时长数据（批量增量） |
| `status_update` | 儿童→家长 | 上报当前状态（在线/超时/已停用） |
| `policy_sync` | 家长→儿童 | 策略更新（限额、白名单、就寝时段） |
| `policy_ack` | 儿童→家长 | 策略接收确认 |
| `announcement` | 家长→儿童 | 公告推送 |
| `announcement_ack` | 儿童→家长 | 公告已读确认 |
| `heartbeat` | 儿童→家长 | 心跳（每 30 秒） |
| `heartbeat_ack` | 家长→儿童 | 心跳响应 |
| `exemption` | 家长→儿童 | 豁免/恢复指令 |
| `error` | 双向 | 错误通知 |

### 4.3 消息示例

**Handshake（儿童端 → 家长端）**:
```json
{
  "type": "handshake",
  "version": "1.0",
  "deviceId": "XP-ABCD1234EF56",
  "deviceName": "小明的小米手机",
  "deviceType": "android",
  "osVersion": "Android 14",
  "appVersion": "0.1.0",
  "timestamp": 1691664000
}
```

**Usage Report（儿童端 → 家长端）**:
```json
{
  "type": "usage_report",
  "deviceId": "XP-ABCD1234EF56",
  "date": "2026-08-10",
  "records": [
    {
      "packageName": "com.tencent.mm",
      "appName": "微信",
      "totalMinutes": 45,
      "category": "social"
    }
  ],
  "totalMinutes": 120,
  "timestamp": 1691664000
}
```

**Policy Sync（家长端 → 儿童端）**:
```json
{
  "type": "policy_sync",
  "version": 3,
  "policies": {
    "daily_limit": {
      "maxMinutes": 120,
      "stopMode": "full",
      "whitelist": ["com.example.calculator", "com.example.dictionary"]
    },
    "sleep_time": {
      "start": "21:00",
      "end": "07:00",
      "stopMode": "full"
    }
  },
  "timestamp": 1691664000
}
```

## 五、重连与容错

### 5.1 心跳机制
- 频率: 每 30 秒发送一次 `heartbeat`
- 超时: 连续 3 次无响应（90 秒）视为断开
- 断开后行为:
  - 儿童端使用缓存策略继续执行
  - 自动重连（指数退避: 1s, 2s, 4s, 8s, 16s, 最大 60s）

### 5.2 数据重传
- 儿童端记录每条数据的同步状态
- 重连后增量推送未同步的数据（按 `sync_status=0` 查询）

## 六、安全注意事项

1. **证书固定**: 首次配对后，双方存储对方证书指纹，后续连接必须校验
2. **密钥保护**: 私钥使用 AndroidKeyStore (儿童端) / DPAPI (家长端) 加密存储
3. **传输加密**: 所有数据经 TLS 1.3 加密，禁止降级到明文 TCP
4. **配对码一次性**: 配对码有效期 10 分钟，使用后立即失效
5. **防重放**: 消息携带时间戳 + 递增序列号，超出窗口（±30秒）拒绝

---

*本协议为 v1.0 版本，后续扩展字段通过 JSON 新增 key 实现向前兼容。*
