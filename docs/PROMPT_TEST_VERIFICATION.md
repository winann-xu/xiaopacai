# 小趴菜 全面验收测试提示词（逐行、逐个功能）

版本：V1.0    日期：2026-08-11    编制：Codex@50.20（主测试）
目的：交付前按功能逐项、逐行验证，覆盖全部已发现缺陷的回归，确保交付程序尽可能无 Bug。

## 0. 测试环境与规则

### 环境
- 儿童端 AVD：`xiaopacai_test`（emulator-5554，Android 14 x86_64）
- 家长端 AVD：`xiaopacai_parent`（emulator-5556，Android 14 x86_64）
- Web 3.0：`dist/xiaopacai-web3` 发布版（REST 5000 + P2P 9527，`--urls http://0.0.0.0:5000`）
- 模拟器一律**可见窗口**启动（禁止 `-no-window`），调试过程实时可见
- 模拟器互连：`adb -s emulator-5556 forward tcp:9528 tcp:9527`，儿童端连 `10.0.2.2:9528`；
  儿童端连 Web 中继：`10.0.2.2:9527`；真机直接用家长端界面显示的“本机 IP”

### 准备步骤（每次全新验证前执行）
1. 两台模拟器 `install -r` 最新 debug APK
2. 儿童端补权限：
   `settings put secure enabled_accessibility_services com.xiaopacai.child/.service.GuardianAccessibilityService`
   `settings put secure accessibility_enabled 1`
   `appops set com.xiaopacai.child android:get_usage_stats allow`
   `pm grant com.xiaopacai.child android.permission.POST_NOTIFICATIONS`
   `dpm set-active-admin com.xiaopacai.child/.service.GuardianDeviceAdminReceiver`
3. 家长端 `pm grant ... POST_NOTIFICATIONS`
4. 抓取证据：每步 `uiautomator dump` + `screencap` + `logcat -d`，写入 `emulator-walkthrough/`
5. 用例通过标准：界面状态符合预期 + 关键日志无 ERROR/FATAL + 无“无反应”交互

### 测试工具入口（debug-only）
- `adb shell am start -n com.xiaopacai.child/com.xiaopacai.child.debug.DebugTriggerActivity --es action <动作>`
- 动作：start_service/seed/seed_highlimit/seed_partial/seed_partial_block/seed_usage/collect/partial/fullstate/reset/overlay/notify/pair/pair_report/pair_shared/parent_start/parent_stop/parent_paircode/parent_seedpolicy/parent_seedannounce/parent_fulldata/parent_setup

---

## 1. 安装与角色引导

### 1.1 首次启动角色引导
- 前置：`pm clear com.xiaopacai.child`（儿童端）
- 步骤：启动 App → 观察引导页
- 预期：显示“欢迎使用小趴菜”，可选择 儿童端/家长端
- 通过：选择后进入对应流程；返回重开不重复引导

### 1.2 权限引导页（重点回归：去开启）
- 步骤：权限未全时显示“需要开启以下权限”；逐项点“去开启”
- 预期：
  - 使用情况访问 → 系统“使用情况访问”设置页
  - 无障碍服务 → 系统“无障碍”设置页
  - 忽略电池优化 → 系统“电池优化/高耗电”设置页（不得跳错应用）
  - 通知权限（Android 13+）→ 本应用通知设置页
  - 任一意图失败 → 回退打开本应用详情页，且 logcat 有 PermissionGuide 日志
- 通过：四项设置页均能打开；返回后权限状态刷新并自动进入主页

### 1.3 守护服务自启（重点回归）
- 步骤：儿童模式 force-stop 后重开 App
- 预期：App 打开后 20 秒内 `GuardianForegroundService` 自动启动，logcat 出现“守护前台服务启动”“同步管理器已启动”
- 通过：服务运行中（dumpsys activity services 可见）

---

## 2. 家长端（emulator-5556）

### 2.1 家长登录与主页
- 步骤：家长角色启动 → 密码登录 → 主页
- 预期：顶部 P2P 状态条；底部 设备/策略/公告/报告/设置 五个页签

### 2.2 P2P 监听与配对码 + 本机 IP（重点回归）
- 步骤：点“启动”开启 P2P 监听（9527）→ 点“配对码”
- 预期：显示 6 位配对码、**本机 IP（如 192.168.x.x）**、指纹、有效期 5 分钟、二维码
- 通过：IP 紧邻配对码展示，家长可读可截图

### 2.3 儿童端直连配对（双模拟器）
- 步骤：`adb -s emulator-5556 forward tcp:9528 tcp:9527`；儿童端“手动连接”填 `10.0.2.2:9528` + 配对码
- 预期：TLS 握手（指纹=家长端 40a4cb89…）→ 家长端“已连接 1 台设备”
- 通过：儿童端主页显示“已连接到家长端”，收到策略（20 条）

### 2.4 策略配置与下发
- 步骤：策略页调整每日限额/就寝/分类/黑白名单 → 保存
- 预期：家长端列表更新；儿童端收到 policy_update 并落库
- 通过：儿童端主页限额数字随之变化

### 2.5 公告全流程（重点回归：撤回→编辑→再发布）
- 步骤：
  1. 新建公告 → 保存（草稿）→ 发布（已发布）
  2. 撤回已发布公告（已撤回）
  3. 编辑该公告（改标题）→ 保存
  4. 观察是否回到“草稿”并出现“发布”按钮
  5. 再次发布
- 预期：撤回后编辑保存自动回草稿，可再次发布；删除可用
- 通过：步骤 5 后状态为“已发布”，且儿童端收到 announcement_push

### 2.6 设置页与 Web 云端中继
- 步骤：设置 → Web 云端中继 → Web 账号登录（admin/admin123）→ 填 Web 地址 → 连接中继
- 预期：Token 保存；注册 `/api/relay/register` 成功；P2P relay 会话建立
- 通过：Web 管理端 `/api/relay/sessions` 可见 parent 在线会话

### 2.7 忘记密码 / 恢复码
- 步骤：设置 → 生成恢复码
- 预期：8 位恢复码一次性展示；可用于重置密码

---

## 3. 儿童端（emulator-5554）

### 3.1 主页状态
- 预期：连接状态、今日使用/限额/剩余、家长公告区、连接家长端入口、快捷入口（设置/权限管理/关于/应用分类/守护状态/切换到家长端）

### 3.2 公告直达与列表（重点回归：最近 3 条含已确认）
- 步骤：
  1. 家长端发布普通公告 → 儿童端应立即系统通知直达（“公告通知已直达”）
  2. 家长端发布紧急公告 → 儿童端全屏置顶 AnnouncementOverlayActivity，未确认不可关闭
  3. 确认后回执 announcement_ack → 家长端日志“公告确认回执”
  4. 主页“家长公告”区检查
- 预期：列表显示**最近 3 条公告**（含已确认/已读的），按优先级+时间排序
- 通过：3 条均可见，确认过的也保留在列表

### 3.3 应用分类页（重点回归）
- 步骤：主页点“应用分类”
- 预期：打开 AppCategoryActivity，列出已安装应用与分类，可手动修改
- 通过：页面正常打开并可操作

### 3.4 守护状态页（重点回归）
- 步骤：主页点“守护状态”
- 预期：GuardianStatusActivity，显示 设备管理器/无障碍/用量/自启/电池优化 5 项就绪状态
- 通过：正常打开且状态实时

### 3.5 超时停用 full/partial
- full：`seed`（限额 1 分钟）+ `collect` → isTimeout=true full → 打开非白名单应用被 BlockOverlay 拦截
- partial：`seed_partial` + `collect` → 白名单(Chrome)放行、黑名单/非白名单拦截
- 重点：**超时期间 App 自身页面（应用分类/守护状态/权限引导）仍可打开**（不得被自身拦截死锁）
- 通过：覆盖层文案正确；返回桌面/紧急电话可用；解除后恢复

### 3.6 断线重连
- 步骤：杀掉家长端 App（连接断开）→ 观察儿童端
- 预期：P2PConnection 自动重连（退避递增），重连后策略再下发、心跳恢复

### 3.7 守护服务自启与数据同步
- 步骤：重开儿童端 App
- 预期：服务自启；`seed_usage` + 连接后 usage_report → 家长端 sync_ack；“已同步 N 条”

### 3.8 诊断上报
- 步骤：触发 DiagnosticsDailyWorker（或等每日任务）
- 预期：P2P diagnostics_report 直连家长端可被处理（日志“收到诊断信息”）；Web REST 上报闭环可用

---

## 4. Web 3.0（localhost:5000）

### 4.1 登录与首页
- admin/admin123；首页设备/策略/公告/报告/设置/管理端菜单

### 4.2 设备与策略
- 设备列表显示儿童端在线；策略配置保存后经 P2P 下发

### 4.3 公告与中继
- 公告发布/撤回；`/api/relay/sessions` 显示 parent/child 会话；中继转发 usage_report 到家长端

### 4.4 诊断模块
- `POST /api/diagnostics` → 列表/导出可见

### 4.5 扫码登录 / 忘记密码
- login-ticket 生成/轮询/确认 → 自动登录；reset-ticket 流程

### 4.6 静态文件托管（重点回归）
- GET / 返回 index.html；GET /login 等前端路由回退 200

### 4.7 后端测试
- `dotnet test` 178 项全过；`npm run build` 0 错误

---

## 5. 协议与链路（日志级）

- handshake：配对码校验、设备注册去重、指纹记录
- policy_update：家长/Web → 儿童端落库
- usage_report：儿童端 → 家长/Web 写入 + sync_ack + 中继转发
- announcement_push / announcement_ack：双向闭环
- heartbeat / heartbeat_ack：30s 周期，3 次无响应断线重连
- 帧协议：4 字节大端长度 + JSON，1MB 上限
- diagnostics_report：直连家长端处理；Web 链路待补（已知遗留）

---

## 6. 已修复缺陷回归清单（必须全过）

| # | 缺陷 | 验证方式 | 状态 |
|---|------|---------|------|
| 1 | 公告撤回后编辑无法再发布 | 2.5 全流程 | 待验 |
| 2 | 儿童端应用分类/守护状态点击无反应 | 3.3/3.4 | 待验 |
| 3 | 儿童端用量无法重置（系统统计残留） | wipe-data 后已用 0 | 待验 |
| 4 | 守护服务重装/重启后不自启 | 1.3 | 待验 |
| 5 | 公告收到但不显示（服务未运行） | 3.2 | 待验 |
| 6 | 去开启按钮无反应/电池按钮跳错应用 | 1.2 | 待验 |
| 7 | 公告列表只显示未读 | 3.2 最近 3 条 | 待验 |
| 8 | 中继注册端点缺失/owner 未绑定 | 2.6 + Web 4.3 | 待验 |
| 9 | Web usage_report SQLite 翻译异常 | 4.3 中继转发 | 待验 |
| 10 | Web 发布版静态文件 404 | 4.6 | 待验 |
| 11 | 超时期间自身页面死锁 | 3.5 | 待验 |
| 12 | 家长端配对码旁无 IP | 2.2 | 待验 |

## 7. 交付门槛
- 全部用例通过；回归清单 12 项全绿
- 双端 30 分钟长稳：0 FATAL、Web 0 异常、心跳持续
- dist 三端产物更新（APK/Windows/Web3）
- CHECKPOINT 更新 + 结果回传 Claude；GitHub 推送**仅在用户明确指示时**执行
