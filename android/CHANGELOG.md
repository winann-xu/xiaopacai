# 变更日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。规范见双仓库统一文档 `docs/VERSIONING.md`。

---

## [1.2.0] — 2026-08-23（[TASK-APP-UPDATE-V1]，交付）

> 自动更新闭环首版（ADR 0017）：服务端发布即 P2P 推送，客户端检查/下载/校验/安装全链路；
> versionCode 10200，minVersionCode=10200（1.1.x 全量强制）。升级不触碰任何本地数据。

### 新增
- **应用自动更新**（UpdateManager）
  - 检查触发：家长端启动静默检查 / 服务端 `update_available` P2P 推送 / 家长端「关于-更新软件」手动检查
  - 强制/可选判定（minVersionCode 阈值）+ 频控（可选每版本每日一次；强制每次提示）+ 跳过此版本
  - 自动下载开关（默认关；允许移动网络下载，D3 产品决策）；私有目录下载 + SHA-256 校验（失败拒绝并删除）
  - PackageInstaller session 安装 + ACTION_VIEW 兜底 + 未知来源权限引导；安装结果广播回调
  - 通知渠道 channel_updates + 进度/完成/失败通知；儿童端收到推送不弹窗、守护不打断
  - 家长端关于页「更新软件」按钮（检查失败兜底官网 xpc.winann.com）
  - Manifest：REQUEST_INSTALL_PACKAGES + FileProvider + InstallResultReceiver；res/xml/file_paths.xml 最小暴露面
- 单测 160 → 178（UpdateLogicTest 18 例：解析/频控/跳过/防降级/SHA-256/下载校验）

### 包含热修复（1.1.2–1.1.6）
- P2P 连接（Android 8.0 TLS 1.2 回退 + socket 泄漏修复）
- 今日倒计时卡分钟 / 冻结 00:29:00（估算延续 + 权威采集判定）
- 数据库并发关闭共享 SQLCipher 连接导致守护失效（移除 67 处 db.close()）

---

## [1.1.1] — 2026-08-15（[TASK-HARDENING-V1.1.1]，交付）

## [1.1.1] — 2026-08-15（[TASK-HARDENING-V1.1.1]，交付）

> V1.1.1 加固版：修复 OPPO 真机回归 4 项 P0 缺陷（140 信任务书）。版本由 tag v1.1.1
> 推导：versionName 1.1.1，versionCode 10101。架构裁决见 ADR 0016。

### 修复
- **Bug1 上滑结束小趴菜后管控失效（OPPO 真机）**
  - 1-A 两个守护服务补 `android:stopWithTask="false"`（上滑最近任务不再销毁服务）+ Manifest 回归单测
  - 1-B ColorOS（OPPO/一加/真我）保活四项引导：自启动管理 / 后台冻结耗电管理（厂商组件逐个尝试+兜底应用详情）/ 电池白名单 / 最近任务锁定（步骤弹窗）；系统无检测接口如实标注「引导项」
  - 1-C Device Owner 仅检测展示、不落地 DPC 激活（docs/DEVICE_OWNER.md）
  - 1-D 失守监控全链路：GuardDownMonitor 本地持久化（cap 100）+ P2P guard_event（含健康度快照，离线队列 SyncManager 冲刷）+ 家长端落盘/高优通知（id 4001）/云端转传；恢复后立即重拦截并通知家长
- **Bug2 儿童端倒计时不更新**：每秒本地倒计时 HH:MM:SS（剩余 = 今日限额 − 最近已用 − 交互增量，熄屏不虚减）；归零立即锁定（双保险消除 ≤60s 空窗）；权限/采集失效如实显示「守护失效」不假倒计时
- **Bug3 日志未上传 + Web 查日志报错**：登录/绑定成功立即上传；失败 5/15/60 分钟指数退避（6 小时周期兜底保留）；日志页展示上次成功时间/失败原因/重试计划
- **Bug4 无障碍权限丢失无感知**：亮屏/解锁/应用更新/回前台事件触发即时自检（30 秒节流）+ 每分钟轮询兜底；发现被关立即高优通知 + 一键直达无障碍设置；文案如实区分管控曾生效/未生效

### 新增
- 家长端守护状态页：守护健康度（score/100、6 项权限真实勾叉、Device Owner 检测、ColorOS 引导说明）+ 失守历史（开始/恢复+时长），无数据如实显示「待上报」
- 单测 137 → 154（HardeningManifest +7、CountdownLogic +7、LogUploaderRetry +3）

---

## [1.1.0] — 2026-08-15（[TASK-MILESTONE-V3]，交付）

> 里程碑 V3 = 1.1.0，交付时打 tag v1.1.0；versionName 构建时读 Git tag，
> versionCode = major×10000 + minor×100 + patch（v1.1.0 → 10100，保证覆盖存量 versionCode=1）。

### 新增
- 需求 1：Git 版本联动 — versionName/versionCode 构建时自动读取 Git tag（无 tag 为 dev-短哈希 + versionCode 兜底 1），BuildConfig 注入 VERSION_NAME/VERSION_CODE/GIT_COMMIT/BUILD_TIME
- 需求 7：统一「关于」组件 AboutContent（动态年份、Git 版本号、官网 xpc.winann.com 可点击打开，家长端/儿童端共用）
- 需求 2：策略下发与家长公告场景 A/B 决策（ADR 0010，与 Web 同版本联动）
  - A2 策略客户端版本防线：同 policyType 旧版本不覆盖本地缓存
  - B5 公告删除：处理 `announcement_clear` 指令与同步帧 `cleared_ids`，本地记录批量删除（AnnouncementDao.deleteByIds）
  - B8 去重修复：紧急未确认公告重连补推时无视 upsert=unchanged 重新全屏
  - 133 修复：公告批处理单条异常不再中断整批（displayed 回执不丢）
- 132 信登录页优化：
  - 移除「测试期允许 HTTP」开关与 allowHttpOverride（公网仅 HTTPS，局域网 HTTP 回退由 CloudHttp 自动处理）
  - 登录失败文案细分三类：无网络 / 无法连接服务器 / 服务器未启用 HTTPS（CloudConnectionException 分类）
  - 未配置服务器地址时预填 xpc.winann.com:443
- 需求 13：账号角色（user.role）保存与读取，Web 云端中继设置仅 admin 可见
- 需求 3：新旧账号登录提醒（ADR 0011）
  - 家长端：登录新账号检测到旧绑定邮箱 → 确认框（列出清除范围 + 旧账号密码验证）→ 清除后继续登录
  - 儿童端：三条配对入口（扫码/发现/手动 IP）统一把关，本地业务数据残留时弹确认框，确认后清除再绑定
- 需求 4：解绑重绑全清（D2：device_id 一并重置，重绑全新设备身份；ADR 0011）
  - 新增 `LocalDataWipe`：儿童端+家长端业务表全清（audit 表保留）、Web 凭据、中继配置、设备身份
  - 换账号清理增加服务端本机设备同步解绑（verify-password 操作令牌 + DELETE /api/devices，尽力而为）
  - 清除后三处核对：数据库行数=0 / 配置文件键不存在 / UI 回到未绑定（设置页展示核对清单）
- 需求 10：家长端策略/公告与 Web 双向同步（ADR 0013，D5 服务端权威；服务端复用既有接口，无改动）
  - 新增 `ParentCloudSync` 云端同步层：设备列表 / 策略 GET+PUT / 公告 CRUD+发布撤回 / 报告拉取
  - 策略：按设备配置（服务端账号设备列表），PUT 携带 expectedVersion（A2 乐观并发），
    409 冲突采纳服务端最新并提示；白名单/黑名单自 GET 的 DTO 原样回传（服务端整体覆盖语义）
  - 公告：在线全量镜像本地表（web- 前缀），新建/编辑/发布/撤回/删除全走服务端；
    撤回后可直接再发布（服务端发布不限前置状态）
  - LAN 直连通道保留：策略保存后写本地镜像（replacePoliciesForDevice，type 级替换），
    公告发布后补充 LAN 推送（id 与服务端一致去重，紧急 requiresAck）
  - 离线：快照缓存只读 + 「离线数据」标注，策略/公告离线禁改，联网刷新即同步
- 需求 11：报告与 Web 同步（ADR 0013，口径完全一致）
  - 今天=日报 / 7 天=周报 / 30 天=导出聚合；总时长为原始累计口径（UI 明示与 Web 一致）
  - 新增「今日已用（调整后）」卡片：设备列表合计（调整后已用/限额/剩余/重置偏移，
    与 Web 设备页同源同口径）
  - 分类展示名与占比由服务端计算下发；离线展示快照缓存并标注「离线数据」
- 需求 14：家长端设置新增「日志」菜单 + 自动上传 Web（ADR 0014）
  - 新增 `AppLog` 统一应用日志：内存环形缓冲 5000 条上限 + 文件 JSONL 追加
    （超过 4MB 重写保留最新，磁盘上限 5MB），消息截断 4000 字符，损坏行容错
  - 崩溃兜底：UncaughtExceptionHandler 经 `eCrash` 同步写入环形缓冲（可随日志上传定位）
  - 日志页 ParentLogScreen：时间（毫秒）/级别色标/模块/内容滚动查看，复制全部、清空（确认框）、
    上传云端（未绑定账号置灰），展示缓冲上限与上次上传时间
  - 脱敏：`AppLog.maskSecrets` 写入即打码（密码/令牌/API Key、验证码、JWT、64 位 hex；
    裸 code: 不打码避免误伤 HTTP code），与 Web 服务端规则完全一致
  - 自动上传：WorkManager 每 6 小时（KEEP，初始延迟 10 分钟）+ 手动按钮；
    增量上传（lastTs 快照，500 条/批循环，全部成功才推进）；未绑定 Web 账号自动跳过
  - 关键链路埋点：应用启动、云端登录成功/失败、设备列表/策略/公告同步、上传结果
- 需求 15：全端 UI 走查与修复（ADR 0015，3 路并行走查裁决，P0×1 / P1×4 / P2×30 全修复）
  - P0：公告全屏页长文案/大字体下「知道了」按钮被顶出屏幕（儿童无法关闭公告）→ 内容区滚动 + 按钮固定底部（AnnouncementOverlay/BlockOverlay 同模式修复）
  - P1 安全/功能：设置页硬编码 http 内网地址 → 读取已存 host 按内网/公网自动 http/https；解绑断开 P2P 连接；家长守护状态页虚假状态 → 如实「待上报」；登录页端口非法崩溃 + 弹窗取消卡死
  - P2 一致性/无障碍 30 项：深浅色统一跟随系统、列表稳定 key 防崩溃、硬编码字号改 typography、色值适配深色模式、读屏语义（contentDescription/图标替代 emoji）、空态/加载态/错误态补齐、Toast 操作反馈、输入校验（端口/时间格式）、离线禁用编辑控件、超长文本截断、Role.Link 回退（Compose 1.5 无此 API，记债待 BOM 升级）

### 移除
- 需求 6：初始化/登录流程中的 ADB/运行命令提示（PermissionGuideScreen 电脑 ADB 一键授权卡片）
- 需求 8：家长端「电脑 ADB 一条命令快速指南」入口（ParentAdbGuideScreen 删除）
- 需求 9：家长端分类限额 UI（入口隐藏，保存强制 -1 不限；后端逻辑保留）
- 需求 10：未接线的三个旧独立页面（ParentPolicyScreen / ParentAnnouncementScreen /
  ParentReportScreen，功能已并入主页 Tab，删除避免行为分叉）

### 修复
- 关于页年份写死 2024 → 动态年份
- 登录失败文案笼统 → 按原因细分，家长可自查网络/地址/HTTPS 配置
- 需求 10：公告本地镜像 priority 解析——服务端字符串（normal/important/urgent）被
  optInt 恒读为 0，紧急/重要公告降级为普通，改为显式映射
- 需求 5：上滑结束小趴菜后管控失效（ADR 0012）
  - GuardianAlarmReceiver 补 AndroidManifest 声明（此前漏注册，AlarmManager 兜底链路静默失效）
  - onTaskRemoved 抢先注册 5 秒系统侧恢复闹钟（不随进程消亡），恢复后立即重放采集重新拦截
  - 心跳打标 + 开机时刻区分重启/被杀：进程被杀检测并通知家长（安全频道）
  - 管控生效标记 enforcement_active：重启即快速重放（不等 30 秒初始延迟）
  - 权限引导页 + OEM_KEEPALIVE.md 增补能力边界如实说明（强制停止无法自恢复属平台限制）

---

## 格式说明

`[版本号]` — 发布日期，格式 YYYY-MM-DD。分类：新增 / 变更 / 修复 / 移除。
