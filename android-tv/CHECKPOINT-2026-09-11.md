# 小趴菜 TV 端 — 续接文件（CHECKPOINT 2026-09-12 · 第三轮后）

> 本文件是给下一个会话/AI 的交接说明。读完这份就能直接接手，不必翻历史。
> 上一轮（9 项需求）见 `PROGRESS.md` 的「第二轮」；本轮见「第三轮」。

## 一句话现状

Android TV 版（小米电视 4A / Android 5.1.1 / 1280×720 / 遥控器）已具备：
单屏主界面 + 家长账号门禁 + 紧急解除（主界面与锁屏）+ 公告管理 + 设备注册/心跳/策略/用量上报 +
**设备绑定（家长验证后自动绑定）** + **心跳状态可视化** + **今日倒计时** + **防绕过三件套**（看门狗 / 无障碍拦截 / 设备管理员）+
**脱敏日志上传**。全部真机验证通过。

## 工程与命令速查

```bash
# 工作目录
cd ~/01-project/05-codex_project/projects/003-xiaopacai/android-tv

# 构建 / 安装（JAVA_HOME 必须显式指定）
export JAVA_HOME=~/jdk/jdk-17.0.20.1+1/Contents/Home
export ADB=~/Library/Android/sdk/platform-tools/adb
./gradlew assembleDebug
$ADB connect 192.168.1.21:5555
$ADB install -r app/build/outputs/apk/debug/app-debug.apk

# ⚠️ 包名带 .debug 后缀（debug 构建），写成 com.xiaopacai.tvos 会 am start 失败
P=com.xiaopacai.tvos.debug
$ADB shell am start -n $P/com.xiaopacai.tvos.ui.TvHomeActivity
```

## 关键文件地图（本轮新增/改动）

| 文件 | 作用 |
|---|---|
| `ui/TvHomeActivity.kt` | 主界面：logo/家长账号/心跳/绑定状态/倒计时/门禁/紧急解除/恢复管制；`SHOW_INSTALLED_APPS=false` 隐藏已装应用卡片 |
| `ui/lock/BlockOverlayActivity.kt` | 锁屏：纯色 `#B71C1C` 全红底、三按钮一行、心跳行；遥控器选中靠 `dispatchKeyEvent` + `selectedIndex`（**不依赖 Compose 焦点**）|
| `ui/login/ParentLoginActivity.kt` | 家长验证（账号+密码，密码不落盘）；成功即调 `DeviceBinding` 自动绑定 |
| `util/DeviceBinding.kt` | **项3 关键**：家长 JWT → `/api/pairing/generate-code` + `/api/pairing/verify` 绑定本机 |
| `util/HeartbeatStatus.kt` | 心跳状态（online / lastSuccessAt），供主界面+锁屏显示 |
| `util/AppLogTV.kt` | 日志环形缓冲 + **写入即脱敏** + 崩溃捕获；`AppLogTV.init()` 必须先在 Application 调 |
| `service/GuardianWatchdog.kt` | **项2 关键**：AlarmManager 每 60s 自检并拉起全部守护组件（进程死了也触发）+ `GuardianWatchdogReceiver` |
| `service/TVAccessibilityService.kt` | 超时后检测到切换应用 → 立刻盖回锁屏（需家长手动开启）|
| `service/GuardianDeviceAdminReceiver.kt` | 设备管理员（防卸载）；`isActive()` / `requestActivate()` |
| `service/CloudSyncServiceTV.kt` | 心跳 60s；用量上报 5 次；**诊断+日志上传 30 次**（`/api/v1/device/diagnostics-report`，设备令牌通道，无需家长 JWT）|
| `ui/settings/SettingsActivity.kt` | 2×3 单屏；含「🛡 防护增强」卡（无障碍/管理员一键跳转 + 状态）|

## 后端契约（已验证存在）

```
POST /api/auth/login                              {Username,Password} → accessToken
POST /api/v1/device/register                      设备匿名注册 → deviceToken
POST /api/v1/device/heartbeat                     心跳（响应含 commands / policyVersion）
GET  /api/v1/device/policies                      策略
POST /api/v1/device/usage-report                  用量上报
POST /api/v1/device/diagnostics-report            诊断+日志上报（deviceId 必须与令牌一致）
GET  /api/v1/device/announcements                 ⚠️ TV 端尚未接入（见待办）
POST /api/pairing/generate-code                   {method:"manual"} → pairCode（需家长 JWT）
POST /api/pairing/verify                          {pairCode,deviceId,deviceName,platform} → 绑定（需家长 JWT）
GET  /api/devices                                 家长设备列表（绑定后才出现这台电视）
POST /api/logs                                    家长 JWT 通道，TV 未使用（无家长令牌存储）
```

## 真机操作坑（踩过的，别重踩）

- **限时触发锁屏**：把 `daily_limit_minutes` 设 0 最省事（= 完全禁用，立刻锁）。
- **改 DataStore**（设置页有家长门禁，改限额最快的方式）：
  `run-as <pkg> cat files/datastore/xiaopacai_tv_settings.preferences_pb` 拉出来 → 手改 protobuf
  （map<string,Value>；`daily_limit_minutes` 是 Value.integer 字段 3）→ `adb push /data/local/tmp/xxx`（**必须 chmod 644**）
  → `run-as <pkg> sh -c 'cat /data/local/tmp/xxx > files/datastore/...preferences_pb'`。
  ⚠️ `printf | adb shell "run-as ... cat > file"` 这种 stdin 管道在本机会挂住；`run-as cp /sdcard/...` 读不了 sdcard。
- **非 root 无法 kill 本应用进程**（`kill: Operation not permitted`）；`am force-stop` 会连闹钟一起取消，
  所以「被杀后自愈」只能靠 `dumpsys alarm` 证明闹钟在册 + 观察 60s 一次的自检日志。
- **`uiautomator dump` 在 MIUI 电视上偶发 `FATAL EXCEPTION IN SYSTEM PROCESS`** —— 是测试工具自身报错，与 App 无关。
- **macOS 没有 `timeout` 命令**；`screencap` 出来的 PNG 可用纯 python（zlib+反滤波）取像素验证颜色。
- 拿到 UI 文本后用 `input tap x y`：主界面「恢复管制」约 (1050,611)，锁屏三按钮 y≈515。

## 设备所有者（DO）—— 本机已获得，勿丢

- 状态：**是 device owner**（`isDeviceOwnerApp=true`；`setUninstallBlocked` 生效；`adb uninstall` 被拒）
- ⚠️ **MIUI 的 `dumpsys device_policy` 不打印 DO 段**，别拿它判断；用应用内 `isDeviceOwnerApp()` 或
  「再 `dpm set-device-owner` 报 already set」验证
- **若哪天丢了（恢复出厂/清数据）怎么重设**：ADB 装上 APK → 
  `settings put secure user_setup_complete 0` + `settings put global device_provisioned 0` →
  `dpm set-device-owner <pkg>/com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver` →
  立刻把两个设置项还原为 1。**不需要真的恢复出厂**（本机就是这么拿到的）
- 撤销：只能由 App 调 `clearDeviceOwnerApp()`，或恢复出厂（本 ROM 的 dpm 没有 remove-active-admin）
- DO 解锁但**尚未实现**的能力：`setApplicationHidden`（应用拦截/分类限额正解）、
  `setLockTaskPackages`+`startLockTask`（kiosk 锁任务，退不出去）、`setScreenCaptureDisabled`

## 待办 / 待家长拍板

1. ~~项3 真实登录绑定验证~~ **已完成（家长 09:25 在电视上登录一次即自动绑定，策略已下发）**
2. **项9 差异清单**（手机版有、TV 版没有）已在对话中列出，等家长挑选项后一次性实现。高优先候选：
   - 云端公告下发到电视（`/api/v1/device/announcements` + 弹窗，TV 端完全没接）
   - 白名单落盘 + 云下发（目前是演示数据，导致"白名单不受限"不成立）
   - 就寝时段 / 豁免申请（`BedtimeActivity`、`ExemptionRequestActivity` 是**死页面**，无入口）
   - 权限引导（用量统计权限未授予 → 真实用量读 0）
   - ~~严格模式（device owner）~~ **已完成**；剩：手动配对码绑定 / 应用拦截（DO 的 setApplicationHidden）/ 守护状态页 / 应用内升级

## 收尾状态（本次离开时的电视）

- 设备所有者：已获得；禁止卸载：已生效（`adb uninstall` 被拒）
- 每日限额 90 分钟（来自 web 策略）、当日已用约 18 分钟；紧急解除：已关闭
- ⚠️ 每次覆盖安装 APK 后要重开无障碍（系统会解除绑定）：`settings put secure enabled_accessibility_services <pkg>/com.xiaopacai.tvos.service.TVAccessibilityService`
- 无障碍守护：已开启；设备管理员：已激活；看门狗闹钟：在册
- 主界面在前台；应用进程运行中；`files/logs/tv_applog.txt` 持续累积
