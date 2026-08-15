# ADR 0013 — 里程碑 V3 需求 10+11：家长端策略/公告/报告与 Web 双向同步

- 日期：2026-08-15
- 状态：已采纳（TASK-MILESTONE-V3）
- 范围：Android 家长端为主（消费既有服务端接口，服务端无代码改动）

## 背景

Android 家长端主页的策略/公告/报告三个 Tab 此前完全本地化：策略存 `parent_policies` 表
（全局模板语义，无设备维度）、公告存 `parent_announcements`（本地 UUID 自建）、报告读
`parent_usage_summary`（LAN P2P 上报口径）。与 Web 端（服务端策略表/公告 API/报告聚合）
互不相通——任意端修改，另一端不可见，报告口径也不一致（本地累计 vs 服务端原始累计+重置偏移）。

需求 10/11（决策 D5）：双向同步、服务端为权威、离线本地缓存可看、报告口径与 Web 完全一致。

## 决策与实现

### 1. 服务端为权威，本地表降级为缓存/镜像

- 新增 `ParentCloudSync`（`util/`）统一同步层：设备列表 / 策略 GET+PUT / 公告 CRUD+发布撤回 /
  报告 daily+weekly+export，全部复用 Web 同源 API；JWT 来自 CloudAccountManager；
- 本地数据角色：
  - `parent_announcements` → 服务端公告镜像（`web-<serverId>` 前缀，`replaceAllAnnouncements` 全量覆盖）；
  - `parent_policies` → 仅保存成功后写入的 LAN 下发镜像（见第 5 条）；
  - 设备列表 / 单设备策略 / 报告快照 → SharedPreferences 快照缓存（离线展示用）。

### 2. 策略按设备 + A2 乐观并发（409 冲突采纳）

- 策略 Tab 新增设备选择器（数据源 GET /api/devices，账号隔离）；
- 编辑基于 GET 返回的完整 DTO，PUT 携带 `expectedVersion`：
  - 409 → 采纳错误体携带的服务端最新策略并提示「策略已被其他端修改，请确认后重新保存」；
- **白名单/黑名单必须原样回传**：服务端 PUT 为整体覆盖语义（未传即清空），
  仅修改每日限额/就寝时段/超时动作三个 UI 字段，其余字段从 GET 的 DTO 原样带回；
- 超时动作映射：UI（full/partial/none）↔ 服务端（full_lock/partial_lock/warn_only）。

### 3. 公告全量镜像 + 变更全走服务端

- 在线进入即 GET /api/announcements 全量覆盖本地镜像（B13 账号隔离，家长仅见自己创建的公告）；
- 新建/编辑/发布/撤回/删除全部调用服务端 API，成功后重拉列表（服务端权威，杜绝本地漂移）；
- 撤回后编辑：服务端保持 revoked 状态，UI 对 revoked 公告同样显示「发布」按钮
  （服务端发布接口不限前置状态，替代旧本地逻辑「编辑后回到草稿」）；
- **存量 bug 修复**：`replaceAllAnnouncements` 此前用 `optInt` 读服务端字符串型 priority
  （恒 0），紧急/重要公告降级为普通——改为显式字符串映射。

### 4. 报告口径与 Web 完全一致

- 今天 → `GET /api/reports/daily`；7 天 → `/api/reports/weekly`；
  30 天 → `/api/reports/export?format=json`（逐日聚合：总时长求和、分类按 key 合并重算占比）；
- 总使用时长为**原始累计口径**（含重置前用量），UI 明示「原始累计口径，与 Web 一致」；
- 新增「今日已用（调整后）」卡片：数据来自设备列表
  `todayUsageMinutes/rawTodayUsageMinutes/lastResetOffsetMinutes`（与 Web 设备页同源同口径），
  有重置偏移时展示偏移量与原始累计；
- 分类展示名与占比由服务端计算下发（ReportAggregator），Android 不再本地命名。

### 5. LAN 直连设备通道保留（与服务端推送互补）

- 策略保存成功后写本地镜像 `replacePoliciesForDevice`（按 policyType 级替换：
  清除同类型历史全局行 `target_device_id=''` 与本设备旧行，写 `target_device_id=<child deviceId>`），
  儿童端 LAN 握手下发沿用；
- 公告发布成功后向 LAN 直连设备补充推送，id 使用服务端公告 id（与中继推送一致，
  终端按 id 去重），紧急公告 `requiresAck=true`；
- **已知边界**：纯 LAN 设备（从未连接服务端中继、不在 /api/devices 列表）无法按设备编辑
  策略——服务端权威模型下无其策略行；此类设备仍可通过握手下发收到镜像策略。

### 6. 离线语义（沿用总体原则 3）

- 网络不可达（CloudConnectionException）→ 读取快照缓存只读展示，标注「离线数据」；
- 策略/公告离线**禁改**（服务端权威，避免离线编辑产生冲突）；报告离线展示最近一次快照；
- 恢复联网后手动「刷新」或重进页面即重新拉取；新账号绑定全量覆盖由需求 3/4 的
  `LocalDataWipe` 清除后首次在线拉取完成。

### 7. 删除未接线的旧独立页面

- `ParentPolicyScreen` / `ParentAnnouncementScreen` / `ParentReportScreen` 三个独立页面
  从未被任何导航引用（功能实现在主页 Tab 内联），本次功能重写后成为重复死代码，
  一并删除，避免与主页 Tab 行为分叉。

## 验收注意（写给 Codex）

- 真机双端互改：Web 改策略 → Android 策略页刷新可见；Android 改 → Web 刷新可见，
  Android 保存后儿童端收到新策略并生效；
- 并发冲突：Web 与 Android 同时改同一设备策略，后保存方收到 409 提示并加载最新版本；
- 公告：Android 发布公告 → Web 列表可见、儿童端收到（含紧急全屏确认）；Web 删除 →
  Android 刷新后本地镜像同步消失；
- 报告：Android 今日/7 天/30 天数字与 Web 报告页一致；设备页「今日已用」与 Android
  「今日已用（调整后）」一致；断网进入三页 → 显示「离线数据」+ 缓存，策略保存被禁用；
- 单元测试：`ParentCloudSyncTest`（映射与三种周期归一化 11 例）。
