# 小趴菜系统 · 管理员运维手册

> 版本：V1.2 ｜ 更新日期：2026-08-15 ｜ 适用：阿里云公网生产 + 本机预发布（50.11）
> 维护约定：**每次系统升级/部署后必须同步更新本文档**（版本号 + 变更记录 + 相关命令/路径变化）。
> 凭据策略：本文档不写任何明文密码/密钥；需要时从 `/etc/xiaopacai-web.env`（服务器）或
> `C:/Users/winann/.codex/skills/xiaopacai-collab/references/current-state.md`（本地技能库）读取。

---

## 版本与更新记录

| 版本 | 日期 | 变更内容 | 维护人 |
|---|---|---|---|
| V1.0 | 2026-08-15 | 初版：架构/日常运维/升级/备份/安全/排障/巡检/监控 | Codex |
| V1.1 | 2026-08-15 | 公网域名 `xpc.winann.com` + HTTPS 反代（nginx）已启用；邮箱验证已启用；管理端邮件配置；管理员引导创建说明 | Codex |
| V1.2 | 2026-08-15 | v1.1.1 加固版：守护健康度/失守事件运维（guard_events）、日志表修复运维（app_logs）、下载中心 1.1.1、系统说明书归档 | Codex |

> 后续每次发版，在“第九章 附录 B”追加记录，并同步修订正文。

---

## 第一章 系统架构与部署清单

### 1.1 组件

- Web 服务端：ASP.NET Core 8（`XiaopacaiWeb.dll`），自托管单进程（Web 5000 + P2P TLS 9527）。
- 前端：Vue 3 + Element Plus，构建产物 `wwwroot/`。
- 数据库：SQLite（当前生产为**明文 SQLite**，待按 R6.2 加固为加密库；加固前禁止外传库文件）。
- 客户端：Android 儿童端/家长端（双角色）、Windows 家长端。
- 中继：儿童端/家长端经 Web 服务器 P2P TLS 通道互联（云端中继）。

### 1.2 服务器与部署清单

| 环境 | 地址 | 说明 |
|---|---|---|
| 阿里云公网生产 | `https://xpc.winann.com`（nginx 反代） | 入口 443/80 → 本机 `127.0.0.1:5000`；P2P `:9527` 直连；部署目录 `/opt/xiaopacai/app`；IP 直连 `http://8.217.165.122:5000` 仍可用（待收敛） |
| 本机预发布 | 192.168.50.11 | 部署目录 `/home/winann/xiaopacai-web`；Web `:5000`、P2P `:9527` |
| 开发机 | 192.168.50.53（Claude）/ 50.20（Codex） | 源码与测试 |

### 1.3 生产目录结构（阿里云 `/opt/xiaopacai/app`）

```text
/opt/xiaopacai/app/
├── XiaopacaiWeb.dll / XiaopacaiWeb          # 服务端程序
├── wwwroot/                                 # 前端静态资源（含 downloads/ 下载中心）
├── Data/
│   ├── xiaopacai.db                         # 业务数据库
│   ├── certs/                               # P2P TLS 证书
│   └── .dbkey（若已加密）                    # 库密钥（严禁外传）
├── appsettings*.json                        # 非敏感默认配置
├── docs/                                    # 运维文档（本手册）
└── app.bak-YYYYMMDD-HHMMSS/                 # 升级前备份（见第三章）
```

环境变量文件：`/etc/xiaopacai-web.env`（含 JWT/DB/P2P 密钥，**root 0600，严禁覆盖/外传**）。
systemd 服务：`xiaopacai-web`。

### 1.4 端口

| 端口 | 用途 | 公网暴露 | 备注 |
|---|---|---|---|
| 443/80 | HTTPS/HTTP（nginx 反代） | 是 | 已启用 `xpc.winann.com`；HTTP 80 已 301 跳转 HTTPS |
| 5000 | Web/API（Kestrel 直连） | 当前是 | 建议安全组收敛为仅 nginx 本机来源（127.0.0.1） |
| 9527 | P2P TLS | 当前是 | 建议后续按来源白名单收敛 |

---

## 第二章 日常运维

### 2.1 健康检查

```bash
# 服务器本机（无 curl 时用 python）
curl -s http://127.0.0.1:5000/api/health
# 或
python3 -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:5000/api/health').read().decode())"
```

正常返回：`{"status":"healthy","version":"3.0.0-p2",...}`。
公网入口：`https://xpc.winann.com/api/health`（经 nginx 反代，HSTS 已启用）；
本机直连：`http://127.0.0.1:5000/api/health`；IP 直连：`http://8.217.165.122:5000/api/health`。

### 2.2 服务管理

```bash
sudo systemctl status  xiaopacai-web          # 状态（active running / Main PID）
sudo systemctl restart xiaopacai-web          # 重启
sudo systemctl stop    xiaopacai-web          # 停止（升级前）
sudo systemctl start   xiaopacai-web          # 启动
sudo systemctl enable  xiaopacai-web          # 开机自启（已启用）
```

### 2.3 日志

```bash
sudo journalctl -u xiaopacai-web -n 200 -l    # 最近 200 行
sudo journalctl -u xiaopacai-web -f           # 实时跟踪
sudo journalctl -u xiaopacai-web --since "10 min ago"
```

关键关键字：
- 启动：`[小趴菜 Web 3.0] 启动成功`、`[DB] EnsureCreated 完成`
- P2P：`[P2P] TCP/TLS 监听已启动`、`[P2P-Handshake]`、`[P2P-Usage]`、`[P2P-Announce]`
- 安全：`policy.reset_limit`、`change_password_failed`、限速/越权相关审计
- 错误：`Exception`、`Failed`、`Warning`

### 2.4 端口与会话

```bash
sudo ss -ltnp | grep -E ':5000|:9527'
```

P2P 活跃会话可查库：

```bash
sqlite3 /opt/xiaopacai/app/Data/xiaopacai.db \
  "SELECT DeviceId, Role, Status, ConnectedAt FROM relay_sessions WHERE Status='connected' ORDER BY ConnectedAt DESC LIMIT 20;"
```

### 2.5 数据库巡检（生产当前为明文 SQLite）

```bash
DB=/opt/xiaopacai/app/Data/xiaopacai.db
sqlite3 $DB "PRAGMA integrity_check;"                       # 完整性（期望 ok）
sqlite3 $DB "SELECT Id,DeviceName,DeviceId,OnlineStatus,LastSeenAt FROM devices ORDER BY Id;"
sqlite3 $DB "SELECT Id,DeviceId,DailyLimitMinutes,BedtimeStart,BedtimeEnd FROM policies;"
sqlite3 $DB "SELECT Id,Username,Role,IsActive,last_login_at FROM users;"
sqlite3 $DB "SELECT action,COUNT(*) FROM audit_logs WHERE CreatedAt>=datetime('now','-1 day') GROUP BY action;"
```

> 安全提醒：明文库文件权限应为 `0600`（服务账号可读）；巡检命令不要在含敏感输出的日志里留存。

### 2.6 下载中心

```bash
ls -lh /opt/xiaopacai/app/wwwroot/downloads/
md5sum /opt/xiaopacai/app/wwwroot/downloads/*.apk
# 公网抽查（域名反代入口）
curl -sI https://xpc.winann.com/downloads/<最新 Release APK 文件名>   # 期望 200
```

> 当前 APK 为 ABI 拆分（arm64-v8a / armeabi-v7a / x86_64）的 Release 签名包（旧 debug 已删除）；
> 下载中心文件名与页面引用必须一致；当前线上版本 v1.1.1（versionName 1.1.1 / versionCode 10101），
> 旧 1.1.0 安装包已移除，如需回退用备份 `/opt/xiaopacai/app.bak-20260815-225329` 内的下载文件。

### 2.7 公告 / 策略推送验证

- Web 发布公告/保存策略后，儿童端在线应 ≤5 秒收到（公告通知直达；紧急公告全屏置顶）。
- 查询推送记录：

```bash
sqlite3 $DB "SELECT Id,Title,Status,TargetDeviceId,CreatedAt FROM announcements ORDER BY Id DESC LIMIT 10;"
sqlite3 $DB "SELECT Id,Action,TargetType,TargetId,CreatedAt FROM audit_logs ORDER BY Id DESC LIMIT 20;"
```

### 2.8 限速自锁注意事项（SEC-K3，修复前）

- 当前已知缺口：P2P 限速拒绝帧缺少 `error_code`，客户端可能无限重试并把来源 IP 锁 5 分钟+。
- **在修复完成前，不要用模拟器/脚本频繁连生产 P2P**；真机扫码绑定失败时先等服务端修复（见待办）。
- 若 IP 被锁：停止客户端重试并等待冷却，或由管理员在服务器侧清理限速计数（修复后提供正式接口/命令）。

### 2.9 邮箱验证与邮件配置（已启用）

注册流程（邮箱验证码）：
1. 用户在 Web 注册页输入邮箱 → 调用 `POST /api/auth/email-code` 发送 6 位验证码。
2. 用户查收邮件（含发件人、验证码、有效期）→ 注册时提交 `code` 完成账号创建。
3. 验证码有有效期与限频（按 IP/小时 + 按邮箱/小时），发送/成功/失败均写入审计。

邮件通道配置（管理员）：

| 接口 | 说明 |
|---|---|
| `GET  /api/admin/mail-config` | 查看配置（Secret 脱敏） |
| `PUT  /api/admin/mail-config` | 保存配置（热生效；Secret 留空=不变） |
| `POST /api/admin/mail-config/test` | 发送测试邮件 |

- 通道：`api`（阿里云 DirectMail）或 `smtp`；配置项含 SMTP Host/Port/User/SSL；密钥走加密存储（SecretCrypto 主密钥），不回显。
- 审计关键字：`email_code_sent` / `email_code_send_failed` / `email_code_rate_limited` / `email_code_unconfigured` / `mail_config_update` / `mail_config_test`。
- 常见故障：收不到验证码 → 先查审计 `email_code_send_failed` 与邮件通道配置；再查垃圾箱/发信限额；最后 `POST /api/admin/mail-config/test` 验证通道。

### 2.10 守护健康度与失守事件（v1.1.1 新增）

儿童端守护状态（无障碍/使用情况访问/设备管理员/通知/前台服务/电量优化）会形成健康度快照并上报：

| 接口 | 说明 |
|---|---|
| `POST /api/guard-events` | 儿童端批量上报失守/恢复事件与健康度快照（账号隔离，限频） |
| `GET  /api/guard-events?deviceId=&limit=` | 查询失守历史（本账号设备；admin 可全量过滤） |
| `GET  /api/guard-events/health?deviceId=` | 查询最新健康度快照（score/100 + 6 项权限状态） |

- 数据表：`guard_events`（显式 ToTable，与 DDL 同名）；7 天保留策略同 `app_logs`。
- Web 设备管理页会展示守护健康度徽标与详情；家长端“守护状态”页展示 score 与失守历史。
- 运维巡检：失守事件应能解释（上滑/强杀/OEM 清理/权限被关）；若频繁失守，按 2.10 引导家长开启 OPPO 保活四项（自启动/后台冻结/电池白名单/最近任务锁定），并确认“设置-强制停止”按钮对设备管理器应用应处于禁用态。

---

## 第三章 发布与升级流程（标准操作）

> 每次升级前：阅读本文档“版本与更新记录”，确认备份与回滚预案；升级后更新本文档。

### 3.1 本地构建与测试（必须在升级前全绿）

```powershell
# Web 前端
cd C:\Users\Public\bridge\work\xiaopacai-web\web; npm run build
# Web 服务端测试
C:\dotnet\dotnet test C:\Users\Public\bridge\work\xiaopacai-web\tests\xiaopacai-web.Tests.csproj -c Release
# 服务端发布（Linux 自包含）
C:\dotnet\dotnet publish C:\Users\Public\bridge\work\xiaopacai-web\server\xiaopacai-web.csproj -c Release -r linux-x64 --self-contained true
# Android（含单测与 Release 签名；签名参数读 gradle.properties）
cd C:\Users\Public\bridge\work\xiaopacai\android; .\gradlew.bat testDebugUnitTest assembleRelease
# Windows
C:\dotnet\dotnet build C:\Users\Public\bridge\work\xiaopacai\windows\XiaopacaiParent\XiaopacaiParent.csproj -c Release
C:\dotnet\dotnet test  C:\Users\Public\bridge\work\xiaopacai\tests\XiaopacaiParent.Tests\XiaopacaiParent.Tests.csproj -c Release
```

> 当前基线（v1.1.1）：Web 303/303、Android 154/154、Windows 15/15、npm build 通过。

### 3.2 打包与上传

```bash
# 服务器侧准备目录后，用工具上传（Codex 侧 aliyun_sftp.py push，或 scp）
tar czf xiaopacai-web-update.tgz -C build/server .
```

> 上传工具与脚本见 `C:\Users\Public\bridge\work\tmp-web-e2e\`（aliyun_sftp.py / aliyun_update.py）。

### 3.3 备份（升级前必做）

```bash
sudo systemctl stop xiaopacai-web
TS=$(date +%Y%m%d-%H%M%S)
sudo cp -r /opt/xiaopacai/app /opt/xiaopacai/app.bak-$TS
sudo cp /etc/xiaopacai-web.env /etc/xiaopacai-web.env.bak-$TS
```

### 3.4 部署与启动

```bash
# 解压覆盖（保留 Data/、downloads/、appsettings.Production.json、env）
sudo tar xzf xiaopacai-web-update.tgz -C /opt/xiaopacai/app
sudo systemctl start xiaopacai-web
sleep 5
curl -s http://127.0.0.1:5000/api/health
```

### 3.5 上线验证清单

- [ ] `/api/health` healthy，版本号符合预期
- [ ] Web 页面可登录（admin/parent），httpOnly Cookie 生效
- [ ] 设备列表显示在线设备；儿童端心跳正常（LastSeen 更新）
- [ ] 发布一条测试公告，儿童端 ≤5s 收到
- [ ] 保存策略（限额/就寝），儿童端日志出现 `policy_update`
- [ ] 下载中心 APK 返回 200
- [ ] `GET /api/logs` 返回 200（v1.1.1 起修复；此前实体未映射 `app_logs` 表会 500）
- [ ] `GET /api/guard-events` 返回 200；设备页健康度展示与真机一致
- [ ] 无新报错日志（journalctl 关键字 Exception/Failed）

### 3.6 回滚

```bash
sudo systemctl stop xiaopacai-web
sudo rm -rf /opt/xiaopacai/app                     # 仅当确认备份完好
sudo cp -r /opt/xiaopacai/app.bak-$TS /opt/xiaopacai/app
sudo cp /etc/xiaopacai-web.env.bak-$TS /etc/xiaopacai-web.env
sudo systemctl start xiaopacai-web
```

> 回滚后：设备 P2P 指纹若与备份库不一致，需重新配对（见第六章 6.4）。

---

## 第四章 备份与恢复

### 4.1 备份内容与位置

| 内容 | 路径 | 说明 |
|---|---|---|
| 业务数据库 | `/opt/xiaopacai/app/Data/` | 核心数据，必须加密后异地 |
| 环境/密钥 | `/etc/xiaopacai-web.env` | 含 JWT/DB/P2P 密钥 |
| 下载中心 | `wwwroot/downloads/` | APK/ZIP 安装包 |
| systemd 单元 | `/etc/systemd/system/xiaopacai-web.service` | 服务定义 |

### 4.2 备份策略（建议 cron，每日 03:00）

```bash
#!/bin/bash
TS=$(date +%Y%m%d)
SRC=/opt/xiaopacai/app
BAK=/opt/backups/xiaopacai
mkdir -p $BAK
sqlite3 $SRC/Data/xiaopacai.db ".backup '$BAK/xiaopacai-$TS.db'"
sqlite3 $BAK/xiaopacai-$TS.db "PRAGMA integrity_check;" > $BAK/integrity-$TS.log
tar czf $BAK/downloads-$TS.tgz -C $SRC/wwwroot downloads
cp /etc/xiaopacai-web.env $BAK/env-$TS.env
# 加密后异地（示例：openssl 对称加密，密钥另行保管）
openssl enc -aes-256-cbc -salt -pbkdf2 -in $BAK/xiaopacai-$TS.db \
  -out $BAK/xiaopacai-$TS.db.enc -pass file:/opt/backups/.bakkey
```

- 保留策略：本地保留 14 天；至少一份异机/对象存储。
- 每月做一次恢复演练（见 4.4）。

### 4.3 恢复

```bash
sudo systemctl stop xiaopacai-web
cp /opt/xiaopacai/app/Data/xiaopacai.db /opt/xiaopacai/app/Data/xiaopacai.db.corrupt-$(date +%Y%m%d%H%M%S)
cp /opt/backups/xiaopacai/xiaopacai-YYYYMMDD.db /opt/xiaopacai/app/Data/xiaopacai.db
chown 服务账号:服务账号 /opt/xiaopacai/app/Data/xiaopacai.db
chmod 600 /opt/xiaopacai/app/Data/xiaopacai.db
sudo systemctl start xiaopacai-web
curl -s http://127.0.0.1:5000/api/health
```

### 4.4 恢复演练（月度）

- 在预发布机 50.11 恢复最近备份 → 启动 → 登录 → 设备列表 → 公告/策略 → 比对数据条数。
- 记录演练结果到“第九章 附录 B”。

---

## 第五章 安全管理

### 5.1 账号口令

- 注册：新家长账号通过邮箱注册，必须填写邮箱验证码（`/api/auth/email-code`），未验证邮箱不能注册。
- 管理员引导创建：`users` 表为空时不再播种默认账号；须配置环境变量
  `ADMIN_EMAIL` + `ADMIN_INITIAL_PASSWORD` 才会创建管理员（`MustChangePassword=true`，首次登录强制改密）。
- 定期（建议 90 天）轮换；改密走 Web“设置 → 修改密码”（httpOnly Cookie 会话）。
- 忘记密码：Web 忘记密码 → 家长端 App 扫码确认；admin 重置需服务器/数据库人工处理。
- 审计：登录失败、改密失败必须能在 `audit_logs` 查到。

### 5.2 密钥与配置文件

```bash
sudo chmod 600 /etc/xiaopacai-web.env
sudo ls -l /opt/xiaopacai/app/Data/certs/
```

- JWT/DB/P2P 密钥只存在于 env 与本地技能库；**禁止写入仓库、日志、信件、截图**。
- Android Release 签名密钥：`xiaopacai-release.jks`，口令在 `keystore.secret` + gradle.properties，禁止入库。

### 5.3 网络安全

- **HTTPS/反代已启用**：`xpc.winann.com` 经 nginx/1.24 反代到 `127.0.0.1:5000`，HSTS 已开启（30 天）。
- 已生效：HTTP 80 → 301 跳转 HTTPS。建议后续：阿里云安全组将 5000 收敛为仅 nginx 本机来源、9527 按来源白名单。
- 服务器本机：`sudo ufw status`；仅开放必要端口；禁 root 远程登录建议。

### 5.4 P2P 安全

- TLS 指纹固定：阿里云 P2P 证书指纹（2026-08-15 清库重装后已更新）
  `a518ac3b57130ec697e9927de23d4bf69388f844651c6ea46404bb0157ca449e`；
  客户端换服务器/证书后必须重新配对，否则拒绝连接（fingerprint_mismatch）。
- 配对码：6 位、一次性、限流；已配对设备重连免码按指纹放行（scan-fix 已合入）。
- 解绑/换绑：Web 解绑会清空归属与配对码，可重新扫码绑定。
- 巡检中继会话与握手拒绝日志，发现异常（频繁 invalid_pairing_code / ip_rate_limited）及时处理。

### 5.5 Web 安全

- 已启用：httpOnly Cookie 会话、CSP/安全响应头（nosniff、X-Frame-Options）、限速、输入白名单、审计。
- 巡检：`curl -sI http://8.217.165.122:5000/` 检查响应头；Swagger 生产必须关闭。
- 日志脱敏：日志中不得出现密码/令牌/密钥（升级时保持）。

### 5.6 审计日志

```bash
sqlite3 $DB "SELECT CreatedAt,UserId,Action,TargetType,TargetId,Detail FROM audit_logs ORDER BY Id DESC LIMIT 100;"
```

重点关注：`login`（失败）、`change_password_failed`、`pair`、`unpair`、`policy.*`、`announcement.*`、`data_export`、`account.*`。

### 5.7 依赖漏洞扫描（月度）

```bash
cd web && npm audit
cd server && dotnet list package --vulnerable
# Android：OWASP dependency-check（或 CI 集成）
```

高危依赖：禁止上线；先升级/替换再发版。

### 5.8 数据隐私

- 儿童使用数据最小化：服务器仅保留“账号/互联/诊断/报告”所需；诊断字段去标识化。
- 注销/解绑：级联清理设备、策略、公告、报告、诊断、中继会话；备份中的残留按保留策略过期清理。
- 导出/下载：必须鉴权 + 审计；导出文件不得含他人数据。

### 5.9 应急安全事件处置

1. 发现入侵/数据泄露/异常登录 → 立即在阿里云安全组封锁来源/整机外网入口。
2. 保留现场：journalctl 快照、数据库副本、审计日志（只读归档）。
3. 判断影响面：账号、设备、公告、密钥（若密钥泄露必须轮换 env + 证书）。
4. 从干净备份恢复；恢复后全量回归（含设备重新配对）。
5. 记录事件至“附录 B”，并按需通知用户。

---

## 第六章 检修与故障排查

| 现象 | 可能原因 | 排查/处置 |
|---|---|---|
| 服务起不来 | 端口被占 / env 缺失 / 权限 / DB 损坏 | `journalctl -u xiaopacai-web -n 100`；`ss -ltnp`；检查 env 0600；`PRAGMA integrity_check` |
| 健康检查失败 | 进程未起 / 端口未监听 | `systemctl status`；`ss -ltnp`；确认 `Urls` 配置 |
| Web 打不开/白屏 | wwwroot 缺失/旧缓存 | 检查 `wwwroot/index.html`；`npm run build` 后重发静态 |
| 儿童端连不上 P2P | 指纹不匹配 / 配对码无效 / IP 被限速锁 / 中继未登记 | 看 `[P2P-Handshake]` 日志；核对指纹；重新扫码配对；等待限速冷却 |
| 公告收不到 | 设备离线 / target 不匹配 / 紧急公告未确认 | 设备列表在线状态；`announcements` 状态；儿童端日志 |
| 策略不生效 | 时间格式错误 / 未推送 / 缓存 | 检查 policies 表 BedtimeStart 必须 `HH:mm`；Web 保存后确认 pushed；儿童端 `policy_update` 日志 |
| 设备一直离线 | 网络/心跳超时 / 会话未回收 | `relay_sessions` 状态；心跳 30s；重启服务观察 |
| 数据库异常 | 磁盘满 / 文件损坏 | `df -h`；`PRAGMA integrity_check`；按 4.3 恢复 |
| IP 被限速锁定 | K3 缺口（修复前） | 停止重试等待冷却；修复后清理计数 |

通用定位三连：

```bash
sudo journalctl -u xiaopacai-web -n 100 -l
curl -s http://127.0.0.1:5000/api/health
sqlite3 /opt/xiaopacai/app/Data/xiaopacai.db "PRAGMA integrity_check;"
```

---

## 第七章 巡检与保养

### 每日（5 分钟）

- [ ] `/api/health` 200
- [ ] `systemctl is-active xiaopacai-web` = active
- [ ] 磁盘 `df -h`（阈值 <80%）
- [ ] 日志无新增 Exception/Failed
- [ ] 设备列表在线状态与 LastSeen 正常

### 每周（15 分钟）

- [ ] 备份完整性校验（integrity log 为 ok）
- [ ] 下载中心文件与校验和抽查
- [ ] 审计日志抽查（登录失败/越权/配对）
- [ ] relay_sessions 无异常堆积

### 每月（1 小时）

- [ ] 依赖漏洞扫描（npm audit / dotnet / Android）
- [ ] 口令轮换检查（admin/parent）
- [ ] 恢复演练（预发布机）
- [ ] 证书有效期检查（P2P/HTTPS）
- [ ] 云资源账单/到期续费/快照检查（阿里云“保修”与续费）
- [ ] 手册与版本记录核对更新

---

## 第八章 监控与告警建议

- 健康探针：每分钟请求 `/api/health`，失败即告警（短信/钉钉/微信机器人）。
- 磁盘：`df` 阈值告警。
- 进程：systemd 自带 Restart=on-failure；配合 `systemctl is-active` 探针。
- 登录失败：审计表聚合，短时多次失败触发告警（爆破信号）。
- P2P：会话数突增/握手失败率突增告警。
- 备份：每日备份成功/完整性告警。

---

## 第九章 附录

### A. 常用命令速查

```bash
# 服务与日志
sudo systemctl {status|restart|stop|start} xiaopacai-web
sudo journalctl -u xiaopacai-web -f
# 反代（nginx）
sudo nginx -t && sudo systemctl reload nginx
curl -sI https://xpc.winann.com/api/health
# 健康与端口
curl -s https://xpc.winann.com/api/health
curl -s http://127.0.0.1:5000/api/health
sudo ss -ltnp | grep -E ':5000|:9527'
# 数据库
DB=/opt/xiaopacai/app/Data/xiaopacai.db
sqlite3 $DB "PRAGMA integrity_check;"
# 邮件配置（mail_config 表）
sqlite3 $DB "SELECT Id,Channel,IsConfigured,SmtpHost,SmtpPort,LastTestAt FROM mail_config;"
# 审计
sqlite3 $DB "SELECT CreatedAt,UserId,Action,Detail FROM audit_logs ORDER BY Id DESC LIMIT 50;"
```

### B. 版本与变更记录（持续维护）

| 日期 | 系统版本 | 手册版本 | 变更摘要 | 操作人 |
|---|---|---|---|---|
| 2026-08-15 | 3.0.0-p2 | V1.0 | 初版手册 | Codex |
| 2026-08-15 | 3.0.0-p2 | V1.1 | 域名 xpc.winann.com + HTTPS 反代、邮箱验证与邮件配置运维 | Codex |
| 2026-08-15 | v1.1.1 | V1.2 | 四项 P0 加固上线（上滑失效/倒计时/日志 500/权限丢失）；guard_events 与健康度运维；下载中心 1.1.1；系统说明书 SYSTEM_MANUAL.md | Codex |

### C. 待办安全/运维项

- [x] HTTPS + 域名 `xpc.winann.com`（R3.1，2026-08-15 已启用）
- [x] HTTP 80 → 301 跳转 HTTPS（2026-08-15 已生效）
- [ ] 生产 5000 端口收敛（仅 nginx 本机来源）
- [ ] 生产数据库加密（R6.2）
- [x] SEC-K3 限速自锁修复与复测（2026-08-15 已上线）
- [ ] 生产 P2P 端口来源白名单
- [ ] 监控告警落地（探针/磁盘/审计）
- [ ] 备份加密与异机存储落地
- [ ] 邮件通道可用性与发信限额巡检（每月）

---

*本文档随系统升级持续更新；升级发版时务必同步修订“版本与更新记录”与相关命令。*
