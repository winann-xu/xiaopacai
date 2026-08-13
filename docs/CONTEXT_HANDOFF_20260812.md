# 小趴菜 上下文交接快照（2026-08-12 新连接用）

> 用途：压缩上下文。新会话先读本文件 + `docs/PROMPT_TEST_VERIFICATION.md` +
> `emulator-walkthrough/deep-test-results-20260811.md`，即可无缝续接。

## 项目定位
- 小趴菜：儿童守护·家长监控。开源 Apache-2.0、本地优先、P2P TLS；数据点对点/只存终端，
  账号/互联/诊断可落 Web（用户已许可）。
- 三端：Android 儿童/家长端、Windows 家长端、Web 3.0。

## 仓库与最新状态（2026-08-12）
- 本地镜像：`C:\Users\Public\bridge\work\xiaopacai`（Android/Windows，main）、
  `...\xiaopacai-web`（Web，master）
- 主开发库在 50.53：`/home/winann/xiaopacai`、`/home/winann/xiaopacai-web`
- 关键提交（本地）：Android `0d511b5`；Web `73b1b0c`（含 4e9f574 中继 owner 绑定修复、
  900b8da SQLite 翻译、ebc48fe 静态文件）
- GitHub：winann-xu/xiaopacai、winann-xu/xiaopacai-web（公开）；**推送需用户明确指令**
- 交付产物：`C:\Users\Public\bridge\work\xiaopacai-tests\dist\`（APK 40.9MB / Windows zip / Web3 发布版）

## 测试环境（仍在运行）
- 模拟器：5554=儿童1(xiaopacai_test)、5556=家长(xiaopacai_parent)、5558=儿童2(xiaopacai_child2)
- Web 3.0 本机测试服务：`http://127.0.0.1:5000`（P2P 9527），admin/admin123
- 儿童↔家长直连：`adb -s emulator-5556 forward tcp:9528 tcp:9527`，儿童端连 `10.0.2.2:9528`
- 儿童↔Web：`10.0.2.2:9527`；Windows 家长端：`192.168.50.20:9527`（需先停 Web 抢 9527）
- 调试触发器：`DebugTriggerActivity`（action: start_service/seed*/collect/pair*/parent_* 等）

## 生产服务器（2026-08-12 新部署）
- **192.168.50.11**，Ubuntu 20.04，SSH `winann` / `w`（sudo 同密码）
- Web 3.0 生产：`http://192.168.50.11:5000`，admin/admin123（**待改密**）
- 部署目录 `/home/winann/xiaopacai-web`，systemd 服务 `xiaopacai-web`（自启）
- .NET 8.0.30 运行时 `~/.dotnet`；P2P 9527；SQLCipher 库已内置（e_sqlite3）
- 运维见 `docs/USER_MANUAL_WEB_PRODUCTION.md`

## 生产互联已实测（2026-08-12 上午）
- child1 ↔ 生产 Web(192.168.50.11:9527) 在线；owner 已绑定
- 家长端(Android) ↔ 生产 Web 中继（登录→register userId=1→relay P2P 会话），
  child usage_report 经生产实时转发到家长端（已实测）
- child2 ↔ Windows 家长端局域网直连（重配对后指纹更新，上报 4 条落 Windows DB）
- 注意：儿童端切换服务器需重新配对（指纹固定）；Windows 端无 Web 中继功能（局域网直连）
- 生产 admin 密码仍为 admin123，需尽快改密

## 已验收链路（全绿）
- Android：双角色、P2P TLS 配对（指纹）、策略下发、时长上报、超时 full/partial、
  公告直达/紧急置顶/回执、应用分类、守护状态、断线重连、守护自启
- 一拖二：双儿童并发、用量/上报/公告按设备隔离
- Windows：TLS 直连、策略/上报落库、心跳（设备列表计数不更新=发现项）
- Web：中继路由（child→Web→parent）、诊断上报/列表/导出、扫码登录、重置密码、
  静态托管、178/178 测试、30 分钟长稳

## 已知遗留（非阻塞）
1. Web P2P 未处理 diagnostics_report（儿童端经 Web 的诊断消息被丢弃）
2. relay/register 重复调用累积会话行（建议按 DeviceId+Role upsert）
3. Windows 端设备列表不显示 P2P 连接设备
4. appsettings Urls=127.0.0.1 覆盖 env；生产已用 Production 配置 0.0.0.0

## 协作通道
- 桥接信箱 `C:\Users\Public\bridge`（in=Codex→Claude，out=Claude→Codex，MAILBOX.log/flag）
- 直接调 Claude：`/home/winann/bin/claude-real -p "..." --output-format text`（50.53）
- 同步用 git bundle（上传 /home/winann/ + 写信），GitHub 推送需用户指令

## 用户手册
- Web 生产用户手册：`docs/USER_MANUAL_WEB_PRODUCTION.md`
- 全面验收测试提示词：`docs/PROMPT_TEST_VERIFICATION.md`（V1.1，含多端互联/隐私边界）

## 2026-08-12 深夜：实机反馈修复（扫码 + 权限）— 已本地提交 e2f62c3，未推 GitHub

### 问题 1：儿童端权限开启繁琐（4 项开了 10 分钟只开 2 项）
- 权限引导页重写为快捷版：`PermissionGuideScreen.kt`
  - “一键引导”：按顺序自动打开未授权项，从系统设置返回且新授权一项后自动跳下一项
  - 通知权限（Android 13+）直接弹系统授权框；电池优化优先 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 直接弹窗
  - 进度条 doneCount/4 + 可选第 5 项开机自启动（国产 ROM 深链）
  - 新增“ADB 一键授权”卡片：一键复制 5 条 adb 命令（usage/notification/battery/accessibility）
- 模拟器实测：一键引导 20 秒内完成 无障碍→通知→完成（电池已在白名单），全程自动跳转 ✓

### 问题 2：家长端无法扫码（Web 端/儿童端均扫不了）
- 根因 1：扫码结果回调未接 —— `launchQrScan`/`launchChildScan` 用 `context.startActivity` 直接启动，
  没有走 `rememberLauncherForActivityResult`，导致结果永远收不到。已全部改为 launcher 启动。
- 根因 2：`QrScannerActivity` YUV→NV21 转换的行数/步长逻辑错误，解码必然失败。已重写为
  标准逐行拷贝（处理 rowStride/pixelStride），修复 zxing 包路径。
- 新增能力：
  - 家长端设置页「扫码登录 Web」：扫 Web 登录二维码 → 从 ticketUrl 提取 ticket →
    `POST /api/auth/login-ticket/{ticket}/confirm`（需已保存 web_token）；顺带自动填入中继地址
  - 儿童端「我的二维码」（被扫）：内容 `{"type":"xiaopacai_child",deviceId,deviceName}`
  - 儿童端「扫描家长端」：识别 pairing/web_relay 二维码自动连接（`GuardianForegroundService.getP2PConnection().connect` 包在 scope.launch）
  - 家长端「扫码配对」：识别儿童二维码后展示设备信息并生成新配对码
- 调试构建新增：儿童/家长端「调试：模拟扫码」按钮（注入 test_result）+ 家长设置页
  「调试：生成真实 Ticket 模拟扫码登录」（先向 Web 创建真实 ticket 再注入）
- 新增 `clear_usage` 调试触发器 + `UsageRecordDao.deleteUsageRecordsForDate`（重置当日使用时长，供重头测试）

### 模拟器实测结果（5554/5556/5558，AVD 无真实相机故用注入 QR）
- 儿童端扫家长端 QR → 家长端显示「已连接 1 台设备」，儿童端显示「● 已连接到家长端」✓
- 家长端扫儿童端 QR → 弹窗「已识别儿童设备：测试儿童设备 + 配对码」✓
- 家长端扫码登录 Web（真实 ticket）→ 「扫码登录已确认 ✓ 网页端将自动登录」✓
- 儿童端「我的二维码」弹窗正常 ✓；权限一键引导全流程 ✓；单元测试 BUILD SUCCESSFUL ✓
- 新 APK 已上传生产：http://192.168.50.11:5000/downloads/XiaopacaiParent-1.0.0-debug.apk（HTTP 200）

### 备注
- 儿童端模拟器系统 UsageStats 残留 1498 分钟假数据（pm clear 不清系统统计），测试中若出现
  超时锁定，先 `am start -n com.xiaopacai.child/.debug.DebugTriggerActivity --es action clear_usage`
  再重启应用；策略限时若被家长端推送覆盖，需先在家长端把限额调大或断连。
- 「家长扫儿童端 = 全自动下发连接请求」尚未实现（当前为识别 + 新配对码，儿童端手动连接）；
  若用户要求双向自动连，需评估 Web 中继下发配对请求 + 儿童端轮询。

## 2026-08-13 上午：真机（OPPO PKV110 / Android 16）实测与相机修复 — 已提交 581b1ad

### 真机连接方式（无线调试）
- 手机 192.168.50.14，无线调试：配对端口/连接端口每次随机，用 `adb mdns services` 发现
  （`_adb-tls-pairing._tcp` 配对 / `_adb-tls-connect._tcp` 连接）
- `adb pair <ip>:<配对端口> <配对码>` → `adb connect <ip>:<连接端口>`

### 发现的真机问题与修复
1. **相机黑屏（严重）**：`QrScannerActivity.startCamera()` 在 Compose 首帧异步渲染时执行，
   `previewView` 尚未创建 → 静默 return → 相机从未启动（屏幕 93% 全黑、无任何 CameraX 日志）。
   修复：`ensureCamera()` 等待预览视图就绪（50ms×40 次自动重试）+ 权限就绪后再绑定 CameraX，
   失败弹 Toast 明确提示。日志可见「预览视图未就绪，重试 0 → 开始绑定相机 → 相机已绑定」。
2. **Web 账号登录按钮无反应（用户侧配置问题）**：手机 Web 服务地址为空 → 登录按钮实际禁用
   （Compose 禁用态不反映在 TextView enabled 属性上，易误判）。填入 192.168.50.11 后登录成功。

### 真机实测结果（全链路通过）
- 家长端扫电脑屏幕上显示的儿童端二维码（Edge kiosk 全屏 1200x1200）→
  「已识别儿童设备：TestChild-001 + 配对码」✓
- 家长端扫 Web 登录二维码（真实 ticket）→ 「扫码登录已确认 ✓ 网页端将自动登录」✓
  服务器日志：`[Auth] 扫码登录 Ticket 已确认: <ticket> by userId=1`
- 相机权限已授予；扫码器在真机正常打开、预览正常

### 生产下载已更新
- 修复版 APK（MD5 d10987947a6affedc1443e3b8a81636c）已上传
  http://192.168.50.11:5000/downloads/XiaopacaiParent-1.0.0-debug.apk

## 2026-08-13 上午：权限底层快速授权实测 + 引导页自动检测（已提交 6a3c670）

### 用户诉求
减少家长设定手机的门槛；探索更底层方式（如 ADB、重启进高权限模式等）。

### 真机（OPPO PKV110 / ColorOS / Android 16）验证结果
- 底层授权命令实测有效：
  - 使用情况：`adb shell appops set <pkg> GET_USAGE_STATS allow` ✓
  - 通知：`adb shell pm grant <pkg> android.permission.POST_NOTIFICATIONS` ✓
  - 电池优化：`adb shell dumpsys deviceidle whitelist +<pkg>` ✓
  - 无障碍：`settings put secure enabled_accessibility_services ...` + `accessibility_enabled 1` ✓
    **注意 ColorOS 坑**：应用被 force-stop 时系统会回退该设置；应用常驻时保持有效。
- 自启动（可选第 5 项）：ColorOS 的自启动管理（com.oplus.battery/com.oplus.startupapp...）
  受 OPLUS_COMPONENT_SAFE 签名权限保护，第三方应用无法直接拉起深链；已补充 OPLUS 变体尝试，
  失败回退应用详情页。AUTO_START appop 在该机型不存在。
- “重启进高权限”不可行：重启不会授予任何权限；且无线调试在重启后需重新开启。
  真正可行的低门槛路径 = 电脑 ADB 一条命令（30 秒）+ 引导页自动检测。

### 引导页改进（6a3c670）
- 每 2 秒自动刷新权限状态：ADB 外部授权后手机无需操作，自动识别并进入儿童端。
  实测：引导页 3/4 → 电脑执行无障碍授权命令 → 5 秒内自动进入儿童端主页 ✓
- ADB 一键授权卡置顶高亮，含 5 条已验证命令（新增 accessibility_enabled=1）。
- 新版 APK 已上传生产 downloads（MD5 29aee1b6ed717df3506944540efd7805）。

### 重启测试（未完成，待用户重新开启无线调试）
- 手机重启后无线调试未自动广播（ColorOS 需手动重开），等待用户提供新的 IP:端口/配对码。

### 重启测试（已完成，结果全绿）
- 重启后（无线调试重新开启，新端口 41513）：
  - 4 项权限全部保持：usage allow / accessibility 含小趴菜 / 通知 granted / 电池白名单 ✓
  - 应用自动拉起：GuardianAccessibilityService + GuardianForegroundService 均在运行 ✓
    （无障碍服务在开机时被系统绑定 → 自动拉起应用进程与守护服务，无需 ColorOS 自启动权限）
  - 打开应用直接进入儿童端主页（无权限引导）✓
- 结论：ColorOS 上“设置一次管永久”成立；自启动第 5 项为非必需（其他 OEM 仍建议开启）。

## 2026-08-13 下午：Web ↔ 儿童端 / 家长端 互联实测（1 台真机 + 模拟器家长端 + 生产 Web）

### 测试拓扑
- 儿童端：真机 OPPO PKV110（192.168.50.14，经无线调试控制）→ 直连生产 Web 192.168.50.11:9527
- 家长端：模拟器 5556 → 经宿主机端口转发（127.0.0.1:5001→50.11:5000、127.0.0.1:9527→50.11:9527）
  连接生产 Web（模拟器无法直连局域网，用 netsh portproxy + 10.0.2.2 隧道）

### 发现并修复的问题（Android 已提交 c9a6f9f）
1. **儿童端扫码 web_relay 二维码未传 isRelay=true**：服务器不会登记中继会话，
   消息无法路由到家长端 → 已在 handleQrScanResult 按二维码类型传 isRelay。
2. **家长端中继配对码是本地随机码（无效）**：改为先从 Web /api/pairing/generate-code 获取
   真实配对码 → 注册中继 → P2P 连接 → 展示配对码 + “生成二维码，儿童端扫码经 Web 连接”。
3. 新增调试动作：parent_relay（家长连中继）、pair_relay（儿童连中继，替代相机扫码验证）。

### 实测结果（全链路通过）
- 家长端：Web 登录 → 获取配对码 → /api/relay/register（会话 id=6, role=parent）→ P2P 握手成功，心跳稳定
- 儿童端：扫码（真机相机）/ pair_relay → TLS 到 50.11:9527 → handshake（relay=true+配对码）→
  设备 bc17b269-… 注册成功 → policy_update 下发 → 心跳稳定
- 补绑：家长端用同一配对码再次 register → boundDeviceId=1（儿童设备绑定到 admin 账号）
- **中继转发：儿童端 sync 20 条使用记录 → sync_ack → 家长端「收到消息: usage_report」✓**
- 稳定性：连接一次后 90 秒心跳全部 ack、无断连（此前日志里的“断开”是调试中反复调用
  pair_relay 主动 disconnect 造成，非缺陷）

### 备注
- 宿主机保留 portproxy（5001/9527 → 50.11），模拟器继续可用；真机直连无需隧道。
- 真机无障碍权限在 force-stop 时会被 ColorOS 回退（已记录）；补授权后引导页 2 秒自动进入主页。
- Web 管理端设备列表接口返回格式未适配（/api/admin/devices 非纯 JSON），不影响功能。

## 2026-08-13 下午：三个实机问题修复（Android 1061623 / Web a0c7ae0）

### 问题 1：Web 设置使用时间策略，真机超时后未生效
- 根因：超时判定依赖 UsageStatsCollector 每 5 分钟一次的采集，超时后最多延迟 5 分钟才锁定。
- 修复：采集间隔 5 分钟 → 1 分钟。真机实测：限额 100/已用 120 → 显示「已超时 + 部分应用停用」；
  模拟器 5554（限额 120/已用 1061）触发整机锁定。✅
- 注意：锁定模式（full/partial）由 Web 策略 overtimeAction 决定，用户可配置。

### 问题 2：儿童端应用分类设置进不去
- 排查：入口/Activity 注册正常，真机可打开并列出 381 个应用（首次加载需几秒，期间只有
  转圈容易误以为“打不开”）。
- 修复：加载中显示「正在加载应用列表…」+ 空状态提示（权限缺失时）。✅

### 问题 2 续（2026-08-13 傍晚）：应用分类加载 2 分钟优化 + 家长密码门槛（Android 0a534db）
- 性能根因：初始化时每个应用单独开/关一次 SQLCipher 加密库做查询和插入（381 应用 ≈ 760+ 次开库）。
- 优化：一次性读已入库包名 + 单事务批量插入缺失分类。首扫从约 2 分钟降到约 10 秒，二次进入近即时。
- 搜索：应用分类页新增搜索框，按应用名/包名过滤（实测 chrome → 匹配 1/223）。
- 安全：儿童端进入应用分类设置需先验证家长密码（错误密码提示“密码错误”）。
- 调试新增 set_parent_pwd（仅设密码不切换角色）。

### 问题 2 再续（2026-08-13 晚）：一键自动分类(11 类) + 安全加固（Android cfc1073）
- 新分类体系 CategoryTaxonomy：游戏/社交聊天/短视频直播/长视频影视/学习教育/浏览器/
  购物消费/音乐音频/阅读资讯/工具系统/其他，关键词规则覆盖常见中文+包名。
- 应用分类页新增「一键自动分类」：批量重算默认分类，保留家长手工设置（source=manual 跳过）。
- 性能：真机 378 应用批量分类约 1.3 秒。
- 引擎映射：细分类 → game/social/video/study/other（AppInterceptor 用 toEngineCategory），
  避免短视频/浏览器等新分类导致 partial 拦截失效回归。
- 安全：儿童端「设置」「权限管理」入口统一加家长密码验证（防进入系统页强行停止/卸载）。
  已确认真机设备管理员已激活（防卸载）；关于/守护状态无禁用守护的后门。
- 调试新增 auto_classify 动作（含常见应用抽查日志）。

## 2026-08-13 晚：儿童端离线根因与自动重连（Android d90adf1 / Web 6317c62）
- 现象：儿童端与服务器同局域网仍显示离线。
- 根因 A（Android）：连接目标只存内存，App/服务重启后不会自动重连 → 已持久化连接目标
  并在守护服务启动时自动重连。
- 根因 B（Web）：Web「解绑」把设备置为 revoked，握手对 revoked 永久拒绝 → 永远离线；
  已改为解绑=unpaired（软解绑），握手允许 revoked/unpaired 设备凭新的待确认配对码重新绑定。
- 实测：被吊销真机用新配对码重绑后 status=online + pairStatus=paired。

## 2026-08-13 晚：超时后停留在受限应用内不被拦截的绕过（Android bbc64ae）
- 现象（严重 BUG）：真机停在快手不切换，限额用尽后仍可一直刷。
- 根因：拦截只依赖 TYPE_WINDOW_STATE_CHANGED（前台切换事件），停在原应用内超时不会触发。
- 修复：无障碍服务新增每 5 秒前台巡检（活跃窗口→事件记录→UsageStatsManager 多级兜底），
  超时后即使不切应用也会周期性拉起拦截界面。
- 证据：修复前截图（快手 149k 色视频画面 + 超限 true + 无拦截日志）；
  修复后日志「拦截应用 + 巡检拦截 com.smile.gifmaker partial-video」，覆盖层出现。

## 2026-08-13 晚：Web 账号与扫码绑定完善（Web 5229471）

### 问题与设计
- parent 登录失败：Web 数据库只种了 admin，没有 parent 账号 → 补种默认 parent/parent123。
- 用户期望：邮箱唯一账号 + 绑定多个家长/儿童终端 + Web 显示家长二维码儿童扫码绑定 + 解绑需密码。

### 已实现
- 新增 POST /api/auth/register（邮箱+密码注册，注册即登录）；登录支持用户名或邮箱。
- 配对码新增 OwnerUserId 归属：生成配对码时记录账号，握手/验证时自动绑定设备到该家长账号。
- 新增 POST /api/pairing/binding-qr：Web 家长登录后生成 web_relay 绑定二维码（host+9527+配对码）。
- 设备列表按账号隔离：家长只看自己设备，管理员看全部。
- 解绑：/api/devices/* 已在 ParentOrAdmin 鉴权下（登录即密码门槛）。
- 前端：登录页新增注册表单+邮箱登录；设备页新增“扫码绑定儿童端”二维码弹窗。

### 验证（生产）
- parent/parent123 登录 ✓；parent@xiaopacai.local 邮箱登录 ✓；邮箱注册 ✓。
- binding-qr 返回 {host:192.168.50.11, port:9527, qrContent:web_relay...} ✓。

## 2026-08-13 晚：儿童端限额不生效（快手仍可刷）修复（Android de8d7db）
- 现象：限额用尽（261/250，超限 true，partial 模式），但快手仍可正常使用。
- 根因：AppInterceptor.getAppCategory 用写死的包名关键词判断分类，快手包名
  com.smile.gifmaker 不命中任何规则 → other → partial 模式不拦截。
- 修复：拦截引擎分类改读 app_category 表（一键自动分类/家长微调结果），缺失回退
  CategoryTaxonomy；并补 gifmaker/gifshow/smile.gif 等快手包名。
- 实测：打开快手 → GuardianA11y 拦截 com.smile.gifmaker，原因 partial-video，覆盖层显示
  「应用已停用 261/250 分钟」✓
- 注意：partial 模式只拦 game/social/video（含 short_video），其他/学习放行，符合设计。

### 问题 3：儿童端公告未显示最近三条
- 根因 A：公告实时推送只在儿童端在线时生效；离线期间发布的公告，儿童端重连后永远收不到
  （心跳的 1 小时待办窗口儿童端未消费）。
- 修复 A：Web 在儿童端握手成功时补推最近 3 条已发布/已撤回公告（action=sync）。
  实测：离线模拟器 5554 重连后收到 announcement_push，紧急公告弹全屏覆盖层。✅
- 根因 B：已过期的公告（validUntil 到期）会被列表过滤——属正常行为
  （实测「回家写作业」设了 14:30 过期，过期后不再显示）。
