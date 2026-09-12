# 小趴菜 TVOS 版 — 家长控制应用

> 面向 Android TV / Google TV / 小米电视的下一代家长控制锁屏应用

## 📦 项目概述

小趴菜 TVOS 版是一款专为 Android TV 生态设计的家长控制应用，提供：

- **每日使用时长限额** — 按设备设置每日最大使用时间
- **超时自动锁屏** — 时间到达后自动显示锁屏覆盖，需家长 PIN 解锁
- **云端策略同步** — 通过 HTTP 心跳接口远程下发限额/白名单/PIN 变更
- **防绕过机制** — 监控应用状态，防止卸载/禁用/清除数据
- **篡改告警** — 检测守护服务关闭、调试模式开启等异常并上报云端
- **Android TV 优化 UI** — 支持 D-pad 键盘导航、聚焦动画、大屏布局

## 🏗 架构概览

```
┌─────────────────────────────────────────────────────┐
│                   App Entry Point                   │
│              XiaopacaiTVApp (Application)           │
├──────────────┬──────────────────┬───────────────────┤
│   Services   │   Core Logic     │     UI Layer      │
│              │                  │                   │
│ CloudSync    │ TimeoutExecutor  │  HomeScreen       │
│ ServiceTV    │ TimeoutStateMachine│  LockScreen     │
│ AntiBypass   │ EncryptionHelper │  PINInputScreen   │
│ ServiceTV    │ NetworkHelper    │  SettingsScreen   │
│ TamperAlert  │ UsageStatsHelper │  ExemptionScreen  │
│ Service      │ SharedPrefHelper │  Announcement     │
│              │                  │  BedtimeScreen    │
├──────────────┴──────────────────┴───────────────────┤
│              Data Layer                             │
│  Room DB | DataStore | Shared Preferences          │
└─────────────────────────────────────────────────────┘
```

## 📁 项目结构

```
android-tv/
├── app/
│   ├── src/main/java/com/xiaopacai/tvos/
│   │   ├── data/
│   │   │   ├── database/          # Room 数据库 (DAOs, Entities)
│   │   │   └── model/             # 数据模型 (AppUsage, DeviceConfig, DeviceInfo)
│   │   ├── service/               # 后台服务
│   │   │   ├── CloudSyncServiceTV.kt    # 云端心跳同步
│   │   │   ├── AntiBypassServiceTV.kt   # 防绕过监控
│   │   │   └── TamperAlertService.kt    # 篡改告警
│   │   ├── ui/                    # Jetpack Compose UI 页面
│   │   │   ├── home/              # 首页
│   │   │   ├── lockscreen/        # 锁屏覆盖层
│   │   │   ├── pin/               # PIN 输入
│   │   │   ├── settings/          # 设置页面
│   │   │   ├── exemption/         # 豁免请求
│   │   │   ├── announcement/      # 公告弹窗
│   │   │   └── bedtime/           # 就寝时段
│   │   ├── util/                  # 工具类
│   │   │   ├── TimeoutExecutorTV.kt       # 锁屏状态机
│   │   │   ├── TimeoutStateMachine.kt     # 状态机引擎
│   │   │   ├── EncryptionHelper.kt        # 加密工具
│   │   │   ├── NetworkHelper.kt           # HTTP 请求
│   │   │   ├── UsageStatsHelperTV.kt      # 使用时长统计
│   │   │   └── SharedPreferenceHelperTV.kt # DataStore 配置
│   │   └── XiaopacaiTVApp.kt      # Application 入口
│   ├── src/test/                  # JVM 单元测试
│   └── src/androidTest/           # Robolectric / Instrumented 测试
├── build.gradle.kts               # 项目级构建配置
└── app/build.gradle.kts           # 应用级构建配置
```

## 🛠 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Kotlin 1.9.x |
| **AGP** | 8.7 |
| **Gradle** | 8.9 |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 35 (Android 15) |
| **UI** | Jetpack Compose for TV |
| **数据库** | Room 2.6.x |
| **配置存储** | DataStore Preferences |
| **网络** | OkHttp 4.x |
| **并发** | Kotlin Coroutines + Flow |
| **测试** | JUnit 5, Robolectric 4.14.1, Mockito 5.14.2 |
| **覆盖率** | Jacoco (≥80% 核心模块目标) |

## 🔧 构建指南

### 前置条件

1. **JDK 17** — 推荐 Eclipse Temurin 17
2. **Android SDK** — API 35 平台 + Build Tools
3. **Android Studio** (可选) — 用于图形化调试

### 构建命令

```bash
# 清洁构建 Debug APK
./gradlew clean assembleDebug

# 运行单元测试
./gradlew testDebugUnitTest

# 生成 Jacoco 覆盖率报告
./gradlew jacocoTestReport

# 运行 Robolectric 测试
./gradlew testDebugUnitTest --tests "*.Robolectric*"
```

### 输出位置

```
android-tv/app/build/outputs/apk/debug/app-debug.apk  # Debug APK (~26MB)
android-tv/app/build/reports/jacoco/jacocoTestReport/ # HTML 覆盖率报告
```

## 📊 核心功能模块

### 1. TimeoutStateMachine — 锁屏状态机

四状态自动机：

```
IDLE ──onAppLaunched──▶ RUNNING ──refreshUsage──▶ EXPIRING
  ▲                                                       │
  │                    unlock/nextDayReset                ▼
  └──────────────────────────────────────────── LOCKED
```

- 状态转换条件可测试（已通过单元测试验证 98% 方法覆盖）
- `dailyLimit`、`usedMinutes` 公开属性支持外部注入

### 2. CloudSyncServiceTV — 云端心跳同步

- 60 秒心跳循环 POST `/api/v3/heartbeat`
- 服务端返回 policy / announcement / config
- 支持 bind / unbind / lock / unlock 动作

### 3. AntiBypassServiceTV — 防绕过监控

- 监听 `ACTION_PACKAGE_REMOVED/ADDED/REPLACED`
- 5 秒循环检查应用完整性 + 网络状态
- 检测电视多用户会话切换

### 4. TamperAlertService — 篡改告警

- 检查守护服务运行状态
- 检测 USB 调试模式、无障碍服务关闭
- 使用统计权限撤销检测
- 本地通知 + 云端告警上报

## 🧪 测试覆盖

当前单元测试覆盖的核心模块：

| 模块 | 指令覆盖率 | 方法覆盖率 |
|------|-----------|-----------|
| `EncryptionHelper` | 100% | 100% |
| `TimeoutStateMachine` | 92% | 98% |
| `NetworkHelper` (ResponseResult) | 100% | 100% |
| `data/model/*` | 待添加 | 待添加 |
| `service/*` | 待 Robolectric | 待 Robolectric |
| `TimeoutExecutorTV` | 14% | 31% (协程 lambda 不可测) |

> Jacoco 报告已排除不可测试的协程 lambda（`$refreshUsage$1` 等）和纯 Service 类。

## 🔗 云端协议

### 心跳接口

```
POST /api/v3/heartbeat
Content-Type: application/json

{
  "device_id": "uuid-here",
  "bind_token": "token",
  "usage_data": [...],
  "diagnostics": {},
  "timestamp": 1234567890
}

Response:
{
  "code": 0,
  "policy": {"daily_limit": 60, "whitelist": [...]},
  "announcement": "系统维护通知",
  "config": {"pin_hash": "..."},
  "action": "lock"
}
```

## 📱 兼容设备

详见 [COMPATIBILITY.md](./COMPATIBILITY.md)

## 📄 许可

Copyright © 2024 小趴菜团队
