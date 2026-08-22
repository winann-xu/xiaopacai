# ADR 0017 — App 自动更新机制（TASK-APP-UPDATE-V1）

- 日期：2026-08-22
- 状态：已裁决（产品负责人拍板 D1–D6，见 PROMPT_APP_UPDATE_V1.md 第九节）
- 范围：xiaopacai-web（server + web 前端）+ xiaopacai（android）；Windows 家长端本期仅预留接口文档

## 背景

小趴菜为自托管 APK 分发（非 Google Play），无内置更新机制：新版本靠下载中心手动下载安装，
真机缺陷修复（P2P/倒计时/DB 竞态）无法快速触达用户。需建立「更新清单 → 检查 → 下载 → 校验 → 安装」闭环。

## 决策

### D1 强制更新判定：minVersionCode 阈值（强制/可选一体）

- 服务端清单下发 `minVersionCode`；客户端 `versionCode < minVersionCode` → 强制更新，否则可选。
- 强制更新不可跳过（家长端进入前拦截提示；儿童端不阻塞守护）；可选更新可「以后再说」「跳过此版本」。
- 首次发布（v1.2.0）设 minVersionCode = 10200，让 1.1.x（含 145–148 热修复前版本）全量走强制更新，
  一次性收敛 P2P TLS / 倒计时 / DB 竞态 / socket 泄漏缺陷面。

### D2 推送范围：全部在线设备 + 离线补检

- admin 发布时经既有公告/P2P 通道广播 `update_available`（仅 updateId/versionCode/versionName 摘要）；
- 离线设备在启动检查 / P2P 重连时补检；
- 定向推送（按账号/设备）本期仅字段预留，不实现。

### D3 自动下载策略：默认关闭，允许移动网络

- 设置项「WiFi 下自动下载更新」默认关闭（产品已拍板允许移动网络下载，故开关文案与判定不做仅 WiFi 限制，
  开关仅控制「是否自动下载可选更新」）；
- 开启后：可选更新在后台自动下载，完成后弹一次安装确认；强制更新不受该开关影响。
- 平台边界：Android 非系统应用无法真静默安装，任何路径最终都是一次系统确认（PackageInstaller / 未知来源引导）。

### D4 更新通道（stable/beta）：本期不做，`channel` 字段预留（默认 stable）。

### D5 Windows 家长端：本期不改实现，仅输出接口预留说明（检查接口可用 + 下载中心 zip + 安装方式建议）。

### D6 弹窗频控

- 可选更新：按「versionCode + 日期」每日最多一次（本地持久化去重）；
- 强制更新：每次进入家长端按规则提示，不做日频控；
- 推送触达生成通知，点击进入更新对话框，不重复轰炸（通知与弹窗去重）。

## 安全红线（不可违背）

1. **数据保留是硬红线**：升级必须保留 SharedPreferences（JWT/邮箱/角色/服务器地址）、设备绑定与配对、
   策略/公告/分类/使用记录/报告缓存、KeyStore 密钥与 P2P 客户端证书、加密 DB 文件。
   任何升级路径不得触发本地清库；`MY_PACKAGE_REPLACED` 现有行为仅做权限自检，复核确认不清理业务数据。
2. 更新清单与 APK 下载仅 HTTPS；安装前必须 SHA-256 校验（失败拒绝安装，可重试）；
3. 禁止降级：versionCode 单调递增；客户端对 `latestVersionCode <= 当前 versionCode` 一律视为无更新；
4. 下载中心沿用既有白名单中间件（DownloadCenterGuardMiddleware）；APK 文件名带版本+ABI；
5. admin 发布/推送动作写审计（复用 AuditMiddleware / AuditAsync 模式）；仅 AdminOnly 策略可发布；
6. 公开检查接口 IP 级限频（复用 RequestRateLimiter），响应仅含更新所需字段，不泄露敏感配置。

## 守护不中断保证

- 更新流程（检查/下载/弹窗/安装引导）不解除超时拦截、无障碍或前台守护；
- 安装完成进程重启后守护自启恢复（沿用 START_STICKY/自启机制，V1.1.1 已加固）。

## 技术方案要点

- **服务端**：新增 `AppUpdate` 实体（platform/versionName/versionCode/minVersionCode/
  abiUrls/abiSha256/sizeBytes/changelog/status/publishedAt/createdBy/createdAt/channel 预留），
  沿用 EnsureCreated + 手工补表模式；`GET /api/update/check` 公开接口；`POST /api/admin/updates` 发布推送；
  上传时服务端计算 SHA-256 入库；APK 存 wwwroot/downloads/，文件名 `XiaopacaiParent-{version}-{abi}.apk`。
- **Android**：UpdateManager 单例（检查/下载/校验/安装）；下载到应用私有目录（app-updates/）；
  PackageInstaller session 为主、FileProvider+ACTION_VIEW 兜底；REQUEST_INSTALL_PACKAGES 引导；
  新增更新通知通道（与公告通道并列）；「关于」页新增「更新软件」入口，检查失败兜底打开下载中心。
- **验收边界如实说明**：文档明示「后台自动更新」=「预下载 + 安装时一次系统确认」，非真静默。

## 遗留/待后续

- 定向推送（账号/设备级）字段预留，下期实现。
- 更新通道 stable/beta 下期实现。
- 服务端「重新配对」引导/审计（145 信可选建议）记入 backlog，排期在 UPDATE-V1 之后。
- Windows 家长端自动更新（zip 自更新）下期实现。
