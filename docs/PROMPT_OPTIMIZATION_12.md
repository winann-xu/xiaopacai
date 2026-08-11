# 小趴菜 12 项优化升级提示词包

版本：V1.0    日期：2026-08-11    编制：Codex@50.20（主测试）    依据：用户逐条需求 + 现有 2.0/3.0 代码现状
协作：Claude@50.53（主开发） / Codex@50.20（主测试、集成、验证、报告）

> 说明：本提示词包覆盖 Android 儿童端、Android 家长端、Web 3.0 三端。
> 所有修改不得破坏已验收链路：TLS P2P 握手、配对码、策略下发、时长上报、超时锁定、公告推送、设备去重、双角色登录。
> 每项完成必须附带可执行验收步骤；测试由 Codex 在 Windows 侧执行（dotnet test / gradlew / 双模拟器 E2E）。

---

## 需求 1：应用分类可在小趴菜内设置

### 目标
儿童端已安装应用（游戏/社交/视频/学习/其他）的分类可由家长在小趴菜内查看和修改，不再依赖纯硬编码关键词。

### 现状
- `android/app/src/main/java/com/xiaopacai/child/service/UsageStatsCollector.kt` 中 `CATEGORY_RULES` 基于包名/应用名关键词硬编码分类。
- 分类值为 game/social/video/study/other；Web 端使用 learning，两端口径不一致。
- 家长端、Web 端均无应用分类管理界面。

### 方案
1. 儿童端新增 `app_category` 表（SQLCipher）：
   - 字段：`package_name`、`app_name`、`category`（game/social/video/learning/other）、`source`（default/manual）、`updated_at`。
   - 首次启动扫描已安装应用，按现有关键词规则生成默认分类并落库；无匹配归 other。
2. 儿童端新增"应用分类"设置页：列出已安装应用 + 当前分类，支持单条/批量修改（manual 覆盖 default）。
3. 协议扩展：`policy_update` 消息新增 `app_categories` 字段（JSON 数组：`{packageName, appName, category}`），家长端/Web 端可随策略下发；儿童端收到后合并进 `app_category` 表（manual 覆盖优先）。
4. 统一分类口径：所有端统一使用 game/social/video/learning/other；采集器将旧 study 映射为 learning。
5. Web 3.0 新增接口：`GET /api/devices/{id}/app-categories`、`PUT /api/devices/{id}/app-categories`（保存后随 policy_push 下发）。
6. Android 家长端：设备详情/策略页增加"应用分类"入口，读取儿童端上报的应用列表并修改。

### 验收
- 儿童端修改某应用分类 → 家长端/Web 端下发策略后，儿童端分类保持用户设置。
- 时长上报中分类使用用户设置值。
- Web 端分类管理与下发闭环可用。

---

## 需求 2：二维码扫码配对（IP 配对保留为可选项）

### 目标
不懂技术的家长可扫码完成配对；IP 手动配对保留但降为高级选项。

### 现状
- Windows 家长端有 `QRCodeService`（QRCoder 生成二维码）。
- Android 家长端仅有配对码展示，儿童端仅有手动 IP 输入，两端均无二维码扫码能力。

### 方案
1. Android 家长端：`ParentP2PListenerService` 生成配对信息 JSON（type/deviceId/port/fingerprint/pairingCode/ips/version/timestamp），用 ZXing 生成二维码，在配对页大图展示（加暗色底、高对比、可刷新）。
2. Android 儿童端：新增扫码配对页（ZXing + CameraX 实现相机扫码），解析 JSON 后自动选择可达 IP 发起 TLS 配对；解析失败或不可达时提示手动输入。
3. Web 3.0：部署公网时可生成"连接 Web 服务"二维码（内容为 `{type:"web_relay", host, port, pairingCode}`），儿童端扫码直连 Web。
4. 儿童端配对页 UI：默认"扫一扫"大按钮；"手动 IP 配对"折叠为高级选项。
5. 相机权限：AndroidManifest 增加 CAMERA 权限，运行时请求。

### 验收
- 家长端展示二维码 → 儿童端扫码 → 自动配对成功（TLS 指纹记录、配对码校验）。
- 扫码失败场景给出明确错误提示并可回退手动 IP。
- 扫码配对与手动配对结果一致（策略下发可用）。

---

## 需求 3：跨网络连接（蜂窝网络保持连接）

### 目标
儿童端与家长端不再限定局域网；家长在蜂窝网络下仍可监控、下发策略、收报告。

### 现状
- 儿童端 P2P 直连家长端 TCP（局域网）。
- Web 3.0 已有公网可部署的 P2P TCP/TLS 监听（9527），儿童端已可直连 Web（已验证）。
- Android 家长端尚无连接 Web 中继的能力。

### 方案（Web 3.0 云端中继，优先级最高、改动最小）
1. Web 3.0 作为中继节点（已有 P2P 监听 + 会话表）：
   - 儿童端：`P2PConnectionService` 支持"中继模式"——连接 Web 服务地址（可配），握手携带 `relay=true` 与配对码，注册到 Web 会话表（现有逻辑）。
   - Android 家长端：新增"云端中继"连接入口，用家长账号登录 Web 后连接 Web 9527，握手携带家长身份（deviceId + relay=true）。
   - Web 端 `P2pMessageHandler` 增加中继路由：按配对关系（Devices 表 DeviceId ↔ 家长账号）转发 usage_report / policy_update / announcement_push / 指令消息。
2. 连接策略：优先局域网直连；直连失败或家长勾选"云端监控"时自动走中继；断线自动重连（现有重连机制复用）。
3. Web 端新增中继会话 API：`GET /api/relay/sessions`（管理端查看在线中继设备）。
4. 安全：中继链路仍走 TLS + 配对码 + 证书指纹；家长账号与设备绑定（Devices 表新增 `owner_user_id` 可空字段，配对确认时绑定）。

### 验收
- 儿童端 WiFi、家长端蜂窝网络，两端同时连 Web 中继：策略下发、时长上报、公告推送、超时锁定全链路可用。
- 家长端退出局域网后自动切中继，业务不中断。

---

## 需求 4：公告直接显示，紧急通知前置置顶

### 目标
公告到达儿童端直接展示，不需要儿童主动查看；紧急公告全屏置顶并要求点击确认。

### 现状
- 儿童端公告仅落库，UI 以"有更新"角标提示，需儿童主动进入查看。

### 方案
1. 普通公告：
   - 儿童端收到 `announcement_push` 后立即弹出应用内 Dialog（标题+内容+关闭按钮），同时发系统通知。
   - 不再依赖"更新角标 + 主动查看"。
2. 紧急公告（priority=urgent）：
   - 新增 `AnnouncementOverlayActivity`（全屏、`showWhenLocked`、`turnScreenOn`、`singleInstance`、`excludeFromRecents`），无论前台是游戏还是视频均覆盖显示。
   - 内容包含"我知道了"确认按钮；确认后记录回执时间并上报家长端（新消息类型 `announcement_ack`）。
   - 防绕过：覆盖层使用 TYPE_APPLICATION_OVERLAY 全屏，配合无障碍服务检测 HOME/返回键点击，未确认前不允许关闭。
3. 公告模型扩展：儿童端 `announcements` 表增加 `priority`、`requires_ack`、`acknowledged_at`。
4. 协议扩展：`announcement_push` payload 增加 `requires_ack` 字段；新增 `announcement_ack` 上行消息。

### 验收
- 家长端/Web 发布普通公告 → 儿童端立即弹窗显示。
- 发布紧急公告 → 任意前台应用下全屏置顶，未确认不可关闭；确认后家长端收到回执。

---

## 需求 5：故障诊断信息上报 + Web 3.0 诊断收集模块

### 目标
儿童端定期向后台发送故障/诊断信息，Web 3.0 提供收集与展示模块，为后续升级提供数据。

### 现状
无诊断上报能力。

### 方案
1. Android 儿童端新增 `DiagnosticsCollector`：
   - 采集项：应用版本、Android 版本/API、设备型号/厂商、权限状态（无障碍/用量/设备管理器/通知/电池优化）、守护服务与无障碍服务运行状态、最近崩溃堆栈（记录最近 5 条）、P2P 连接历史（成功/失败/重连次数）、数据库大小、网络状态（WiFi/蜂窝/无）。
   - 上报时机：每天一次（默认开启，家长可在设置关闭）；发生异常时立即补报；设置页提供"立即上报"。
   - 上报通道：复用 P2P 链路新增 `diagnostics_report` 消息（本地缓存未上报，重连补传）。
2. Web 3.0：
   - 新增 `diagnostics` 表与 `POST /api/diagnostics`（儿童端上报）、`GET /api/admin/diagnostics`（列表/筛选/详情）、`GET /api/admin/diagnostics/export`（导出）。
   - 管理后端新增"故障诊断"页面：按设备/时间查看、统计常见异常、崩溃摘要。
3. 隐私：上报前在儿童端设置页明示内容，家长可关闭。

### 验收
- 儿童端触发"立即上报" → Web 管理端可见完整诊断记录。
- 断网期间上报数据缓存并在恢复后补传。

---

## 需求 6：防卸载 / 防退出 / 防重启不启动 完善

### 目标
儿童无法轻易卸载、退出守护或通过重启绕过监控。

### 现状（已有基础）
- `GuardianDeviceAdminReceiver` 设备管理器（防卸载核心）。
- `AntiBypassService`：每分钟检查无障碍/用量/设备管理器/电池优化，异常发通知；监控包变更。
- `BootReceiver` 开机自启；前台服务常驻；无障碍服务拦截。

### 方案
1. 防卸载增强：
   - 设备管理器激活引导（首次设置强制激活，未激活给出明确提示与跳转）。
   - 检测到"应用信息页"打开时记录并通知家长；检测到 `PACKAGE_REMOVED` 事件时若设备管理器仍激活则阻止（系统层能力），否则告警。
   - 家长端提供"守护状态"页：显示设备管理器/无障碍/用量/自启是否就绪，一键引导修复。
2. 防退出：
   - 无障碍服务拦截 HOME/返回键在锁定窗口的行为（与紧急公告 overlay 联动）。
   - 前台服务通知不可划除（现有），增加"服务异常自动重启"（双守护：WorkManager 周期自检 + AlarmManager 兜底）。
3. 防重启不启动：
   - BootReceiver 完善：开机延迟 10s 后启动守护 + 自检。
   - 首次设置增加"后台运行/自启动"引导页：针对华为/小米/OPPO/vivo/荣耀 的电池优化与自启动设置页跳转（按厂商包名跳转）。
4. 防卸载开关：设置页提供"守护锁定"开关，开启后进入需验证家长密码；卸载需先通过家长验证关闭守护。

### 验收
- 新安装模拟器流程：设备管理器 + 无障碍 + 用量 + 自启全部引导就绪。
- 重启模拟器后守护服务自动恢复（BootReceiver 验证）。
- 家长端守护状态页显示各就绪项。

---

## 需求 7：部分 APP 停用（partial_lock）实现与重点测试

### 目标
超时后只停用部分 APP（黑名单/非白名单），白名单与学习类应用可用；此功能为重点测试项。

### 现状
- 策略模型已支持 `OvertimeAction = partial_lock`，但儿童端超时逻辑固定 `_stopMode = "full"`（`UsageStatsCollector.checkTimeoutStatus`）。
- partial 模式仅有调试反射入口，无产品链路。

### 方案
1. 儿童端：
   - `UsageStatsCollector.checkTimeoutStatus`：超时后按策略 `OvertimeAction` 设置 `_stopMode`（full_lock → full；partial_lock → partial；warn_only → warn）。
   - `TimeoutExecutor` 支持 partial：非白名单且非学习类应用启动时弹覆盖层拦截；白名单/学习类放行；拦截对象可配置（黑名单优先，其次非白名单）。
   - 策略缓存解析扩展：daily_limit / category_limit / whitelist / blacklist 联合判定。
2. 协议：`policy_update` 的 daily_limit 项增加 `restrictMode`（full/partial/warn）字段，与 Web 端 `OvertimeAction` 对齐。
3. Web/家长端：策略页"超时处理方式"选择"部分 APP 停用"时，显示黑名单/白名单配置说明。
4. 测试脚本（Codex 双模拟器 E2E）：
   - 设置 partial_lock + 白名单（学习类）+ 黑名单（游戏）→ 超时后游戏被拦、学习可用、桌面可用。
   - full_lock 回归不破坏。

### 验收
- partial_lock 全链路：下发 → 超时 → 部分拦截 → 白名单可用。
- 测试结果记录到回归清单。

---

## 需求 8：运行负载与耗能评估（轻量化）

### 目标
评估整体运行负载与耗电，输出优化建议并落地低风险优化，保持轻量定位。

### 现状（待审查点）
- 时长采集：每 5 分钟一次 UsageStatsManager 查询 + SQLCipher 批量写入。
- 心跳：每 30 秒一次 P2P 消息。
- AntiBypass：每分钟检查 PackageManager/Settings。
- 前台服务常驻 + 无障碍服务常驻。

### 方案
1. 审查并量化：各模块唤醒频率、数据库写入量、网络流量、内存占用（模拟器 + 真机采样）。
2. 落地优化（低风险优先）：
   - 心跳动态化：连接稳定后 30s→60s，丢包时回退 15s。
   - AntiBypass 检查合并：60s→120s，且仅在有变化时发通知。
   - 采集写入合并：同一应用同日多次 upsert 合并为一次。
   - 数据库连接复用与批量事务（已有 batch，检查连接释放）。
   - 电量优化：采集窗口与 Doze 对齐（WorkManager 替代部分定时轮询）。
3. 输出《性能与耗能评估报告》：指标、对比、优化前后、结论。

### 验收
- 报告给出优化前后关键指标（唤醒次数/小时、DB 写入/天、流量 KB/天、内存 MB）。
- 低风险优化已合入且回归通过。

---

## 需求 9：安全与漏洞检查

### 目标
对现有代码做安全审查，输出完整报告并修复发现的问题。

### 范围
- Android：权限最小化、TLS 证书固定（首次配对后指纹校验是否强制）、SQLCipher 密钥管理、日志脱敏（不打印配对码/密码）、导出组件（debug trigger 仅 debug）、无障碍服务滥用面、设备管理器策略、应用备份/数据提取规则。
- Web：JWT 密钥与过期策略、输入校验/SQL 注入（EF 参数化审计）、越权（角色策略覆盖所有端点）、文件上传（备份恢复）校验、审计日志完整性、Swagger 生产暴露、CORS 收紧、错误信息泄露。
- 协议：P2P 消息校验、帧长度上限（已有 1MB）、配对码暴力尝试（限速）、重放防护。

### 交付
- 《安全审计报告》：风险清单（高中低）、位置、利用条件、修复建议。
- 高/中风险问题修复并回归。

---

## 需求 10：Web 端扫码登录（家长 APP 授权，免注册）

### 目标
家长用已登录的小趴菜 APP 扫码即可登录 Web 3.0，无需注册新账号。

### 方案
1. Web 后端：
   - `POST /api/auth/login-ticket`（未登录可调）→ 生成一次性 ticket（UUID，有效期 90s，状态 pending）。
   - `GET /api/auth/login-ticket/{ticket}`（轮询，状态：pending/confirmed/expired）。
   - `POST /api/auth/login-ticket/{ticket}/confirm`（APP 家长端已登录调用，校验登录态后确认并绑定用户）。
   - 确认后首次轮询返回该用户的 JWT（access/refresh），前端自动登录并跳转。
2. Web 前端：登录页增加"扫码登录"Tab，展示二维码（内容为 ticket URL），前端 2s 轮询状态，确认后自动登录。
3. Android 家长端：设置/主页新增"扫码登录 Web"入口，调用确认接口（复用已有家长登录态）。
4. 安全：ticket 一次性、短时效、确认接口要求家长登录态 + 设备绑定（可选）。

### 验收
- Web 未登录 → 显示二维码 → 家长 APP 扫码确认 → Web 自动登录成功。
- ticket 过期/重复使用被拒绝。

---

## 需求 11：Android 兼容性评估报告

### 目标
输出覆盖 Android 各版本与国产厂商 ROM 的适配性评估报告。

### 内容
- 覆盖范围：Android 8.0/8.1（API 26/27）~ Android 15（API 35）；targetSdk 当前值评估。
- 重点项：
  - 前台服务类型（Android 14+ specialUse 声明与权限）、后台启动限制（12+）。
  - 无障碍服务在各厂商 ROM 的保活与兼容（华为/小米/OPPO/vivo/荣耀）。
  - 自启动限制（厂商后台管理）与引导跳转路径。
  - 通知权限（13+）、POST_NOTIFICATIONS 运行时申请。
  - QUERY_ALL_PACKAGES 政策合规（Google Play 与国内商店差异）。
  - UsageStats 权限在厂商 ROM 的授权差异。
  - SQLCipher/Conscrypt TLS 在各版本行为（已有 BouncyCastle 修复）。
- 输出：《Android 兼容性评估报告》，含各版本/厂商风险矩阵与适配建议、需真机验证清单。

---

## 需求 12：家长端忘记密码重点设计

### 目标
家长忘记密码时有可靠、易用的找回/重置通道。

### 方案（三层通道）
1. APP 家长端（本机已登录状态记忆）：
   - 设置页"修改/重置密码"：验证当前登录态（可要求再次输入登录密码或使用屏幕锁）后直接设置新密码，并吊销其他设备 token。
2. Web 端 + 已登录家长 APP 扫码授权重置：
   - 登录页"忘记密码" → 生成 reset-ticket（有效期 10 分钟）→ 显示二维码 → 家长用已登录 APP 扫码确认身份 → 网页设置新密码。
   - Web 后端：`POST /api/auth/reset-ticket`、`GET/POST /api/auth/reset-ticket/{ticket}/confirm`、`POST /api/auth/reset-ticket/{ticket}/reset`。
3. 离线恢复码（重点设计）：
   - 首次设置家长账号时生成 8 位恢复码（仅显示一次，可截图/打印保存），存储为 PBKDF2 哈希。
   - 忘记密码时输入恢复码即可重置（Web 与 APP 均支持）。
4. 管理员兜底：管理端账号重置（已有 `POST /api/admin/accounts/{id}/reset-password`）。
5. 安全：重置后吊销全部 refresh token；记录审计日志；失败限速（5 次/小时）。

### 验收
- 三条通道端到端可用；恢复码错误 5 次后锁定；重置后旧 token 全部失效。

---

## 实施计划

| 阶段 | 内容 | 负责 |
|------|------|------|
| P1 | 协议与数据模型扩展（app_category / diagnostics / partial restrictMode / announcement requires_ack / login-ticket / reset-ticket / 中继） | Claude 开发，Codex 评审协议 |
| P2 | Android 儿童端（分类页、公告弹窗与紧急 overlay、诊断采集、防卸载增强、partial 链路） | Claude 开发 |
| P3 | Android 家长端（二维码生成/扫码、中继连接、扫码登录授权、忘记密码、守护状态页） | Claude 开发 |
| P4 | Web 3.0（诊断模块、扫码登录、中继路由、分类 API、重置密码、管理端页面） | Claude 开发 |
| P5 | 测试与报告（partial E2E、性能报告、安全审计、兼容性评估、全量回归、打包更新） | Codex 主测 + Claude 修复 |

## 总体约束
- 模拟器以可见窗口运行；调试过程向用户实时同步。
- 所有代码中文注释；协议变更需更新 docs/ADR 与协议说明。
- 每阶段完成：commit + CHECKPOINT 更新 + 测试记录。
- 交付物更新到 `xiaopacai-tests/dist/`（APK/Windows/Web 构建产物）。
