# 变更日志 — 小趴菜 TVOS 版

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Android TV Jetpack Compose UI 框架
- 锁屏覆盖层（BlockOverlayActivity）
- PIN 密码验证（SHA-256 哈希 + Base64 编码）
- 每日使用时长限额 + 自动锁屏
- 云端心跳同步服务（CloudSyncServiceTV）
- 防绕过监控服务（AntiBypassServiceTV）
- 篡改告警服务（TamperAlertService）
- Android TV 豁免请求页面
- 云端公告弹窗
- 就寝时段功能
- 使用时长统计（UsageStatsManager）
- DataStore 偏好设置管理

### Changed
- `TimeoutStateMachine` 从 object 改为 class（可测试化）
- `EncryptionHelper` 使用 `java.util.Base64` 替代 `android.util.Base64`
- `NetworkHelper` 使用 `java.security.MessageDigest` 进行 SHA-256 哈希
- Jacoco 报告排除协程 lambda 和纯 Service 类

### Fixed
- Jacoco classDirectories 路径从 `intermediates/javac/` 改为 `tmp/kotlin-classes/`
- `TimeoutStateMachine` 状态阈值（EXPIRING ≤10 分钟）
- SHA-256 known vector 测试哈希值
- KClass 比较（`::class` 替代 `.javaClass`）

## [v0.1.0] — 初始版本

### Added
- 项目脚手架（AGP 8.7, Kotlin 1.9, Gradle 8.9）
- 基础数据模型（AppUsage, DeviceConfig, DeviceInfo）
- Room 数据库结构
- 网络层（OkHttp 封装）
- 加密工具类
- 单元测试框架（JUnit 5, Robolectric 4.14.1）
- Jacoco 覆盖率配置
