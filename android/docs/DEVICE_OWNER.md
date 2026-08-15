# Device Owner（设备所有者）说明

[TASK-HARDENING-V1.1.1] Bug1-C：Device Owner 仅检测与说明，不落地 DPC 激活。

## 结论（产品决策，已确认）

- 本应用 **只检测、只展示** Device Owner 状态（`DevicePolicyManager.isDeviceOwnerApp(packageName)` 与 `isProvisioningAllowed(ACTION_PROVISION_MANAGED_DEVICE)`）。
- **不落地** DPC 激活流程：不在应用内发起任何 Device Owner 预置、不引导 ADB 命令、不请求企业模式。
- 检测结果展示于：
  - 儿童端：守护状态自检（健康度快照 `deviceOwner.isActive` / `provisioningAllowed` / 边界说明）；
  - 家长端：守护状态页「Device Owner（企业预置）」卡片；
  - Web 端：设备守护健康度面板。

## 为什么不做自动激活（边界诚实说明）

1. 未激活设备成为 Device Owner 的合法途径是 **ADB 预置**（`adb shell dpm set-device-owner`），需要：
   - 设备上无任何账号、处于初始设置状态（多数 OEM 限制）；
   - USB 调试开启 + 桌面电脑操作。
2. OPPO 等定制 ROM 对第三方 Device Owner 预置有额外限制（需移除/无 Google 账号等）。
3. 对普通家长用户，ADB 流程不可达（且违反本应用「普通用户界面一律不出现 ADB/命令/调试提示」的既有决策，见 ADR-0014 之前的 D4 决策）。
4. 非法尝试激活会被系统拒绝并可能产生副作用（如清数据）。**安全红线优先：不提供可能造成数据丢失的自动化操作。**

## 检测与展示语义

| 字段 | 含义 | 展示 |
|---|---|---|
| `deviceOwner.isActive` | 本应用是否为设备所有者 | 已激活（企业预置）/ 未激活 |
| `deviceOwner.provisioningAllowed` | 系统是否允许 DP 预置（通常为 true） | 仅记录，不用于驱动任何自动激活 |

- 已激活：守护能力最强（应用无法被用户卸载、强制停止受限）。
- 未激活：按「能力边界」如实说明，引导用户依赖现有防卸载链路（设备管理器 + 家庭组 + 权限引导）。

## 与守护健康度的关系

`GuardDownMonitor.computeHealth()` 将 Device Owner 作为 **信息项** 计入快照，不参与 0-100 健康度评分（评分仅由 6 项可检测权限决定），避免「未激活 DO」拉低健康度造成误导。
