# 兼容矩阵 — 小趴菜 TVOS 版

本文件列出小趴菜 TVOS 版支持的 Android TV 设备和操作系统版本。

## 系统版本

| Android 版本 | API 级别 | 支持状态 |
|-------------|---------|---------|
| Android 8.0 (Oreo) | 26 | ✅ 最低支持 |
| Android 9 (Pie) | 28 | ✅ 支持 |
| Android 10 (Q) | 29 | ✅ 支持 |
| Android 11 (R) | 30 | ✅ 支持 |
| Android 12 (S) | 31 | ✅ 支持 |
| Android 12L | 32 | ✅ 支持 |
| Android 13 (Tiramisu) | 33 | ✅ 支持 |
| Android 14 (UpsideDownCake) | 34 | ✅ 支持 |
| Android 15 (VanillaIceCream) | 35 | ✅ 目标版本 |

## 已验证/推荐设备

### Google TV / Android TV 官方设备

| 设备 | 制造商 | 推荐系统 | 状态 |
|------|--------|---------|------|
| Chromecast with Google TV (4K) | Google | Android 13 | ✅ 已验证 |
| Chromecast with Google TV (HD) | Google | Android 13 | ✅ 已验证 |
| NVIDIA Shield TV Pro | NVIDIA | Android 11/14 | ✅ 已验证 |
| NVIDIA Shield TV (2019) | NVIDIA | Android 11 | ✅ 已验证 |

### 中国厂商 TV 系统

| 设备 | 系统 | 状态 | 备注 |
|------|------|------|------|
| 小米电视 / 小米盒子 | MIUI TV | ⚠️ 需测试 | 防绕过服务需特殊权限 |
| 海信电视 | VIDAA / Android TV | ⚠️ 需测试 | 部分 VIDAA 非 Android |
| 创维电视 | Skyworth OS | ⚠️ 需测试 | 需确认 Android 基础 |
| TCL / 雷鸟 | Google TV | ⚠️ 需测试 | 通常兼容良好 |
| 索尼电视 | Google TV | ✅ 兼容 | Google 认证设备 |
| 三星 TV | Tizen | ❌ 不兼容 | 非 Android 系统 |
| LG TV | webOS | ❌ 不兼容 | 非 Android 系统 |

## 权限需求

| 权限 | 用途 | 危险权限 | 运行时请求 |
|------|------|---------|-----------|
| `INTERNET` | 云端心跳 | 否 | 否 |
| `FOREGROUND_SERVICE` | 后台服务 | 否 | 否 |
| `POST_NOTIFICATIONS` | 告警通知 | 是 (API 33+) | 是 |
| `android.permission.PACKAGE_USAGE_STATS` | 使用统计 | 特殊权限 | 需引导跳转设置 |
| `android.permission.QUERY_ALL_PACKAGES` | 防绕过检测 | 特殊权限 | 需在 AndroidManifest 声明 |

## 特殊设备配置

### 小米电视 / 小米盒子

- **MIUI TV 特性**：可能自动杀死后台服务
- **解决方案**：
  1. 在 MIUI 设置中忽略电池优化
  2. 在应用管理中设置"自启动"
  3. 在安全中心中将应用加入白名单
- **防卸载**：DevicePolicyManager 可能受限

### NVIDIA Shield TV

- **开发者模式**：默认支持 ADB 调试
- **无障碍服务**：完全支持
- **使用统计**：完全支持

## 已知限制

1. **三星 Tizen / LG webOS**：非 Android 系统，无法运行
2. **旧款 Android 4.x/5.x 电视**：低于 API 26，不支持
3. **部分国产定制系统**：可能限制后台服务或特殊权限

## 建议测试设备优先级

1. 🔴 **高优先级**：Chromecast 4K、NVIDIA Shield TV Pro
2. 🟡 **中优先级**：小米盒子/电视、索尼 Google TV
3. 🟢 **低优先级**：TCL、海信、其他厂商
