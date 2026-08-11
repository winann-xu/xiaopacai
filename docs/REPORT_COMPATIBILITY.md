# 小趴菜 Android 兼容性评估报告

版本：V1.0    日期：2026-08-11    评估：Codex@50.20    覆盖：Android 8.0~15 + 国产厂商 ROM

## 1. 当前配置

- minSdk 26（Android 8.0）/ compileSdk 34 / targetSdk 34。
- 核心能力依赖：前台服务（specialUse）、无障碍服务、设备管理器、UsageStats 权限、通知（13+ 运行时）、开机自启、SQLCipher（net.sqlcipher）+ Conscrypt/BouncyCastle TLS。

## 2. 版本适配矩阵

| Android 版本 | API | 前台服务 | 无障碍 | 通知 | 自启 | 结论 |
|-------------|-----|---------|--------|------|------|------|
| 8.0~8.1 | 26~27 | ✅ | ✅ | 渠道机制 | ✅ | 完全支持 |
| 9 | 28 | ✅ | ✅ | ✅ | ✅ | 支持 |
| 10 | 29 | ✅ | ✅ | ✅ | ✅ | 支持 |
| 11 | 30 | ✅ | ✅ | ✅ | ✅ | 支持（包可见性需 QUERY_ALL_PACKAGES，已声明） |
| 12 | 31 | ✅（后台启动受限，前台服务启动需引导） | ✅ | ✅ | 厂商限制 | 基本支持，需自启引导 |
| 12L/13 | 32/33 | ✅ | ✅ | 需 POST_NOTIFICATIONS 运行时申请 | 厂商限制 | 支持，通知权限需引导 |
| 14 | 34 | ✅（specialUse 声明，targetSdk 34 合规） | ✅ | ✅ | 厂商限制 | 支持 |
| 15 | 35 | ⚠️ 需回归验证（targetSdk 尚未升级至 35） | ✅ | ✅ | 厂商限制 | 兼容性良好，建议升级 targetSdk 后回归 |

## 3. 国产厂商 ROM 风险矩阵

| 厂商 | 系统 | 自启动限制 | 电池优化 | 无障碍保活 | 建议 |
|------|------|-----------|---------|-----------|------|
| 华为/荣耀 | HarmonyOS/EMUI/MagicOS | 严格（后台管理白名单） | 严格 | 中 | 引导页跳转自启动设置 + 电池白名单 |
| 小米 | MIUI/HyperOS | 严格 | 严格 | 中 | 引导页 + 关闭省电策略 |
| OPPO | ColorOS | 严格 | 严格 | 中 | 引导页 + 允许自动启动 |
| vivo | OriginOS | 严格 | 严格 | 中 | 引导页 + 后台高耗电白名单 |
| 三星 | One UI | 一般 | 一般 | 高 | 基本可用 |
| 原生 | AOSP | 无限制 | 一般 | 高 | 最佳 |

共性风险：厂商后台管理会杀前台服务；已通过 `AntiBypassService` 检测电池优化 + 通知告警；需补充各厂商"自启动设置页"跳转引导（需求 6）。

## 4. 专项风险

1. **前台服务启动限制（Android 12+）**：后台不能直接 startForegroundService；当前由开机广播/用户操作启动，需确认 O+ 场景（12 上限制较严，建议 WORKMANAGER 兜底）。
2. **通知权限（13+）**：需运行时请求 POST_NOTIFICATIONS，当前 `PermissionGuideScreen` 已覆盖基础引导；确认各 ROM 引导文案。
3. **QUERY_ALL_PACKAGES**：Google Play 需提交用途声明；国内商店无碍。若未来上架 Google Play 需适配。
4. **TLS 兼容**：Android 端 TLSv1.3 优先 + 1.2 回退；BouncyCastle RSA 证书修复已通过双模拟器验证；真机（各 ROM 的 Conscrypt 版本）需真机抽样复验（列入验收清单）。
5. **SQLCipher 加载**：net.sqlcipher 在部分 ROM 需确认 so 库加载（x86_64/arm64 均打包）。
6. **Android 15（API 35）**：targetSdk 34 在 Android 15 可运行（兼容模式），建议下一步 targetSdk 35 并回归前台服务/通知/无障碍。

## 5. 建议与结论

- 当前版本在 Android 8.0~15 + 主流国产 ROM 上**可用**，核心功能不受阻。
- 真机验证清单（发布前必须）：华为/小米/OPPO/vivo 各一台（Android 12~14），重点验证自启、保活、无障碍、UsageStats 授权、通知。
- 建议升级 compileSdk/targetSdk 至 35 前先完成 12 项功能改造，避免双线并行回归成本。
- 已列入提示词包需求 6（自启引导页）与需求 11（本报告持续更新）。
