# ADR 0018 — 无线调试自授权 + Device Owner 预置（脱离电脑的强管制预置通道）

- **状态**：已实施（2026-08-24 三端实测完成，v1.3.0 交付）
- **日期**：2026-08-24
- **决策者**：Codex@50.20（方案起草）/ 产品负责人（核准）/ Claude@50.53（实施）
- **关联任务**：[TASK-STRICT-PROVISION-V1]
- **相关文档**：`docs/STRICT_CONTROL_EVALUATION.md`、`android/docs/DEVICE_OWNER.md`、
  ADR 0016-4（Bug1-C）、D4 决策（普通界面不出现 ADB 提示）、Shizuku 官方手册（无线调试模式）

## 背景

1. **现状**：V3 已按 D4 删除初始化与家长端全部「电脑 ADB 一条命令」类指引；ADR 0016-4（Bug1-C）
   确定 Device Owner 只检测不激活；`STRICT_CONTROL_EVALUATION.md`（2026-08-23）结论：
   无 GMS 国产设备上，DO 预置通道只剩 **ADB 预置** 与 **OEM 预装**，QR/NFC/zero-touch
   均依赖 Google Play 服务、国内不可用。
2. **需求**：产品负责人提出——能否让小趴菜**脱离电脑**完成「ADB 授权」（原流程为电脑 ADB
   一条命令，约 30 秒）；可接受恢复出厂作为前置条件。
3. **可行性事实（2026-08-24 调研）**：
   - ADB 授权模型：授权 = 设备信任某台主机的 RSA 密钥；`/data/misc/adb/adb_keys` 由
     root/SELinux 保护，第三方 App 无法自写；授权必须经「允许调试」弹窗或 6 位配对码由**人**完成。
   - 普通 App 无 `REBOOT` 权限（signature|privileged），无法自行重启手机；`adb shell reboot`
     仅在获得 shell 权限后可用。
   - 无线调试（Android 11+）：开启时 adbd 监听随机端口；App 可**扮演 ADB 主机**连接本机 adbd
     （Shizuku 模式），用户输入一次配对码即获 shell 权限——**无需电脑**；但每次重启后无线调试
     默认关闭、需重连。
   - `dpm set-device-owner`：需设备无账号、初始状态（恢复出厂可满足）；shell 权限可执行。
   - Google 2026 年提案（未落地）可能限制 adbd 仅绑定 WiFi 接口、阻断 loopback 自连——方案需留降级路径。

## 核心结论

1. **原设想流程（启动 → 自动重启 → 重启后自动授权）不可行**：App 无权重启；ADB 授权强制「人」参与；
   无线调试重启即失效，重启会把一次性授权变成每次开机重来。
2. **目标（脱离电脑授权）可行**：无线调试自配对（App 自当 ADB 主机，用户输一次配对码）→ 获得
   shell 权限 → 在出厂重置后的无账号设备上执行 `dpm set-device-owner` → 永久 Device Owner。
3. **ADB 只做一次性引导**：授权后日常能力靠 DO 持久权限（跨重启生效），不依赖无线调试/ADB 常开——
   这是本方案与「Shizuku 每次开机重连」模式的本质区别。

## 决策

### 1. 产品形态：强管制模式（独立受控入口）

- 普通版（默认）：维持 V3 纯 UI 授权，普通界面不出现 ADB/命令/调试提示（D4 延续）。
- 强管制模式：家长在「设置 → 守护增强 → 强管制模式」主动进入的独立流程，走本 ADR 通道。
- 设备形态：不限定（普通手机出厂重置后亦可）；产品定位仍建议按 STRICT_CONTROL_EVALUATION
  「家庭专用设备」界定，避免泛化承诺。
- 本期（V1）范围：**打通预置通道 + DO 状态展示 + 防卸载链路确认**；kiosk/Lock Task、应用挂起、
  DO 策略中心、逃生舱界面划入 V2（对应 STRICT_CONTROL_EVALUATION 阶段 1 拆分）。

### 2. 技术底座：内嵌官方 adb 二进制（LADB 模式，P1 已确认）

- 将 Google 官方 adb 编译为 `libadb.so`（arm64-v8a / armeabi-v7a / x86_64 三 ABI），放入
  jniLibs，运行时从 `nativeLibraryDir` 用 ProcessBuilder 执行（同 LADB；不用 getFilesDir，
  规避 Android 10+ W^X 限制）。
- 二进制来源：优先 AOSP NDK 自行构建（一次性工作），备选 rendiix/android-tools 预编译产物
  （LADB 同源）；固定版本 + SHA-256 校验入库。
- adb server 仅在授权期间运行：`-L localabstract:xiaopacai_adb` 自定义监听（避开固定 5037
  端口），授权完成即停，降低本地攻击面。
- 配对交互：主路径为用户从无线调试页抄「IP:端口 + 6 位配对码」填入 App（LADB 验证过的
  交互，最稳）；辅助用既有 jmdns 发现 `_adb-tls-pairing._tcp` 自动预填（Shizuku 验证）。
- 命令白名单执行：仅允许 dpm/pm/appops/settings 必要子命令，禁止通用 shell 入口。
- 备选：若官方二进制供应链不可接受，再评估 `libadb-android`（双许可 GPL-3.0-or-later /
  Apache-2.0，取 Apache-2.0 兼容；需承担 Rust 工具链成本）。
- 不引导安装/依赖 Shizuku 第三方 App（避免每次开机依赖第三方、孩子可绕过）。

### 3. 授权流程（用户视角，全程无电脑）

1. 家长进入强管制模式 → App 检测前置条件：Android ≥ 11、无账号（或提示「建议恢复出厂」）、App 已安装。
2. App 分步引导：开启开发者选项 → 开启 USB 调试 → 开启无线调试（附机型差异说明）。
3. 系统设置页点「使用配对码配对设备」→ 家长把 6 位配对码输入 App（通知/弹窗）——**此即「用户确认授权」**。
4. App 以主机身份完成配对 → `adb tcpip 5555` → 回环自连 `127.0.0.1:5555` → 执行
   `dpm set-device-owner com.xiaopacai.child/.service.GuardianDeviceAdminReceiver`。
5. 成功：引导重登账号 → 进入系统正常使用；失败：分类提示（无账号 / ROM 拒绝 / 配对超时 /
   版本不支持），**不自动重试**，回退普通模式。

### 4. 能力边界（如实说明，写入用户手册）

- DO 不可防：安全模式启动、Recovery 恢复出厂、root 设备（沿用 STRICT_CONTROL_EVALUATION 6.1）。
- Android 8–10（minSdk 26 存量）：无无线调试 → 不自授权，保持普通模式（不出现 ADB 提示）；
  电脑回退仅作文档说明。
- HarmonyOS NEXT：无 ADB（hdc），不支持。
- OPPO ColorOS：需在开发者选项关闭「权限监控」（Shizuku 官方 FAQ 已记录）；`dpm` 额外校验需真机实测。
- dpm 副作用（失败可能清数据）：仅在无账号/出厂重置后执行；执行前二次确认；失败不自动重试
  （沿用 DEVICE_OWNER.md 安全红线）。
- Google loopback ADB 收紧提案（2026，未落地）：未来系统版本若阻断自连，App 检测后提示
  改用电脑 ADB 或维持普通模式。

## 模块改动清单（Android，儿童端同 APK 双角色）

| 模块 | 改动 |
|---|---|
| `adbshell/`（新） | 内嵌官方 adb 二进制（jniLibs 三 ABI）+ ProcessBuilder 执行、`-L localabstract` 自启自停、配对码输入 UI（通知/弹窗）+ mDNS 预填、状态机（idle→discovering→pairing→connected→provisioning→done/failed）、命令白名单执行器 |
| `StrictProvisionActivity`（新） | 强管制模式入口页：前置条件检测、分步引导、二次确认、结果/失败页 |
| `GuardianDeviceAdminReceiver` | 扩展 DO 场景支持（manifest 满足 DO 预置要求；DO 激活后防卸载链路确认） |
| `GuardDownMonitor` / 状态页 | DO 激活后健康度展示扩展为「已激活（强管制）」（沿用 0016-4 只检测语义） |
| 权限引导页 | 普通版不变；强管制入口独立，不污染普通流程 |
| 设置/关于 | 强管制模式状态、说明与边界文案 |

## 风险与对策

| 风险 | 对策 |
|---|---|
| OPPO/ColorOS 限制 `dpm` | 真机实测先行；失败提示「本机型暂不支持强管制模式」，回退普通模式，不自动重试 |
| ColorOS「权限监控」拦截 adb 权限 | 引导页分步关闭（Shizuku 官方 FAQ 已记录） |
| 配对码单次有效/超时 | 状态机引导重新生成配对码；失败引导重试而非反复消耗配对码 |
| dpm 失败副作用（清数据） | 仅在出厂重置/无账号状态执行；二次确认；失败只提示 |
| Google 收紧 loopback ADB | 版本/能力检测 + 降级提示（电脑 ADB 或普通模式） |
| 第三方预编译 adb 二进制供应链 | 固定版本 + SHA-256 校验入库；优先 AOSP NDK 自建，保证可复现构建 |
| shell 权限滥用 | 命令白名单（仅 dpm/pm/appops/settings 必要命令），不提供通用命令入口 |
| 重启后无线调试失效 | 设计上不依赖：授权一次性完成，日常走 DO 权限 |

## 替代方案与拒绝理由

| 替代方案 | 拒绝理由 |
|---|---|
| 依赖 Shizuku 第三方 App | 每次开机需用户手动启动；孩子可绕过；多一个第三方依赖，与「日常无依赖」目标冲突 |
| QR/NFC/zero-touch 预置 | 依赖 Google Play 服务，国内无 GMS 不可用（STRICT_CONTROL_EVALUATION 已确认） |
| Root 通道 | OPPO 解 BL 难、安全风险高、与「防绕过」目标矛盾；仅作长期可选 |
| 电脑一次 ADB（V3 前卡片） | 正是本次要消除的依赖；仅保留为降级回退路径 |

## 真机验证清单

1. OPPO PKV110（ColorOS）：开发者选项/无线调试开启 → 自配对 → 回环自连 → `dpm set-device-owner`
   成功/拒绝行为（含 ColorOS「权限监控」关闭验证）。
2. 华为真机（型号待定，需 HarmonyOS 4.x / EMUI 兼容机型）：无线调试入口差异、配对行为、
   `dpm` 结果、仅充电模式/后台限制差异逐项记录；HarmonyOS NEXT 记录为不支持（不阻塞交付）。
3. AVD Android 14（xiaopacai_test 虚拟终端）：普通模式回归 + 自配对流程（视模拟器无线调试支持）。
4. Android 13/14/15 配对状态持久性（重启后是否需要重新配对）。
5. DO 激活后：防卸载、状态展示、家长端状态卡、解绑/换机行为。

## 测试与验收

- Android 单测：新增状态机/前置条件/命令构造/错误分类用例；存量 137 例全绿。
- V3 全量回归（普通模式不受影响）；真机走查记录；验收报告更新。
- 交付物：可用的强管制版本 Release APK（三 ABI、正式签名、下载中心上线）、双 bundle（android）、
  ADR 0018 入库（android + web 镜像）、DEVICE_OWNER.md 与 STRICT_CONTROL_EVALUATION.md 结论更新、
  CHANGELOG、用户手册「强管制模式」章节、TOKEN_USAGE 记录、验收报告（含华为/OPPO/AVD 三端实测结论）。

## 修订记录

- v1.1（2026-08-24）：P1 技术选型改为「内嵌官方 adb 二进制（LADB 模式）」（原 libadb-android
  降为备选）；P2–P5 按专业判断确认；真机测试矩阵新增华为，虚拟终端（AVD）并入；交付物明确为
  可用的强管制 Release 版本。
- v1.2（2026-08-24，实施修订）：P1–P5 全部确认；实测中修正三处——
  ① `isProvisioningAllowed(ACTION_PROVISION_MANAGED_DEVICE)` 不作为硬门槛（ColorOS 上误报
  false，与 `dpm set-device-owner` 不是同一路径），真实结果以 dpm 输出分类为准；
  ② 构建需 `useLegacyPackaging=true`，否则 native 库留在 APK 内、nativeLibraryDir 为空，
  App 无法执行 libadb.so（LADB 同款行为）；
  ③ dpm 输出解析补充 ColorOS 实测变体「there are already some accounts on the device」
  （含 not allowed 字样，须先于 ROM 拒绝判断归类为 ACCOUNTS_PRESENT）。
  流程微调：dpm 经无线调试会话直连执行（不强制 tcpip 5555/回环，减少失败点，DO 状态
  跨重启持久无需常开 ADB）。
  三端实测结论：OPPO PKV110 全链路（自配对成功、有账号 dpm 正确分类）、AVD Android 14
  （dpm 成功 + 已激活态展示）、华为 FRD-AL10（Android 8 低版本拦截正确）。
