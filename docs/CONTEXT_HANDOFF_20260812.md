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
