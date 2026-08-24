# Device Owner（设备所有者）说明

[TASK-HARDENING-V1.1.1] Bug1-C 的「只检测不激活」决策已被 [TASK-STRICT-PROVISION-V1]
（ADR 0018，v1.3.0）取代：现在通过「强管制模式」支持**脱离电脑的 Device Owner 自授权预置**。

## 结论（产品决策，已确认）

- 本应用**检测并展示** Device Owner 状态（`DevicePolicyManager.isDeviceOwnerApp(packageName)`），
  并通过「守护状态 → 强管制模式」提供**自授权预置**（ADR 0018，无需电脑，LADB 模式）。
- 检测结果展示于：
  - 儿童端：守护状态自检（健康度快照 `deviceOwner.isActive` / `provisioningAllowed` / 边界说明）；
  - 家长端：守护状态页「Device Owner（企业预置）」卡片；
  - Web 端：设备守护健康度面板。

## 强管制模式自授权预置（ADR 0018，v1.3.0）

- 通道：内嵌官方 adb 二进制（libadb.so 三 ABI）→ 无线调试自配对（6 位配对码）→
  `dpm set-device-owner`。无需电脑，ADB 只做一次性引导，日常能力靠 DO 持久权限。
- 前置：Android 11+（无线调试）、未激活 DO、无账号/出厂重置状态（dpm 强制要求）。
- 实测：OPPO PKV110（Android 16）自配对成功、有账号 dpm 正确分类提示；AVD Android 14
  dpm 成功并显示「已激活（强管制）」；华为 FRD-AL10（Android 8）低版本拦截。
- 能力边界（如实说明）：安全模式启动、Recovery 恢复出厂、root 设备仍可能绕过；
  解除方式 = 恢复出厂设置（受控解除界面 V2 提供）。

## 历史决策（Bug1-C 时代，已被 ADR 0018 部分取代）

- 原结论：**不落地** DPC 激活流程——不在应用内发起任何 Device Owner 预置、不引导 ADB 命令。
- 该结论的「普通用户界面不出现 ADB/命令提示」红线延续（D4 决策）；强管制模式为独立受控入口。
- 原风险认知（仍成立，作为强管制模式的设计约束）：ADB 预置需无账号/初始状态；定制 ROM
  可能限制；普通用户界面不引导 ADB；非法尝试可能清数据 → 安全红线延续：失败不自动重试、
  执行前二次确认、仅在无账号/出厂重置状态操作。

## 检测与展示语义

| 字段 | 含义 | 展示 |
|---|---|---|
| `deviceOwner.isActive` | 本应用是否为设备所有者 | 已激活（企业预置）/ 未激活 |
| `deviceOwner.provisioningAllowed` | 系统是否允许 DP 预置（通常为 true） | 仅记录，不用于驱动任何自动激活 |

- 已激活：守护能力最强（应用无法被用户卸载、强制停止受限）。
- 未激活：按「能力边界」如实说明，引导用户依赖现有防卸载链路（设备管理器 + 家庭组 + 权限引导）。

## 与守护健康度的关系

`GuardDownMonitor.computeHealth()` 将 Device Owner 作为 **信息项** 计入快照，不参与 0-100 健康度评分（评分仅由 6 项可检测权限决定），避免「未激活 DO」拉低健康度造成误导。
