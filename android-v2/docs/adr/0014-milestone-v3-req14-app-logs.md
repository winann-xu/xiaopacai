# ADR 0014 — 里程碑 V3 需求 14：家长端运行日志 + 上传 Web + Web 日志查看页

- 日期：2026-08-15
- 状态：已采纳（TASK-MILESTONE-V3）
- 范围：Android（日志采集/环形缓冲/日志页/上传）+ Web（app_logs 表 + /api/logs + 日志页）

## 背景

需求 14：家长端设置新增「日志」菜单，展示本机运行详细日志（时间/级别/模块/内容，滚动查看，
支持复制/清空）；日志可同步上传至 Web：普通家长仅能查看自己账号下设备的日志，admin 可查看
全部账号日志；日志脱敏（不含密码、验证码、令牌、密钥明文）；保留策略：本地环形缓冲
（建议 5000 条或 5MB 上限），Web 端保留最近 7 天（决策点 D6：自动定期 + 手动按钮上传）。

此前 Android 无统一应用日志（各模块零散 Logcat），Web 无日志接入端点。

## 决策与实现

### 1. 日志归属 = 账号级（非设备级）

家长端 App 的运行日志是**家长账号自己设备**上的运行痕迹，上传时归属当前绑定的
Web 账号（AccountId）。查询口径：

- 普通家长：`GET /api/logs` 强制 `AccountId = 自己`（服务端过滤，非前端隐藏）；
- admin：默认全部，可选 `accountId` 筛选（复用 B13 账号隔离模式）。

### 2. 双端脱敏（客户端写入 + 服务端入库二次打码）

两端实现**完全一致的 4 条正则**（Kotlin `AppLog.maskSecrets` / C# `AppLogSanitizer.MaskSecrets`）：

| 规则 | 模式 | 替换 |
| --- | --- | --- |
| 密钥赋值 | `(?i)((?:password\|passwd\|pwd\|secret\|token\|api[_-]?key\|access[_-]?key\|auth[_-]?token)\s*[:=]\s*)[^\s,;，；]+` | `$1***` |
| 验证码 | `(?i)((?:验证码\|校验码\|verification[\s_-]?code\|sms[\s_-]?code)\s*[:=：]?\s*)\d{4,8}` | `$1***` |
| JWT | `eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}` | `***` |
| 64 位十六进制 | `(?i)\b[a-f0-9]{64}\b` | `***` |

**边界裁决**：裸 `code:` 不打码——`HTTP code 500` 等常见日志会误伤；仅验证码标签形式
（验证码/校验码/verification code/sms code）打码。服务端入库前二次脱敏兜底（客户端
被篡改/旧版本不脱敏时服务端仍能守住红线）。

### 3. 本地保留：内存环形缓冲 + 文件 JSONL（5MB 上限）

- 内存 `ArrayDeque` 环形缓冲 **5000 条**上限（超出丢弃最旧）；
- 文件 `filesDir/xpc_applog.txt` JSONL 追加（`{"t":epochMs,"l":"I","tag":"...","m":"..."}`，
  消息截断 4000 字符），超过 **4MB 触发重写**保留最新条目（磁盘上限 5MB）；
- 崩溃兜底：`eCrash` 同步直写（UncaughtExceptionHandler 内，不走缓冲路径）；
- 文件损坏行容错：逐行解析失败跳过，不丢整文件。

### 4. 上传策略（决策点 D6）：自动定期 + 手动按钮

- **自动**：WorkManager `PeriodicWorkRequest`（`xpc_log_upload_periodic`，6 小时，
  `KEEP` 策略，初始延迟 10 分钟）；未绑定 Web 账号时跳过；
- **手动**：日志页「上传云端」按钮（未绑定置灰）；
- **增量**：`lastTs`（SharedPreferences）记录上次上传到的客户端时间戳，
  每次取 `ts > lastTs` 的条目按 500 条/批循环上传，全部成功才推进 lastTs
  （失败下次重试，日志丢失可接受——非审计数据）；
- 客户端标识：`${Build.MODEL}/${Build.VERSION.RELEASE}` 截断 64 字符。

### 5. Web 保留 7 天 + 服务端字段防篡改

- `app_logs` 表：Id / AccountId / Level / Tag / Message / Client / CreatedAt（客户端时间，
  仅展示）/ **ReceivedAt（服务端时间，保留依据）**；
- 保留按 **ReceivedAt**（非客户端时间）——客户端时间可篡改，无法伪造服务端收件时间；
- 过期清理内联执行（上传/查询时 `RemoveRange ReceivedAt < now-7d`），无后台任务；
- 入库防线：级别白名单归一化（D/debug→debug、W/warn/warning→warn、E/error/fatal→error、
  其余→info）；Message 截断 1000 / Tag 64 / Client 64；客户端时间钳制
  [2020-01-01, now+1d] 否则取服务器时间；单批 ≤500 条；空条目跳过。

### 6. Web 页面：单组件双路由

- 新增 `views/logs/LogsPage.vue` 同时服务 `/logs`（家长，菜单「运行日志」）与
  `/admin/logs`（admin，菜单「账号日志」），差异仅账号筛选列/筛选器是否渲染
  （`auth.isAdmin` 驱动）；
- 筛选：级别（debug/info/warn/error）、时间范围（from/to，按 CreatedAt）、admin 账号选择
  （选项来自 `/admin/accounts`）；分页 limit 默认 50（服务端钳制 1-1000）+ offset；
- 页面明示「服务端保留最近 7 天 · 内容已脱敏」。

### 7. 安全边界

- 端点 `[Authorize(Policy = "ParentOrAdmin")]`，UserId 为空 → 401；
- 限流：`logs:{userId}:{ip}` 30 次/小时（测试回环地址自动放行）；
- 上传动作写审计 `logs.upload`（仅计数，不含日志内容——审计不含敏感面）；
- 日志内容永不写服务端 Serilog 日志（避免明文二次落盘）。

### 8. 测试覆盖

- Android `AppLogTest`（8 项）：脱敏（赋值/验证码/JWT/hex64/正常文本）、环形缓冲上限、
  文件持久化+脱敏落盘、损坏行容错、清空；
- Web `AppLogSanitizerTests`（7 项）：与 Android 同口径脱敏 + 截断；
- Web `LogsControllerTests`（7 项）：上传绑定账号、空批/超批拒绝、入库脱敏、级别归一化、
  家长只见本账号、admin 全量+筛选、7 天清理。

### 9. 顺带修复（交付阻塞项，本需求内一并修）

- **服务端编译修复**：Program.cs 顶层语句中 `Services.AnnouncementCompensationService`
  无法解析兄弟命名空间前缀（需求 2 遗留，服务端自那后无法编译）→ 全限定
  `XiaopacaiWeb.Services.AnnouncementCompensationService`；
- **解绑测试兼容**：DevicesController.Unpair 的 `ExecuteDeleteAsync` 不被 EF InMemory
  提供程序支持（生产 SQLite 支持）→ 改为 load + `RemoveRange`；
- **测试语义对齐**：DeviceAccessTests 两条旧测试断言软解绑（清 OwnerUserId 留行）→
  按 A12（ADR 0010）硬删除语义重写。
