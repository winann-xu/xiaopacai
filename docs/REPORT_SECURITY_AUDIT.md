# 小趴菜安全审计报告

版本：V1.0    日期：2026-08-11    审计：Codex@50.20    范围：Android 2.0（儿童端+家长端）/ Web 3.0 / P2P 协议

## 1. 结论摘要

| 风险级别 | 数量 | 说明 |
|---------|------|------|
| 高 | 1 | Web 生产 JWT 密钥需强配置（当前为占位符） |
| 中 | 4 | 配对码无频率限制 / Web 备份恢复文件校验弱 / 日志含敏感信息 / 生产未强制 HTTPS |
| 低 | 5 | 详见下方清单 |

总体：核心链路（TLS 指纹、SQLCipher 密钥、JWT 校验、CORS、角色授权、数据备份开关）设计正确；未发现可直接利用的注入/越权漏洞。高/中风险修复建议见 §4。

## 2. Android 端

| 检查项 | 状态 | 说明 |
|-------|------|------|
| TLS 传输 | ✅ | P2P 使用 TLS 1.2/1.3 + 自签证书；首次配对 TOFU 记录指纹，后续连接强制比对（`PairingManager` 持久化到 SQLCipher 加密库） |
| 证书固定 | ✅ | `P2PConnectionService.verifyCertificateFingerprint` 非首次连接指纹不匹配即拒绝 |
| 数据库加密 | ✅ | SQLCipher + AndroidKeyStore（AES-256 主密钥，TEE/SE 保护）；KeyStore 不可用时回退为受保护加密存储（`DbPassphraseProvider`） |
| 备份泄露 | ✅ | `allowBackup=false` + `dataExtractionRules` 禁止备份 |
| 权限最小化 | ✅ | 仅声明必要权限（用量/无障碍/前台服务/设备管理/通知/网络）；`QUERY_ALL_PACKAGES` 有 `tools:ignore` 注释（国内商店合规，Google Play 需声明用途） |
| 调试组件 | ✅ | `DebugTriggerActivity` 仅在 debug 构建；release 无该组件 |
| 日志脱敏 | ⚠️ 中 | `Log.i("首次配对，证书指纹: ...")` 等日志含指纹（指纹非敏感凭据，可接受）；需确认不打印配对码/密码 |
| 无障碍服务滥用面 | ✅ | 无障碍仅用于超时拦截与紧急公告覆盖层，未执行可写系统操作 |
| 防绕过 | ✅ | `AntiBypassService` 每分钟检查无障碍/用量/设备管理器/电池优化，异常发通知；包变更监控 |
| 前台服务合规 | ✅ | `foregroundServiceType="specialUse"` 适配 Android 14+ |

## 3. Web 3.0 端

| 检查项 | 状态 | 说明 |
|-------|------|------|
| JWT 密钥 | ⚠️ 高 | `appsettings.json` 含默认密钥 `dev-secret-key-32chars-minimum!`；`appsettings.Production.json` 为 `CHANGE-ME-...` 占位符。生产必须注入强随机密钥，否则 token 可伪造 |
| JWT 校验 | ✅ | ValidateIssuer/Audience/Lifetime/SigningKey 全开；Access 60min / Refresh 7d |
| 密码哈希 | ✅ | PBKDF2（`PasswordHasher`，带随机盐） |
| 角色授权 | ✅ | `AdminOnly` / `ParentOrAdmin` 策略覆盖全部控制器（含新增 devices/policies/announcements/reports/settings/admin） |
| SQL 注入 | ✅ | EF Core 参数化查询；原生 SQL 仅用于建表检查（常量） |
| CORS | ✅ | 仅 `localhost:5173` / `127.0.0.1:5173`，生产需收紧为正式域名 |
| Swagger | ✅ | 仅 Development 环境启用 |
| 文件上传 | ⚠️ 中 | 备份恢复接口解析 JSON 前未限制文件大小/类型；建议限制 10MB 且仅接受 .json |
| 输入校验 | ⚠️ 低 | 大部分 DTO 有 Required/MinLength；建议统一 `ModelState` 校验输出 |
| 审计日志 | ✅ | 登录/策略/公告/账号/数据操作均写 `AuditLogs`；管理端可查询/导出 |
| 错误信息泄露 | ⚠️ 低 | 默认 `error` 返回含业务描述，未泄露堆栈；生产需关闭详细异常 |
| HTTPS | ⚠️ 中 | 默认 HTTP 绑定 127.0.0.1；公网部署必须启用 HTTPS（或前置反代 TLS） |

## 4. P2P 协议

| 检查项 | 状态 | 说明 |
|-------|------|------|
| 帧长度上限 | ✅ | 1MB 上限，防内存耗尽 |
| 配对码校验 | ✅ | 服务端校验 6 位码 + 有效期 5 分钟 + 状态流转 |
| 配对码暴力尝试 | ⚠️ 中 | 无频率限制；建议按 IP/设备限速（5 次/分钟）并记录审计 |
| 重放防护 | ⚠️ 低 | 消息无序号校验；P2P 帧含 ts/seq，可加时间窗校验（低风险场景可缓） |
| 证书指纹一致性 | ✅ | Web 端按 TLS peer 证书计算指纹并与 DB 比对；儿童端二次连接强制指纹 |

## 5. 修复建议（按优先级）

1. 【高】Web 生产环境 JWT 密钥注入：部署脚本生成 32+ 随机字节密钥，写入环境变量/密钥管理，禁止使用默认值（启动时校验并拒绝弱密钥）。
2. 【中】配对码接口限速：`/api/devices/pairing-code`、`/api/pairing/generate-code` 与 `verify` 增加 IP 级限速（5 次/分钟），失败计数写入审计。
3. 【中】备份恢复文件校验：限制文件大小（≤10MB）、仅接受 `.json` 扩展名、校验 JSON 根结构后再导入。
4. 【中】日志脱敏确认：扫描 `Log.` 调用，确保不打印密码、配对码明文；Web `ILogger` 参数化日志已满足。
5. 【低】生产强制 HTTPS：`appsettings.Production` 增加 `Urls: https://...` + 证书配置或反代说明。
6. 【低】输入校验统一：为新增控制器 DTO 补充 `[StringLength]`/正则约束并返回统一错误格式。
7. 【低】P2P 时间窗校验：消息 `ts` 与服务器时间差 > 5 分钟丢弃（可选增强）。

## 6. 已确认无风险项
- EF Core 全参数化，无拼接 SQL。
- 所有新增控制器均带角色策略，无匿名越权端点。
- Android 备份禁用；SQLCipher 密钥不落明文。
- Swagger 不进入生产。
