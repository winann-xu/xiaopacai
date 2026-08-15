# 变更日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。规范见双仓库统一文档 `docs/VERSIONING.md`。

---

## [1.1.0] — 2026-08-15（[TASK-MILESTONE-V3]，进行中）

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

### 移除
- 需求 6：初始化/登录流程中的 ADB/运行命令提示（PermissionGuideScreen 电脑 ADB 一键授权卡片）
- 需求 8：家长端「电脑 ADB 一条命令快速指南」入口（ParentAdbGuideScreen 删除）
- 需求 9：家长端分类限额 UI（入口隐藏，保存强制 -1 不限；后端逻辑保留）

### 修复
- 关于页年份写死 2024 → 动态年份
- 登录失败文案笼统 → 按原因细分，家长可自查网络/地址/HTTPS 配置

---

## 格式说明

`[版本号]` — 发布日期，格式 YYYY-MM-DD。分类：新增 / 变更 / 修复 / 移除。
