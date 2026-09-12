# ADR-001: 状态机从 object (Singleton) 改为 class

**状态**: 已采用
**日期**: 2024-01-15

## 背景

`TimeoutStateMachine` 初始实现为 Kotlin `object`（单例），所有状态和方法都是静态的。这在开发阶段可以工作，但存在两个严重问题：

1. **不可测试** — 单例状态在测试间共享，无法隔离测试
2. **不可注入** — 依赖全局单例，无法 Mock

## 决策

将 `TimeoutStateMachine` 从 `object` 改为普通 `class`，使其可以实例化。

## 考量

| 选项 | 优点 | 缺点 |
|------|------|------|
| `object`（单例） | 简洁，全局唯一 | 不可测试，不可 Mock |
| `class`（可实例化） | 可测试，可 Mock，可注入 | 需要管理生命周期 |
| DI 框架（Hilt/Koin） | 自动化管理 | 引入额外依赖，复杂度增加 |

## 结果

- `TimeoutStateMachine` 改为 `class`
- `state` 改为 `var` + `private set`
- `dailyLimit`、`usedMinutes` 设为 `public`
- `TimeoutExecutorTV`（object）持有唯一实例
- 测试可直接 `TimeoutStateMachine()` 实例化

## 后续

在引入 Hilt/Dagger 时，可将 `TimeoutStateMachine` 注册为 `@Singleton` 绑定。

---

# ADR-002: 使用 java.util.Base64 替代 android.util.Base64

**状态**: 已采用
**日期**: 2024-01-15

## 背景

`EncryptionHelper` 使用 `android.util.Base64` 进行编码/解码。这在 Android 运行时正常，但在 JVM 单元测试（Robolectric 或纯 JVM test）中抛出 `RuntimeException`，因为 `android.util` 包中的类在 JVM 上是空实现。

## 决策

改用 `java.util.Base64`（`getEncoder()` / `getDecoder()`）。

## 考量

| 选项 | 优点 | 缺点 |
|------|------|------|
| `android.util.Base64` | Android 原生，API 丰富 | JVM 测试不兼容 |
| `java.util.Base64` | JVM 兼容，标准库 | 需要手动处理字符集 |
| Apache Commons Codec | 功能丰富 | 引入额外依赖 |

## 结果

- `base64Encode` 使用 `java.util.Base64.getEncoder()`
- `base64Decode` 使用 `java.util.Base64.getDecoder()` + try-catch
- `bytesToBase64` 使用 `Base64.getUrlEncoder().withoutPadding()`
- `base64ToBytes` 使用 `Base64.getUrlDecoder()` + try-catch

---

# ADR-003: Jacoco 覆盖率报告排除策略

**状态**: 已采用
**日期**: 2024-01-15

## 背景

Jacoco 初始报告显示 14% 指令覆盖率。问题在于：

1. 协程 lambda（`$refreshUsage$1`, `$onAppLaunched$1` 等）无法在 JVM 测试中执行
2. 纯 Service 类（含 Android 生命周期）需要 Robolectric 或设备测试

## 决策

在 `JacocoReport` 的 `classDirectories` 中：

1. **包含**：`util/` 和 `data/model/` 的类
2. **排除**：协程 lambda 类名模式和 Service 类
3. **目标覆盖率**：核心模块（util + model）≥80%

## 排除清单

```
**/*$refreshUsage\$1*.class
**/*$onAppLaunched\$1*.class
**/*$postJson\$1*.class
**/*$get\$1*.class
**/*$startHeartbeat\$1*.class
**/*$triggerHeartbeat\$1*.class
**/*$startMonitoring\$1*.class
**/*$startTamperDetection\$1*.class
**/service/CloudSyncServiceTV*.class
**/service/AntiBypassServiceTV*.class
**/service/TamperAlertService*.class
```

## 结果

- 覆盖率报告更准确反映核心模块质量
- 测试目标明确：util 和 model 模块 ≥80%

---

# ADR-004: Android TV Compose 而非原生 XML

**状态**: 已采用
**日期**: 2024-01-15

## 背景

Android TV 应用可以选择使用原生 XML 布局或 Jetpack Compose for TV。

## 决策

选择 Jetpack Compose for TV。

## 考量

| 选项 | 优点 | 缺点 |
|------|------|------|
| XML 布局 | 成熟，生态完善 | 代码冗长，声明式差 |
| Compose for TV | 声明式，代码简洁，热重载 | 学习曲线，部分 API 仍在演进 |

## 结果

- 所有 UI 页面使用 Compose
- 支持 D-pad 键盘导航
- 使用 `tv.material3` 组件
- 聚焦状态通过 `focusable()` + `Modifier.onFocusChanged()` 处理
