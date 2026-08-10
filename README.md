# 🥬 小趴菜（儿童守护 · 家长监控软件）

> 开源免费 | 本地优先 | P2P 安全传输 | 数据不上云

**小趴菜** 是一款轻量级家长监控软件，帮助家长监督少年儿童使用手机/平板/电脑的时长。家长可调整使用限额、过滤不良内容、发布公告、生成周期使用报告。

## 🎯 核心功能

- **时长控制停用**：超时后整机停用或部分 APP 停用（受限守护模式）
- **应用白名单**：学习类应用白名单，超时后仅允许白名单应用
- **就寝时段**：夜间自动停用，保障儿童作息
- **公告推送**：家长实时发布公告到儿童设备
- **使用报告**：周期生成儿童使用时长与行为报告
- **P2P 直连**：家庭局域网内家长端与儿童端直连，数据不上云
- **数据安全**：两端 SQLCipher 加密存储，TLS 1.3 双向认证传输

## 🏗️ 架构概览

```
┌─────────────────────────┐     ┌─────────────────────────┐
│   家长端 (Windows)       │     │   儿童端 (Android)       │
│   WPF / .NET 8          │◄───►│   Kotlin / Jetpack       │
│   · 设备管理             │ P2P │   · 时长采集             │
│   · 策略配置             │ TLS │   · 超时停用             │
│   · 报告查看             │     │   · 公告展示             │
│   · 本地数据中心          │     │   · 加密存储             │
└─────────────────────────┘     └─────────────────────────┘
```

### 技术栈

| 端 | 语言/框架 | 数据库 | 网络 |
|----|----------|--------|------|
| 家长端 (Windows) | C# / .NET 8 + WPF | SQLite (SQLCipher) | mDNS + TCP/TLS 1.3 |
| 儿童端 (Android) | Kotlin + Jetpack Compose | SQLite (SQLCipher) | mDNS + TCP/TLS 1.3 |

## 📋 构建与运行

### 环境要求

- **家长端**：Windows 10/11 + .NET 8 SDK
- **儿童端**：JDK 17 + Android SDK 34 + Gradle 8.x
- **网络**：家庭局域网（支持 mDNS/DNS-SD）

### 构建家长端 (Windows)

```powershell
# 1. 安装 .NET 8 SDK (https://dotnet.microsoft.com/download/dotnet/8.0)
# 2. 克隆仓库
git clone <repo-url> && cd xiaopacai

# 3. 还原依赖并编译
cd windows/XiaopacaiParent
dotnet restore
dotnet build -c Release

# 4. 运行家长端
dotnet run -c Release
```

#### Windows 运行说明

**系统要求：**
- Windows 10 版本 19041+ (20H1) 或 Windows 11
- .NET 8 Desktop Runtime（不含 SDK 时需单独安装）
- 局域网环境（与儿童端设备处于同一子网）
- 推荐分辨率：1366×768 及以上

**首次运行：**
1. 启动后自动在 `%LocalAppData%/XiaopacaiParent/` 创建加密数据库
2. 数据库密码通过 Windows DPAPI（当前用户级别）保护，无需手动输入
3. 首次启动会创建默认策略模板（每日限额 2 小时 / 就寝 21:00-07:00 等）

**配对儿童端：**
1. 确保 Android 设备与 Windows 处于同一局域网
2. 在家长端点击「设备管理」→「添加设备」→ 生成配对码
3. 在儿童端输入配对码完成 TLS 双向认证
4. 配对成功后策略自动同步到儿童端

**数据存储位置：**
- 数据库：`%LocalAppData%/XiaopacaiParent/xiaopacai_parent.db` (SQLCipher 加密)
- 日志：`%LocalAppData%/XiaopacaiParent/logs/`
- 配置文件：`%LocalAppData%/XiaopacaiParent/config.json`

**常见问题：**
- 防火墙弹窗：请允许 `XiaopacaiParent.exe` 访问专用网络（端口 9527 TCP）
- mDNS 发现失败：检查 Windows 防火墙 → 允许 mDNS (UDP 5353)
- 数据库无法打开：删除 `%LocalAppData%/XiaopacaiParent/` 目录后重启（⚠️ 将丢失所有数据）
- 运行 `dotnet --version` 确认 .NET 版本 >= 8.0.0

### 构建儿童端 (Android)

```bash
# 设置 Android SDK 路径
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17

cd android
./gradlew assembleDebug
# APK 输出：android/app/build/outputs/apk/debug/app-debug.apk
```

## 🔒 安全与隐私

- ✅ 数据仅存终端设备，不上传任何云端
- ✅ 两端数据库 SQLCipher 加密
- ✅ P2P 通信使用 TLS 1.3 + 双向证书认证
- ✅ 一次性配对码 + 证书指纹防中间人
- ✅ 最小化数据采集，不记录明文密码
- ❌ 无账号体系、无云存储、无第三方 SDK

## ⚠️ 能力边界说明（重要）

本软件采用 **受限守护模式** 实现超时停用功能，通过前台识别 + 全屏守护界面 + 拦截非白名单应用的方式限制儿童使用设备。

**请注意**：第三方应用无法真正"硬锁定"安卓系统。停用功能依赖于 Android 系统提供的无障碍服务与使用情况访问权限，可能受以下因素影响：
- 系统版本差异（不同厂商 ROM 的后台限制策略不同）
- 儿童可能通过重启、安全模式等途径绕过
- 部分系统应用（电话、短信等紧急功能）始终可用

我们建议家长将本软件作为辅助工具，配合日常沟通与引导，共同培养儿童健康的电子设备使用习惯。

## 📄 开源许可

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

- 核心功能全部免费，无付费墙、无内购、无广告
- SQLCipher 使用 BSD 许可的社区版
- 欢迎贡献，详见 [CONTRIBUTING.md](CONTRIBUTING.md)

## 📚 文档

- [协作规则](COLLAB_RULES.md)
- [架构决策记录](docs/adr/)
- [开发进度](docs/PROGRESS.md)
- [贡献指南](CONTRIBUTING.md)
- [更新日志](CHANGELOG.md)（即将发布）

---

🥬 小趴菜 — 守护孩子的数字世界，从开源开始。
