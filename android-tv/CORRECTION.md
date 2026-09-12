# CORRECTION.md — 偏离纠正指令（务必先读再继续）

> 对象：正在运行本项目的 qwen3.6 开发 agent。
> 结论：整体按 P1–P5 推进、APK 已可构建、103 单测通过，**方向正确**；但存在下述偏离，必须纠正后再继续 P3/P4。
> 依据：`../小趴菜_TVOS版_小米_开发提示词包.md`（母包）+ `../小趴菜_TVOS版_小米_主开发派发版.md` + 本目录 `TASK.md`。

## 偏离清单（按优先级，逐条整改）

### 【关键·技术偏离 1】锁屏用了 Activity，而不是悬浮窗覆盖层
- 既定要求（母包 §2.7 决策 2 / §十一 / TASK.md Constraints）：
  锁屏 = `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW`，**由 Service 直接 addView 到 WindowManager**，
  **纯 View 实现（不用 Compose）**，默认无无障碍服务（社区调研 TV Sitter D16/D32）。
- 现状：`BlockOverlayActivity` 是 `ComponentActivity`（Compose），由 `TimeoutExecutorTV` `startActivity` 触发；
  全工程没有 `WindowManager.addView` / `TYPE_APPLICATION_OVERLAY`。Manifest 虽声明了 `SYSTEM_ALERT_WINDOW` 权限但未使用。
- 后果：HOME 键会把锁屏 Activity 退回后台（绕过）；它不是一个"盖在所有 App 全屏视频之上"的常驻覆盖层；
  与 D16「按 HOME 只改变锁屏背后内容」的要求不符。
- 整改：新增 `LockOverlayService`（前台服务），持 `WindowManager` 以 `TYPE_APPLICATION_OVERLAY` 加一个全屏**纯 View**锁屏窗；
  触发改为服务加窗 + 周期 `reassert()`；保留 PIN 验证入口（可弹 `PinVerifyActivity` 或在本窗内完成）；
  `BlockOverlayActivity` 降级为兼容/调试用或删除。改完补一条 E2E：锁屏后实体遥控器按 HOME，锁屏不消失。

### 【流程偏离 2】缺少进度/Token 记录文件
- 现状：android-tv/ 下没有 TASK.md / PROGRESS.md / TOKEN_USAGE.md。
- 整改：TASK.md 已由调度方补入（本目录，权威）；你现在必须：
  1) 每完成一个原子步骤，更新 `PROGRESS.md`（Done/Failed/Verification/Next，≤200 行，失败尝试必须写）；
  2) 在 `TOKEN_USAGE.md` 追加本会话输入/输出/累计 token；
  3) 声称完成前，逐条重跑 TASK.md 的 7 条 verify 命令并用真实输出佐证。

### 【轻微偏离 3】其他
- `.env/`（android-sdk/gradle/jdk）放进项目目录污染工作区：移到 `~/.env/` 或 `~/android-env/`，项目内不留；
  并在 `android-tv/.gitignore` 忽略 `local.properties`、`.gradle/`、`build/`、`app/build/`。
- Manifest 的 `FOREGROUND_SERVICE_PHONE_CALL` 在电视上无意义（疑复制残留），删除。
- `CLOUD_HOST`/`CLOUD_PORT` 写死在 build.gradle.kts：改为可从设置/构建参数注入，默认指向家长自托管地址，不写死单一域名。

## 未偏离、保持的部分（不要推翻重做）
- 包名 com.xiaopacai.tvos、LEANBACK_LAUNCHER + banner + touchscreen=false ✓
- 默认无无障碍服务 ✓（正确，继续坚持）
- 服务/UI 模块齐全（CloudSyncServiceTV/AntiBypass/TamperAlert/BootReceiver/TvHome/Whitelist/Exemption/Announcement/Bedtime）✓
- 103 单测、debug APK 构建 ✓

## 执行顺序（下一步）
1. 先读本文件与 `TASK.md`、`PROGRESS.md`，把上述偏离写入 PROGRESS.md 的 Failed。
2. 先改【关键 1】锁屏实现（Service 加窗 + 纯 View + 覆盖层），这是 P3 核心，其余功能保持。
3. 再补【流程 2】的记录文件与【轻微 3】的清理。
4. 逐条跑 TASK.md verify 命令，全部通过再回复 DONE。
