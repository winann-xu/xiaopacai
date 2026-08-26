# ADR 0011 — 里程碑 V3 需求 3/4：旧账号提醒与解绑重绑全清

- 日期：2026-08-15
- 状态：已采纳（TASK-MILESTONE-V3）
- 依据：134 信《TASK-MILESTONE-V3 标准提示词》需求 3/4 + 决策点 D2（解绑后全清，device_id 一并清除）

## 背景

- 需求 3：新旧账号登录提醒——检测到本地旧账号残留（家长端：旧邮箱/JWT/服务器地址；
  儿童端：旧绑定信息/本地业务数据）时，新账号登录/绑定必须弹确认提醒，文案列出将清除的内容。
- 需求 4：解绑/换绑后旧账号相关数据全部清除（公告/策略/分类/使用记录/报告缓存/家长端凭据/
  中继配置/本地缓存），清除需家长账号密码验证（沿用现状）；D2：device_id 一并重置；
  提供清除后干净状态验证（数据库/本地文件/UI 三处核对）。

## 决策与实现

### 服务端（web，需求 2 已随 A12 落地）

- `DELETE /api/devices/{id}` 硬删除设备行及全部关联数据（announcement_deliveries / policies /
  usage_records / daily_summaries / pairing_info / relay_sessions / diagnostics），
  审计 detail 打 `wipe:"hard_delete"`；X-Action-Token 密码二次验证保留（ADR 0010）。
- 需求 4 的“三处核对”为客户端概念；服务端由硬删除 + 审计记录保证可核对。

### 客户端（android）

- 新增 `util/LocalDataWipe.kt`（单点全清）：
  - 儿童端表：announcements / policy_cache / usage_records / daily_summary / pairing_info /
    app_category；家长端表：device_registry / parent_policies / parent_announcements /
    parent_usage_summary（parent_audit_log 保留，审计链不断）；
  - 凭据：web_token / account_email / account_role（保留服务器地址配置）；
  - 中继配置 + 设备身份：relay_* / device_id / device_name（D2 重绑全新身份）；
  - 顺序：先断 P2P（避免旧会话继续收发）→ 数据库 → 凭据 → 中继/身份 → 核对；
  - `verifyClean`：数据库各表行数=0 + 凭据/身份键不存在（数据库/配置文件两层核对，
    UI 层由调用方回到未绑定状态呈现）；
  - 审计落 parent_audit_log（不含敏感明文）。
- `ParentAccountReset.resetAccount` 重构（设置页换账号清理与登录页换新账号共用）：
  1. 旧账号云端验证（POST /api/auth/login，离线拒绝清除）；
  2. **服务端本机设备解绑（尽力而为）**：旧 JWT → GET /api/devices 定位本机 device_id →
     POST /api/auth/verify-password 换一次性操作令牌 → DELETE /api/devices/{id}（X-Action-Token）；
     仅删本机 device_id 名下的记录，不碰旧账号其它设备；失败/离线不阻断本地清除；
  3. LocalDataWipe 全清 + 三处核对；结果（步骤 + 核对明细）返回 UI 展示。
- 需求 3 家长端（ParentLoginScreen）：登录新账号时检测旧绑定邮箱 ≠ 新邮箱 →
  弹「检测到旧账号数据」确认框：明确列出清除范围（公告/策略/分类/使用记录/报告缓存/
  凭据/中继配置/设备身份重置+服务端同步解绑），需旧账号邮箱+密码验证；
  确认 → 旧账号验证+全清 → 继续登录新账号；取消则中止（旧数据原样保留）。
- 需求 3 儿童端（GuardianHomeContent）：三条配对入口（扫码 / 发现设备 / 手动 IP）
  统一经 `requestPairing` 把关：本地业务数据（announcements/policy_cache/usage_records/
  pairing_info 任一非空）→ 弹确认框（范围同家长端 + “旧家长将无法再管控本设备”）；
  确认 → 全清后以全新 device_id 继续配对；无残留 → 静默重置设备身份后直接配对
  （避免旧 device_id 撞号/越权认领）；取消则中止配对。
- `CloudHttp.httpDeleteJson`：带自定义请求头（X-Action-Token）的 DELETE 助手。

## 自主裁决说明（产品负责人未逐条决策，按红线裁决并留档）

1. **服务器地址不随账号清除**：需求 3 将“服务器地址”列为残留检测项，但地址是基础设施配置
   （新旧账号通常同一服务器），清除会强制用户重填且非账号数据；判定为不属清除范围，
   仅作为登录页预填展示。JWT/邮箱仍严格清除。
2. **儿童端确认不需要密码**：儿童端无账号上下文；换绑授权 = 新家长服务端配对码
   （5 分钟有效、服务端已鉴权）+ 设备持有人界面确认；被清除的是本机数据，与
   “家长账号密码验证”语义（保护家长账号数据）不冲突。家长端路径仍强制旧账号密码验证。
3. **换账号时服务端同步解绑本机设备（尽力而为）**：防止旧账号名下残留孤儿设备行；
   失败（离线/无此设备/限速）不阻断本地清除，孤儿行由旧账号后续自行解绑兜底。
4. **无残留时静默重置设备身份**：新装/已清干净状态直接换新身份配对，不打断流程
   （无数据可损即无需确认）。

## 安全考量

- 家长端清除必须旧账号密码云端验证（离线拒绝）；儿童端清除必须设备持有人确认 + 有效配对码；
- 服务端解绑沿用 X-Action-Token 一次性令牌 + 归属校验（越权 403）；
- 全清保留 parent_audit_log 审计链，清除动作本身落审计（不含密码/令牌明文）。

## 测试

- Android 单元测试全量通过（115+，含 ParentAccountReset 验证失败/离线拒绝用例）；
- 服务端无新增接口（复用 A12 + verify-password），由 Codex `dotnet test` + 回归覆盖；
- 真机验证点：登录新账号触发旧账号提醒→验证→清除→登录成功；取消路径原样保留；
  儿童端换绑确认→清除→新身份配对成功；设置页清除后核对清单全部 ✓；
  Web 设备列表旧账号名下本机设备消失（服务端解绑生效）。
