# ADR 0010 — 里程碑 V3 需求 2：策略下发与家长公告场景 A/B 决策落地

- 日期：2026-08-15
- 状态：已采纳（TASK-MILESTONE-V3）
- 依据：134 信《TASK-MILESTONE-V3 标准提示词》D1 决策汇总（产品负责人已确认）

## 背景

需求 2 要求对策略下发与家长公告两类场景清单逐项处置。清单已随
`docs/PROMPT_MILESTONE_V3.md` 附录交付并获产品负责人逐项决策，
本 ADR 记录决策落地方式与实现位置。

## 决策与实现

### A2 多端冲突：服务端版本/时间戳校验（服务端权威）

- `policies` 表新增 `Version` 列（默认 1，每次 PUT 保存 +1；既有库启动迁移补列）。
- `PUT /api/policies/{deviceId}` 接受可选 `expectedVersion`：
  - 携带且与当前版本不符 → `409` + 服务端最新策略（`{ error, policy }`）；
  - 未携带 → 兼容旧 Web 页面，直接保存。
- `GET` 返回 `version`；Web 前端（Pinia policies store）保存时回传 `expectedVersion`，
  409 时采纳服务端最新版并提示用户确认后重存（不静默覆盖）。
- 儿童端 `SyncManager.handlePolicyUpdate` 增加客户端版本防线：同 policyType
  旧版本不覆盖本地缓存（防重复帧/乱序帧回退）。

### A12 解绑清理：硬删除设备行及全部关联数据（配合 D2 全清）

- `DELETE /api/devices/{id}` 由软解绑改为硬删除（保留 X-Action-Token 密码二次验证）：
  依次删除 announcement_deliveries / policies / usage_records / daily_summaries /
  pairing_info / relay_sessions（按 device_id 字符串）/ diagnostics，最后删除设备行。
- 重绑走全新设备身份：儿童端 device_id 一并重置（需求 4 客户端侧实施）。

### B2 / B10 推送后 60 秒未 displayed → 补偿重推

- 新增 `AnnouncementCompensationService`（HostedService，30 秒扫描）：
  - 候选：15 分钟窗口内发布且已过 60 秒宽限期的 published 公告；
  - 目标：送达行 `displayed_at IS NULL AND compensated_at IS NULL` 且设备在线
    （P2P 会话存在）且归属发布者账号（B11 同口径）；
  - 动作：重发 `announcement_push`（action=publish），成功后写 `compensated_at` 打标
    （幂等：每设备最多补偿一次；终端版本+哈希去重兜底）。
- `announcement_deliveries` 新增 `CompensatedAt` 列（启动迁移补列）。
- 离线设备不补（会话不可达），由 B6 重连同步覆盖。

### B5 删除公告：新增“清除本地公告”指令

- 新 P2P 消息类型 `announcement_clear`，payload `announcementIds: [id]`：
  - 实时：删除公告时向发布者账号设备（定向公告仅目标设备）推送；
- 离线：新增 `announcement_tombstones` 表（AnnouncementId 主键 + CreatedBy + DeletedAt），
  删除时落墓碑；重连同步帧携带 7 天内 `cleared_ids`；墓碑 7 天到期由补偿服务顺带清理。
- 儿童端 `AnnouncementDao.deleteByIds` 批量删除本地记录（撤回只置过期，删除彻底移除）。

### B6 离线发布：紧急未确认公告重连必补推

- `BuildAnnouncementSyncJson` 在“最近 3 条”基础上追加：所有 published+urgent
  且本设备无 acknowledged 回执的公告（合并去重），不限于 1 小时窗口。

### B8 去重修复：紧急未确认公告重连必须重新全屏

- 儿童端 `SyncManager.handleAnnouncementPush`：紧急公告（priority>=2）只要未确认
  即重新全屏展示，无视 upsert=unchanged 去重（非紧急公告维持原去重语义）。

### B11 多设备广播账号隔离（隐私修复）

- `PushAnnouncement` 广播由“全部在线设备”改为“发布者账号下的在线设备”
  （devices.owner_user_id 归属，兼容用户 ID/用户名两种历史格式）。
- `BuildAnnouncementSyncJson`、`HandleHeartbeat` 的待发公告判断、补偿服务
  均按同口径过滤；无归属设备仅收定向公告。

### B13 公告归属账号

- 公告归创建者账号（announcements.CreatedBy）：
  - `GET /api/announcements` 家长仅见自己创建的公告（admin 全部）；
  - `GET /{id}`、`GET /{id}/deliveries` 补归属校验（此前任意家长可读任意公告 id，
    属越权读取，一并修复）；
  - `GET /urgent-stats` 家长口径 = 自己账号的紧急公告 × 自己账号的激活设备。

### 维持不变（决策确认）

- A1 默认策略 120 分钟 / full_lock；A5 账号级模板本期不做；
- B3 已发布公告不可编辑（需先撤回）；B14 定向公告 UI 不开放、后端保留。

## 安全考量

- 所有账号隔离改动均以服务端过滤为准（客户端只做展示优化），越权路径返回 403/空集；
- 删除/解绑链路维持既有审计与密码二次验证（X-Action-Token）不变；
- 补偿重推不携带任何新权限面，仅重发已有公告帧（终端去重幂等）。

## 测试

- Android 单元测试全量通过（115+，含 132 信登录文案细分用例）；
- 服务端 C# 无本地 dotnet 环境，交由 Codex `dotnet test` + 全量回归验收；
- 真机验证点：跨账号广播隔离、删除公告后儿童端本地清除、紧急未确认重连再全屏、
  60 秒补偿重推（Web 送达明细 push_count/compensated_at 可查）。
