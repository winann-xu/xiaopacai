# 小趴菜（xiaopacai）更新日志

本文档记录小趴菜亲子守护系统所有重要版本变更。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)。

---

## [2.0.9] - 2026-08-29（[TASK-V208-UNBIND-FIX]，修复 Web 解绑后策略仍下发/生效 + 家长紧急解除报错）

### 修复
- **Web 解绑后策略不再重新下发（服务端）**：匿名注册不再自动创建默认策略；未绑定（无归属）设备
  的 `policies` 接口返回空策略、心跳不再返回策略版本/公告签名/下行指令；绑定成功时（扫码/配对码）
  才补建默认策略（120 分钟 / 整机停用）。
- **Web 解绑后客户端不再自动匿名重注册**：新增「等待重绑」标记，解绑后后台不再注册新设备行，
  避免服务端重建同 deviceId 行并再次下发默认策略（生产实测解绑后 40 秒内重建行的根因）。
- **Web 解绑后本地策略/公告/限额偏移一并清除**：`handleDeviceUnbound` 清空 `policy_cache` 与公告表，
  设备立即解除管控（不再残留“今日限额 120 分钟”并继续锁定）。
- **解绑后家长紧急解除报错**：紧急解除对话框在未绑定时显示「账号邮箱 + 家长密码」，
  使用无状态云端验证（不写本地绑定），本地紧急解除照常生效，未绑定时跳过云端上报。
- **守护状态/应用分类入口（解绑后）**：家长验证对话框未绑定时允许邮箱+密码云端验证，
  验证结果不持久化，不再只能跳转浏览器注册引导。
- **自动升级误报新版本**：自动升级页改用渠道感知的 `/api/update/check`（abi + versionCode + channel），
  special 渠道不再把同版本号 stable 包误报为“新版本”。
- **解绑后旧版残留“已绑定”状态自愈**：等待重绑期间一律视为未绑定，兼容旧版本家长验证误写入的残留邮箱。
- **首页/守护状态限额展示**：未设置限额时如实显示“今日已用 X 分钟”，不再误显示“限额 1 分钟”。

### 单测
- Android 全量 239/239 通过（含 wait_rebind 新增用例）；Web 全量 343/343 通过
  （新增：匿名注册不建策略 / 未绑定不返回策略 / 未绑定心跳零版本 / 绑定补建默认策略）。

---

## [2.0.8] - 2026-08-28（[TASK-V2-RESET-FIX]，修复重置当日限额在 V2 儿童端不生效）

### 修复
- **消费 HTTP 心跳中的 `reset_daily_usage` 指令**：V2 儿童端移除 P2P 后，Web 端点击“重置当日限额”会挂起待重置指令，并在下一次心跳的 `commands` 中下发；此前客户端未处理该指令，导致重置链路断裂。现已在 `CloudSyncService` 中解析并执行重置。

---

## [2.0.7] — 2026-08-28（[TASK-ANN-3-TIERS]，公告三档弹窗 + 同步提速）

### 修复
- **公告三档优先级与弹窗规则修正**：儿童端统一按 `normal/important/urgent` 映射为 `0/1/2`，
  普通公告走应用内对话框、重要公告走带“重要”标识的对话框、紧急公告走全屏确认覆盖层；
  修复历史数据 `priority/requires_ack` 错位导致的“重要被当成紧急”问题。
- **公告同步策略**：Web 保存策略/发布公告后，真机由固定 5 分钟轮询改为 60 秒心跳按版本条件拉取，
  策略与公告约 1 分钟内到达设备；公告本地改为合并式更新并做撤回/删除对账。

### 单测
- Android 全量 238/238 通过（含新增三档优先级映射用例）

---

## [1.3.4] — 2026-08-24（[TASK-REBIND-GATE]，换绑必须先在原家长端解绑）

### 修复
- **儿童端换绑归属拦截（真机复现缺陷）**：儿童端已绑定家长后，扫码新家长二维码前先查询
  `GET /api/pairing/status` 确认设备当前绑定状态：
  - 已绑定 → 弹「无法重新绑定」，必须先由原家长端解绑（服务端解绑为硬删除，行消失后才可重绑）；
  - 未绑定 → 保持原有「检测到旧账号数据」确认流程（清空本地 + 重建设备身份）；
  - 未登录/网络异常/查询失败 → 同样拦截，杜绝绕过归属纪律。
- 新增 `BindingStatusChecker`（可注入客户端，9 例单测覆盖 bound/notBound/网络失败/未登录等分支）

### 配套服务端（xiaopacai-web v1.3.1）
- `GET /api/pairing/status` 绑定状态接口；P2P 握手跨账号配对码拒绝；`/api/pairing/verify` 归属防护

### 单测
- Android 全量 247/247 通过（含新增 9 例）

---

## [1.0.1] — 2026-08-10

### 新增
- **Android 单元测试**：新增 Robolectric/JUnit 测试覆盖 UsageRecordDao、PolicyConfig、TimeoutExecutor、ParentPasswordManager、DataSanitizer 五个核心类（[TASK-TEST-ANDROID]）
- **Android PolicyConfig 数据模型**：新增与 Windows 端兼容的 PolicyConfig Kotlin 数据类，支持 JSON 序列化/反序列化与策略验证
- **CHANGELOG.md**：新增项目更新日志
- **USER_MANUAL.md**：新增完整用户手册

### 修复
- **BUG-0810-08**：修复 Android 端 4 处 Kotlin 编译错误
  - `GuardianDeviceAdminReceiver`：`ACTION_DEVICE_ADMIN_DISABLED` / `ACTION_DEVICE_ADMIN_DISABLE_REQUESTED` 常量修正
  - `ReportGenerator`：`AppDatabase(context)` → `AppDatabase.getInstance(context, passphrase)` 单例修复
  - `BlockOverlayActivity`：移除不存在的 `ButtonDefaults.outlinedButtonBorder(true)` API 调用
  - `ParentPasswordManager`：补充 `AntiBypassService` 导入
- **BUG-0810-07**：策略键加入 `category` 维度，修复分类限额策略覆盖问题；新增 10 个 xunit 测试
- **BUG-0810-06**：`CryptoService` 补充 `using System.IO`；`ReportView` 补充 `AvgDailySubText` XAML 名称定义和 `using` 清理
- **BUG-0810-05**：`SavePolicy` SqliteCommand 参数复用冲突修复（`cmd.Parameters.Clear()`）
- **BUG-0810-04**：`P2PBroadcastService.SendAsync` `ReadOnlyMemory<byte>` 重载修复
- **BUG-0810-03**：Gradle Wrapper 补齐，Android APK 构建成功
- **BUG-0810-01**：Windows `app.ico` 图标修复

---

## [1.0.0] — 2026-08-10

### 新增（Day3 — 成品收尾）
- **D3-01 报告生成**：Android `ReportGenerator` + Windows `ReportView`/`ReportService`，支持日报/周报，TXT/JSON 导出
- **D3-02 数据安全**：Android KeyStore 集成 + Windows DPAPI + HMAC 完整性校验 + AES-GCM 加密 + `DataSanitizer` 数据清理
- **D3-03 防绕过/卸载保护**：`GuardianDeviceAdminReceiver` 设备管理器 + `AntiBypassService` 七向量检测 + `ParentPasswordManager` PBKDF2 密码管理
- **D3-04 UI 成品级打磨**：完整 Material3 品牌色系 + 动画过渡 + 深色主题支持 + Windows 品牌样式
- **D3-05 质量收尾**：`DbPassphraseProvider` 统一密码管理 + 6 文件批量修复
- **D3-06 打包**：版本号 1.0.0 + Git Bundle 生成

### 新增（Day2 — 核心闭环）
- **D2-01 时长统计**：`UsageStatsManager` 集成 + SQLCipher 加密数据库写入 + 5 分钟定时采集循环
- **D2-02 策略引擎**：五类策略（每日限额/就寝时段/分类限制/白名单/黑名单）+ WPF 策略配置 UI
- **D2-03 应用拦截**：`GuardianAccessibilityService` 无障碍服务 + `AppInterceptor` 拦截引擎 + `BlockOverlayActivity` 全屏覆盖
- **D2-04 公告系统**：Windows 创建推送 + Android 接收显示 + 优先级管理
- **D2-05 同步协议**：策略/公告/时长双向同步 + P2P 消息路由（`SyncManager`）
- **D2-06 超时停用**：`TimeoutExecutor` 主动封锁 + 事件记录 + 模式切换（full/partial/none）+ 解除恢复

### 新增（Day1 — 骨架与连接）
- **D1-01 仓库初始化**：README / LICENSE(MIT) / CONTRIBUTING / ADR / COLLAB_RULES / .gitignore
- **D1-02 Android 工程骨架**：Kotlin + Jetpack Compose + SQLCipher + Room + Gradle Wrapper
- **D1-03 Windows 家长端骨架**：WPF + .NET 8 + SQLCipher + MaterialDesign 主题
- **D1-04 P2P 连接 PoC**：TCP/TLS 协议规范 + 局域网发现(JmDNS) + 配对(QR 码) + 广播 + ADR-0002
- **D1-05 两端首版 UI**：Android 守护主页（剩余时长/公告/连接状态）+ Windows 仪表盘

---

## 技术栈

| 平台 | 语言 | 框架 | 数据库 | 通信 |
|------|------|------|--------|------|
| Android 儿童端 | Kotlin | Jetpack Compose | SQLCipher 4.5.4 | OkHttp + JmDNS |
| Windows 家长端 | C# (.NET 8) | WPF | SQLCipher (C#) | TCP/TLS + P2P |

## 版本号规则

- `x.y.z` 遵循语义化版本：主版本.次版本.修订号
- 主版本：架构重大变更或协议不兼容
- 次版本：新功能/模块
- 修订号：Bug 修复和文档更新
