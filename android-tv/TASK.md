# TASK.md — 小趴菜 TVOS 版（小米电视）· 主任务书

> 本文件是权威任务卡（对应《小趴菜_TVOS版_小米_主开发派发版》§三 / 《开发提示词包》§四）。
> 验收标准逐条机器可判。完成全部 7 条并有真实证据，才回复 DONE。

## Goal
在 android-tv/（包名 com.xiaopacai.tvos，Kotlin + Compose for TV）实现"电视作为儿童端"的时长控制停用：
看电视超时进入受限守护模式（全屏锁屏 + 倒计时 + 白名单 + 家长豁免），沿用 android-v3 `CloudSyncService`
HTTP 心跳接入既有 Web 家长端（xiaopacai-web）。

## Deliverables
- android-tv/app/build/outputs/apk/debug/app-debug.apk
- android-tv/TASK.md、PROGRESS.md、TOKEN_USAGE.md、README.md、CHANGELOG.md
- android-tv/docs/adr/*.md、docs/USER_MANUAL_TV.md（含 §2.5 能力边界声明）、docs/COMPAT_MATRIX.md

## Success criteria（机器可判）
1. 构建通过   verify: `cd android-tv && ./gradlew assembleDebug` 退出码 0
2. APK 为 LEANBACK 形态（TV 入口 + banner + 无需触屏）
   verify: `test -f android-tv/app/build/outputs/apk/debug/app-debug.apk` 且 Manifest 含
   LEANBACK_LAUNCHER + banner + `android.hardware.touchscreen required=false`
3. 单测全绿 + 核心模块覆盖率 >=80%
   verify: `cd android-tv && ./gradlew test`；`./gradlew jacocoTestReport`（策略/锁屏状态机/心跳协议解析）
4. 心跳接入家长端闭环（policy_update 下发 + usage_report 上报返 sync_ack）
   verify: xiaopacai-web `dotnet run` + `docs/e2e/heartbeat_test.py` 断言通过
5. 超时锁屏（核心）：限额调小触发后**悬浮窗锁屏覆盖全屏**（含他人 App 全屏视频）、显示倒计时/白名单/家长豁免入口、HOME 键不可绕过
   verify: `tests/gui/` E2E（adb 截图：锁屏出现/倒计时/豁免解除/次日重置；实体遥控器按 HOME 锁屏不消失）
6. D-pad 焦点全走查：所有页面方向键可达、焦点可见、无死区
   verify: `tests/gui/` 逐页截图 + `adb shell input keyevent KEYCODE_DPAD_*` 记录焦点轨迹
7. 白名单/就寝/公告/豁免请求/篡改告警 五项各有一条真实用例为证
   verify: 对应 E2E 脚本编号逐一通过

## Constraints（硬性，不得偏离）
- 全源码中文注释；Compose for TV + D-pad 焦点优先；10 英尺 UI。
- **锁屏实现（关键）**：`TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW`（draw on top），
  **由 Service 直接 addView 到 WindowManager**，**纯 View 实现（不用 Compose）**；**默认不开无障碍服务**。
  原因（社区调研 D16/D32）：无障碍服务会破坏系统密码掩码；Service 加窗没有 Activity/ViewTree，Compose 不可靠。
- 前台识别：轮询 `UsageStatsManager`，启动用"当日汇总最近使用优先"做种子（D29）。
- 白名单豁免 launcher 与自家 App（D35）；`directBootAware` + `ACTION_LOCKED_BOOT_COMPLETED` 提前拉起（D17）。
- 覆盖层盖住画面 ≠ 停止声音：需媒体会话/音频焦点处置；HDMI 信源需 `reassert()` 周期重提（D11/D12）。
- 不上第三方云（家长端自托管）；SQLCipher；TLS；家长 PIN PBKDF2；凭据不明文不落日志。
- 能力边界遵守 §2.5 六条红线；界面与文档如实说明"受限守护模式"边界。
- TV 端前台服务常驻内存 <80MB；心跳默认 60s 批量增量；改完必跑 `./gradlew test` 全绿。
- 任务 ID [TASK-TV-xx] 写入代码注释与 commit；CLOUD_HOST/端口必须可配置（不写死单一域名）。

## File map（写死）
- 参照（只读）：xiaopacai/android-v3/（CloudSyncService/UsageStats/TimeoutExecutor/BlockOverlay/AntiBypass/OEM_KEEPALIVE/UI_SPEC）
- 协议：xiaopacai-web/docs/API.md
- 输出：android-tv/（rootProject.name=xiaopacai-tvos，applicationId=com.xiaopacai.tvos）

## DONE
- 完成咒语：DONE（全大写唯一）
- 触发：7 条 Success criteria 全满足，每条有真实命令输出/截图为证；否则不得回复 DONE。
