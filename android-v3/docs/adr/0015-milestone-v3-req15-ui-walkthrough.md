# ADR 0015 — 里程碑 V3 需求 15：全端 UI 走查与修复

- 日期：2026-08-15
- 状态：已采纳（TASK-MILESTONE-V3）
- 范围：Android 全流程（儿童端/家长端/登录/注册/找回/权限引导/设置/策略/公告/报告/关于）

## 背景

需求 15：全端 UI 一致性检查（字体字号、间距、圆角、颜色、图标、空态/加载态/错误态、
深浅色模式、按钮层级），输出《UI 走查问题清单》并修复。

走查方式：3 个并行走查代理分域审查（儿童端流程 / 家长端流程 / 登录注册找回 + 无障碍与
深浅色），汇总裁决后逐项修复。无真机截图条件，走查为代码级审查；视觉最终确认由 Codex 侧
真机/浏览器回归覆盖。Web 端页面随需求 10/11/13/14 落地时已逐页实现并自走查，本轮无新增
需修复项，问题集中在 Android 端。

## 裁决原则

按红线 **安全 > 功能 > 性能**：

- 安全问题（明文地址泄露、权限残留）→ 立即修复；
- 功能缺陷（死锁、崩溃、虚假数据、卡死、布局错乱）→ 立即修复；
- 纯视觉/统一性问题 → 低成本即修，高成本批量重构记录为已接受债务。

## 问题清单

### P0 ×1（功能死锁）

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 1 | AnnouncementOverlayActivity（公告全屏页） | 内容 Column 整页垂直居中，长公告或大字体（fontScale）下「知道了」按钮被顶出屏幕，**儿童无法关闭公告（死锁）** | 内容卡片 `weight(1f)` + `verticalScroll`，按钮与提示固定底部，任意长度公告均可关闭 |

### P1 ×4（安全红线 + 功能缺陷）

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 1 | ParentSettingsScreen「管理后台」入口 | 硬编码 `http://8.217.165.122:5000`（**安全红线**：泄露内网 IP 且强制 HTTP 明文） | 从 CloudAccountManager 读取已保存 host/port；内网地址（192.168./10./172./localhost）走 http、其余强制 https；未配置时显示 xpc.winann.com |
| 2 | ParentHomeScreen 解绑流程 | 解绑只清库不断开 P2P，正在连接的儿童设备连接残留（管控权限残留） | 解绑确认时若当前连接指纹 == 解绑设备指纹，调用 `GuardianForegroundService.getP2PConnection().disconnect()` |
| 3 | parent/GuardianStatusScreen（家长守护状态页） | 各权限项硬编码 true/false 虚假状态，误导家长判断 | 诊断上报当前仅日志记录未落库（ParentP2PListenerService），如实统一显示「待上报」+ 说明卡片，指示以儿童端权限页为准 |
| 4 | ParentLoginScreen | 端口输入非数字时崩溃/解析失败；SystemGateDialog 取消后 `isProcessing` 不复位，登录按钮永久转圈（卡死） | 端口过滤非数字字符（≤5 位）+ `toIntOrNull` 范围校验兜底默认端口；`isProcessing` 仅在 doCloudLogin 内设置 |

（132 信登录页三项优化已随本轮落地并复查：移除 allowHttpOverride、失败文案三类细分、
未配置时预填 xpc.winann.com:443。）

### P2 已修复（低成本即修，共 30 项）

**ParentHomeScreen（8 项）**
- getLocalIps 括号优先级 bug：局域网判定误伤公网 IP（`&&`/`||` 混用缺括号）
- 设备状态图标无 contentDescription（读屏无播报）
- 设备名超长不截断（布局挤压）→ maxLines=1 + Ellipsis
- 保存时间格式无校验（任意字符串可存）→ 正则 `^([01]?\d|2[0-3]):[0-5]\d$`
- 离线时策略编辑控件未禁用（可改但失效，误导）→ Slider/输入框/单选全部禁用
- 3 处 AssistChip(onClick={}) 空点击徽章 → Surface 徽章（可点击元素无行为，无障碍）
- 公告同步中无 loading 反馈 → CircularProgressIndicator；公告标题超长挤压 → maxLines=1 + 状态徽章
- 分类列表缺稳定 key → itemsIndexed key（重复项崩溃风险）

**ParentLogScreen（4 项）**
- LazyColumn 缺 key → itemsIndexed key（重复时间戳崩溃风险）
- 级别色硬编码 Color 不适配深色模式 → colorScheme 派生（error/tertiary/onSurfaceVariant/primary）
- TopAppBar 样式与全 App 不一致 → primaryContainer
- 复制/上传/清空无反馈 → Toast

**ParentLoginScreen（4 项）**
- 字号硬编码 → typography token（titleLarge/bodyMedium/bodyLarge/labelMedium）
- 键盘遮挡输入框 → imePadding
- 密码明文无显示切换 → 可见性切换按钮
- 统一红字错误 → 字段级错误提示（host/port/email/password）

**AppCategoryScreen（4 项）**
- DB/口令异常直接崩溃且 loading 卡死 → 错误态 + 重试（LaunchedEffect(reloadKey) + try/catch）
- 搜索框 emoji 图标（读屏播报乱码）→ Icons.Default.Search
- 强制浅色主题 → 跟随系统深色（与主界面一致）
- 重复 import 清理

**GuardianHomeContent（3 项）**
- 公告列表缺稳定 key → items key
- 公告空态无文案 → 「暂无家长公告」
- QuickActionButton 图标重复播报 → contentDescription=null（按钮文案已可读）

**settings/GuardianStatusScreen（2 项）**
- 强制浅色主题 → 跟随系统深色
- 诊断上报开关补读屏语义（contentDescription = "诊断上报开关"）

**PermissionGuideScreen（2 项）**
- 进度条缺读屏语义 → contentDescription = "权限进度 n/4"
- 系统设置跳转失败无提示 → Toast「无法打开系统设置，请手动前往设置开启」

**BlockOverlayActivity（1 项）**
- 中部内容不滚动，大字体下「我知道了」按钮被顶出（与 P0 同模式）→ 中部 weight(1f)+scroll，按钮固定

**RoleGuideScreen（1 项）**
- 长文案无滚动 → verticalScroll

**AboutContent（1 项）**
- Role.Link 在 Compose 1.5 不存在（编译错误）→ 回退 clickable 并注释说明

## 已接受债务（记录不修）

1. **字体 token 统一**：约 10 个文件仍用 fontSize/sp 直接值（视觉一致但无功能/安全问题；
   批量重构回归风险 > 收益，留待专项清理）。
2. **部分硬编码色值**（GuardianHomeContent、BlockOverlay、settings/GuardianStatusScreen
   概览卡片）：深色模式下对比度检查未发现失败，纯视觉项。
3. **家长端守护状态页真实诊断数据落库展示**：诊断上报当前仅日志记录（未持久化），
   落库 + 展示属新功能（超出 UI 走查范围），本轮已如实标注「待上报」。
4. **Compose 1.5 无 Role.Link**：链接无障碍角色待升级 Compose BOM（2023.10.01 → 新版）
   后补充。

## 验证

- `./gradlew compileDebugKotlin` EXIT=0（全部修复后全量编译通过）
- 深浅色模式：统一 XiaopacaiTheme 跟随系统深色（isSystemInDarkTheme），不再强制浅色
- 真机视觉回归由 Codex 侧执行（Codex 拥有真机测试环境）
