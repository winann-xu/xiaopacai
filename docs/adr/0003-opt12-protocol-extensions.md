# ADR-0003：12 项优化 P1 — 协议与数据模型扩展

- **状态**：已采纳
- **日期**：2026-08-11
- **决策者**：Claude@50.53（主开发）
- **关联任务**：[TASK-OPT-12-P1]

## 背景

12 项优化升级（`docs/PROMPT_OPTIMIZATION_12.md`）中，需求 1（应用分类管理）、需求 4（公告直接显示 + 紧急通知置顶确认）、需求 5（故障诊断上报）、需求 7（部分 APP 停用）均需要先扩展 P2P 协议与本地数据模型，才能支撑后续各端功能开发。本 ADR 记录 Android 侧（儿童端 + 家长端同 APK 双角色）P1 阶段落地的协议与数据模型扩展。

## 决策

### 1. 新增消息类型：`diagnostics_report`

- **方向**：儿童端 → 家长端
- **用途**：故障诊断信息上报（需求 5）。儿童端定期/异常时上报，供排障与后续 Web 3.0 诊断收集模块使用。
- **消息格式**：
  ```json
  {
    "type": "diagnostics_report",
    "deviceId": "XP-XXXX",
    "diagnostics": {
      "appVersion": "0.1.0",
      "androidVersion": "Android 14",
      "deviceModel": "Pixel 8",
      "manufacturer": "Google",
      "permissionStatus": {},
      "serviceStatus": {},
      "recentCrashes": [],
      "p2pHistory": {},
      "dbSizeBytes": 102400,
      "networkType": "wifi"
    }
  }
  ```
- **实现**：`P2PConnectionService.MessageType` 新增 `DIAGNOSTICS_REPORT` 常量；`ParentP2PListenerService` 新增 `handleDiagnosticsReport` 分支（P1 仅解析 + 日志，落库/转发由 P3 家长端、P4 Web 3.0 实现）。
- **无响应消息**：协议不定义 ack，儿童端按连接状态（断线重连）补传。

### 2. 新增消息类型：`announcement_ack`

- **方向**：儿童端 → 家长端
- **用途**：紧急公告（requires_ack=true）儿童确认后的回执上报（需求 4），家长端可追踪"是否已确认"。
- **消息格式**：
  ```json
  {
    "type": "announcement_ack",
    "announcementId": "ann-xxxx",
    "deviceId": "XP-XXXX",
    "acknowledgedAt": 1723276800
  }
  ```
- **实现**：`ParentP2PListenerService` 新增 `handleAnnouncementAck` 分支；儿童端 `AnnouncementDao` 新增 `markAcknowledged()` 记录确认时间。

### 3. 策略扩展：daily_limit 新增 `restrictMode` 字段

- **用途**：需求 7（partial_lock）。超时后处理方式由固定的"全部停用"扩展为三种模式，与 Web 端 `OvertimeAction` 对齐。
- **取值**：`full`（全部停用，默认）/ `partial`（仅停用部分应用，黑名单优先，其次非白名单）/ `warn`（仅警告不拦截）。
- **实现**：`PolicyConfig.kt` 新增 `restrictMode` 字段（默认 `"full"`），`toJson()`（daily_limit 时输出）/`fromJson()`（缺省回退 `full`）均处理该字段，旧策略数据无字段时按 `full` 解析，行为不变。
- **兼容**：老版本家长端下发的不含 `restrictMode` 的策略，儿童端默认按 `full` 执行，不改变现有行为。

### 4. 公告模型扩展：`requires_ack` / `acknowledged_at`

- **用途**：需求 4。公告可标记为"需儿童确认"（紧急公告全屏置顶），并记录确认回执时间。
- **协议**：`announcement_push` 内公告 JSON 新增 `requires_ack`（布尔）与 `acknowledged_at`（Unix 秒时间戳）字段；`ParentP2PListenerService.sendAnnouncementToDevice` 增加 `requiresAck` 参数并序列化进消息。
- **存储**：儿童端 `announcements` 表新增两列（见下文 schema 变更）；`AnnouncementDao.upsert` 增加可选参数（默认值保持旧行为），`getAllActive` 返回结果追加两字段。
- **兼容**：旧版家长端推送的公告 JSON 无 `requires_ack` 时按 `false` 解析（普通公告，不弹确认），行为不变。

### 5. 数据库 Schema 变更（V2 → V3）

- **新增 `app_category` 表**（需求 1，应用分类可管理）：
  ```sql
  CREATE TABLE IF NOT EXISTS app_category (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      package_name TEXT NOT NULL,
      app_name TEXT NOT NULL DEFAULT '',
      category TEXT NOT NULL DEFAULT 'other',  -- game/social/video/learning/other
      source TEXT NOT NULL DEFAULT 'default',   -- default/manual
      updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
      UNIQUE(package_name)
  )
  ```
  说明：分类口径统一为 `game/social/video/learning/other`（需求 1 第 4 条），采集器的旧 `study` 值后续（P2）映射为 `learning`。
- **`announcements` 表新增列**：
  ```sql
  ALTER TABLE announcements ADD COLUMN requires_ack INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE announcements ADD COLUMN acknowledged_at INTEGER NOT NULL DEFAULT 0;
  ```
  迁移通过 `PRAGMA table_info` 判断列是否存在（SQLite 不支持 `ADD COLUMN IF NOT EXISTS`）；新建库时列已内建于 `CREATE TABLE`。
- **版本号**：`DATABASE_VERSION` 2 → 3；`onCreate`（V3 全新库）与 `onUpgrade`（V2→V3 老库迁移）均执行 `createV3Tables()`。

## 替代方案与拒绝理由

| 替代方案 | 拒绝理由 |
|---------|---------|
| 诊断上报走 HTTP | 项目约束"数据不上云"，且 P2P 链路已存在，复用成本最低 |
| restrictMode 复用旧 `stopMode` 字段 | 语义不同（stopMode 是状态、restrictMode 是策略），混用会破坏 Windows/Web 既有实现 |
| 公告确认状态仅存内存 | 重启丢失，家长端无法追溯"未确认"公告 |
| V3 迁移一次性 ALTER 全部列 | SQLite 老版本无 `ADD COLUMN IF NOT EXISTS`，需逐列判断保证幂等 |

## 后果

### 优势
- 四类扩展（分类管理、公告确认、诊断上报、部分停用）的协议与数据层已就绪，P2 之后各端可直接消费。
- 全部为增量扩展（新消息类型分支、新字段、新表/列），不修改既有消息处理与业务逻辑。

### 风险
- 老版本儿童端/家长端收到新消息类型会在 `else` 分支打日志后忽略（现有代码已兜底），不影响连接。
- 老版本儿童端忽略 `restrictMode` / `requires_ack` 字段，新策略/公告在老端上表现为旧行为（full / 普通公告），需在发布节奏上保证家长端先升级。
- 分类口径 `learning` 与旧数据 `study` 并存，需 P2 采集器映射收敛。

### 缓解措施
- 所有新字段均带默认值（`full` / `false` / `0`），缺省即旧行为。
- 消息类型表已在 `docs/p2p/PROTOCOL.md` 登记，Windows/Web 端按本 ADR 对齐实现。

---

*本 ADR 为 P1 阶段 Android 侧协议与数据模型扩展记录。协议明细见 `docs/p2p/PROTOCOL.md`。*
