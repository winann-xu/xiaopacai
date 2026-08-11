# 小趴菜（儿童守护）· Android 双角色升级提示词包（家长端入 APP）

版本：V2.1-P1    日期：2026-08-11    编制：Codex@50.20（主测试）    依据：2.0 验收报告 + P2P 协议 + Web 3.0 提示词包

## 一、执行总览

- 目标：在现有 Android 儿童端 APP（com.xiaopacai.child）内新增**家长端角色**，同一 APP 用角色区分：
  - **儿童端**：免密码直接进入守护界面（现有逻辑不变），负责时长采集/策略执行/超时拦截。
  - **家长端**：密码登录（PBKDF2），家长在**另一台手机**安装同一 APP，以家长身份登录后**监控儿童端**（设备列表/策略下发/公告推送/使用报告/超时处理）。
- 版本：Android 2.1（2.0 离线端体系内升级，不影响 Web 3.0；两端独立）。
- 儿童端 APK 与家长端为同一包名、同一安装包，首次启动选择/切换角色；儿童端默认免密，切家长端需密码。
- 角色：Claude@50.53 主开发（主仓库 `/home/winann/xiaopacai`）；Codex@50.20 主测试（模拟器双实例端到端）。

## 二、功能清单

### A. 角色与登录
1. 首次启动角色引导页：选择“我是家长 / 我是孩子”。
2. 儿童角色：免密直进守护主页（现逻辑不变）；提供“切换为家长”入口（需家长密码）。
3. 家长角色：设置家长密码（PBKDF2，≥10 万次迭代，复用 ParentPasswordManager 能力）→ 密码登录 → 家长主页；登出/切换回儿童需密码。
4. 角色与密码存本地加密偏好（guardian_prefs / KeyStore），密码永不明文。

### B. 家长端功能（移动版，对齐 Windows 家长端）
1. **设备管理**：儿童设备列表（在线/离线/证书指纹/最近同步）；配对（启动 P2P 监听 + 显示配对码/二维码；或扫描/手动 IP 连接儿童端）；解绑。
2. **策略配置**：每日限额（30~480 分钟滑杆）、就寝时段、分类限额（游戏/社交/视频/学习）、黑白名单、超时处理方式（整机停用/部分 APP 停用/仅提醒）——保存即下发到已连接儿童端。
3. **公告管理**：新建/编辑/发布/撤回公告（普通/重要/紧急，有效期），发布即推送儿童端。
4. **使用报告**：从儿童端同步的 usage_report 生成日报/周报（分类占比、趋势），本机查看/导出 TXT/JSON。
5. **设置**：修改家长密码、通知偏好、服务端口（默认 9527）、数据清除。

### C. 家长端 P2P（移动端监听服务）
1. 新增 Android **入站 P2P 监听服务**（对标 Windows P2PListenerService）：TCP 9527 + SslStream(TLS1.3/1.2) + 自签名证书（KeyStore/持久化，指纹稳定）；4 字节大端长度前缀 + JSON 帧，协议与 2.0 儿童端完全兼容。
2. 消息处理：handshake（记录设备、按设备回 policy_update）、usage_report（写本地 SQLCipher、回 sync_ack）、announcement_push、heartbeat(+ack)。
3. 配对：家长端生成 6 位配对码 + 显示证书指纹；儿童端“连接家长端”输入家长手机 IP + 配对码（儿童端现有 UI 已支持手动 IP，需补配对码校验）。
4. 儿童端连接即“上线”，断开自动标记离线；家长端实时状态刷新。

## 三、架构与数据边界

- 同进程双角色路由：MainActivity 启动按角色分发（child→现有 MainScreen；parent→新增 ParentMainScreen）；角色切换需密码。
- 家长端数据：本机 SQLCipher（复用 AppDatabase 模式，新增家长端表：parent_meta/device_registry/policies 缓存/announcements 缓存/usage 汇总），不上云。
- 通信：儿童端主动连接家长端（家长端手机监听），TLS 双向证书指纹校验；断网重试（儿童端现有 sendMessage 门卫逻辑复用）。
- 安全：家长密码 PBKDF2 + 盐；证书指纹首配锁定；配对码 6 位数字、服务端校验、失败限次。

## 四、目录结构（在现有 android/app 内扩展）

```
android/app/src/main/java/com/xiaopacai/child/
├── role/                 # 新增：RoleManager（child/parent、角色切换、密码）
├── ui/parent/            # 新增：家长端页面（ParentLoginScreen/ParentHomeScreen/设备/策略/公告/报告/设置）
├── p2p/                  # 新增：ParentP2PListenerService（入站 TLS 监听）；复用 P2PConnectionService 客户端
├── data/                 # 扩展：家长端表与 DAO（AppDatabase 加表）
└── service/              # 复用 GuardianForegroundService（儿童端）
```

## 五、构建与验证命令（50.20 执行）

- Android：`cd android && ./gradlew assembleDebug`；安装：`adb install -r`
- 双实例联调：创建第二个 AVD（如 xiaopacai_parent）；家长端模拟器 `adb -s emulator-<A> forward tcp:9527 tcp:9527`，儿童端连接 `10.0.2.2:9527`（经宿主机转发到家长端模拟器）
- 测试触发器复用 debug-only 组件（DebugTriggerActivity 扩展 parent 相关动作）

## 六、验收标准

| 类别 | 验收项 | 标准 |
|---|---|---|
| 角色 | 首次引导/切换/密码 | 儿童免密直进；切家长需密码；密码 PBKDF2 校验正确 |
| 家长端 | 设备/策略/公告/报告/设置 | 移动端 GUI 走查通过，功能对齐 Windows 家长端 |
| 联调 | 双机端到端 | 家长手机监听 → 儿童连接配对 → 策略下发生效 → 时长上报入库 → 公告推送 → 超时拦截 |
| 安全 | 密码/证书/配对码 | 密码不明文；证书指纹稳定；配对码校验与限次 |
| 数据 | 隐私边界 | 不上云、SQLCipher、数据清除 |
| 质量 | 构建与测试 | assembleDebug 0 错误；单测覆盖角色/密码/P2P 帧解析 |

## 七、执行协议

- 阶段：P1 角色框架 + 家长登录 + P2P 监听服务（可编译）→ P2 家长端 UI + 双机联调 → P3 端到端验收 + 打包。
- 协作：Claude 在 50.53 开发并 commit（含 [TASK-ROLE-xx] 标记），bundle 同步 50.20；Codex 构建/双实例联调/缺陷回传（信件 061+）。
- 中断续接：CHECKPOINT.json / PROGRESS.md / 本提示词。

## 八、第一阶段任务（P1，立即执行）

1. RoleManager：角色枚举/当前角色/切换（家长密码校验）、家长密码设置与修改（PBKDF2）。
2. 启动分流：MainActivity 按角色进入 child 或 parent；家长未设密码时引导设置。
3. ParentP2PListenerService：入站 TCP 9527 + TLS + 证书持久化 + 帧协议解析（handshake/usage_report/announcement_push/heartbeat）；配对码生成与校验。
4. 数据层扩展：AppDatabase 新增家长端表（device_registry/policies/announcements/usage 汇总）。
5. commit（含 [TASK-ROLE-P1]）→ bundle → 回信 docs/bridge-out/050-role-p1-done.txt（含 P2 计划）。

— 提示词包 V2.1-P1，Codex@50.20
