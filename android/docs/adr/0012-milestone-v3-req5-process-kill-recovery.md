# ADR 0012 — 里程碑 V3 需求 5：上滑结束进程后管控失效修复

- 日期：2026-08-15
- 状态：已采纳（TASK-MILESTONE-V3）
- 范围：仅 Android 客户端（服务端无改动）

## 背景

真机 Bug：限额到期后被管控 App 被拦截；用户上滑结束小趴菜进程后，被管控 App 恢复可用。
根因：上滑结束触发进程被杀（OPPO/小米等 OEM 常见），进程内 START_STICKY 重启被延迟或抑制，
管控执行随进程消亡；且排查发现 **GuardianAlarmReceiver 从未在 AndroidManifest 声明**，
AlarmManager 兜底链路（30 分钟）实际静默失效——恢复依赖 WorkManager 15 分钟周期，
管控失效窗口可达 15 分钟以上。

## 决策与实现（四层恢复 + 如实边界）

### 1. 修复漏注册（存量缺陷）

- AndroidManifest 补声明 `GuardianAlarmReceiver`（exported=false），30 分钟兜底闹钟恢复生效。

### 2. 上滑结束快速恢复（系统侧闹钟，不随进程消亡）

- `GuardianForegroundService.onTaskRemoved`：上滑时抢先注册 5 秒一次性精确闹钟
  （`setExactAndAllowWhileIdle`，无精确闹钟权限时退化 `setExact`/`set`），
  打 `swipe_recover_pending` 标记；
- `GuardianAlarmReceiver.ACTION_SWIPE_RECOVERY`：拉起守护服务；若 `enforcement_active`
  标记为真，通知「守护已自动恢复，管控重新生效」（安全频道 + 点击回到小趴菜），
  并附自启动/电池优化提示；清除待恢复标记。

### 3. 进程被杀检测 + 管控快速重放

- 心跳：服务每分钟打标 `guardian_heartbeat_ms` + `guardian_boot_epoch`
  （用开机时刻区分「设备重启」与「进程被杀」，避免开机自启误报）；
- `onStartCommand` 检测：心跳间隔 > 5 分钟且开机时刻未变且非上滑路径 →
  判定被杀，通知家长（安全频道）；
- `TimeoutExecutor` 管控生效/解除时打标 `enforcement_active`（+mode）：
  - 服务重启时若标记为真，立即重放一次 `collectAndPersist()`（不等 30 秒初始延迟），
    让拦截/全屏封锁尽快重新生效；
  - 恢复通知据此选择「管控重新生效」文案。

### 4. 用户引导与能力边界（如实说明）

- 权限引导页新增「能力边界说明」卡片：上滑结束 → 约 5 秒自动恢复并通知家长；
  「强制停止」→ 系统一并取消闹钟/WorkManager，任何应用无法自我恢复，重新打开即恢复并通知；
- `OEM_KEEPALIVE.md` 增补「上滑结束/强制停止专项说明」表格（场景×行为×恢复时限）与技术要点。

## 验收注意（写给 Codex）

- 真机回归原 Bug 场景：限额到期 → 上滑结束 → 约 5~30 秒内被管控 App 重新被拦截 +
  安全频道通知「守护已自动恢复，管控重新生效」；
- 强制停止 → 重新打开小趴菜 → 管控立即恢复 + 通知（无法自动恢复为平台限制，属预期）；
- 设备重启 → 开机自启恢复且**不**误报「进程曾被结束」；
- 单元测试新增 `KillRecoveryTest`（阈值判定 5 例）。

## 能力边界结论（需求 5 原文要求如实说明）

第三方应用无法绝对阻止用户手动结束进程。本方案将「管控失效窗口」从最长 15 分钟以上
压缩到秒级（上滑路径 ~5 秒），并在每次恢复时通知家长；「强制停止」场景受平台约束
只能做到「重新打开即恢复并通知」。此即 Android 平台约束下的可行上限。
