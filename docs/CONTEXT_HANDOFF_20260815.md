# 小趴菜 · 交接快照（2026-08-15，新会话先读本文 + 技能库 current-state.md）

> 凭据/密钥一律不写本文件；需要时读 `C:/Users/winann/.codex/skills/xiaopacai-collab/references/current-state.md`。

## 一句话状态
上线冲刺中：扫码绑定修复已合入并部署阿里云生产；公告账号归属已修复；**SEC-K3 限速自锁已修复上线**
（服务端补 error_code=ip_rate_limited + 客户端长退避，8fd86f6）；**公网域名 xpc.winann.com + HTTPS 反代已启用**；
**邮箱验证（Resend SMTP）已启用**；LOGO、安全基线、设备管理器解除、网页管理双链接均已完成。

## 角色与协作
- Claude@50.53 = 主开发（主仓库唯一事实源；Codex 不直接改其主仓库）；Codex@50.20 = 测试/集成/部署/验收。
- 桥接信箱：`in\`=Codex→Claude，`out\`=Claude→Codex；任务走信件 + git bundle/format-patch。
- 最近信件：120（scan-fix 合并验证）、121（部署/公告修复/A1+B2 补丁）、122（K3 限速缺口，待 Claude 修复）。
- Claude 侧 base：xiaopacai=102aa77、xiaopacai-web=457740f；Codex 补丁：`in\prelaunch-apk-patches-20260815010027\`。

## 环境
- 阿里云公网：入口 `https://xpc.winann.com`（nginx 反代：80→301 HTTPS，443→127.0.0.1:5000，HSTS；
  Let's Encrypt 证书至 2026-10-13）；IP 直连 `http://8.217.165.122:5000` 仍可用（待收敛）；
  P2P `:9527`；部署目录 `/opt/xiaopacai/app`，systemd `xiaopacai-web`，环境 `/etc/xiaopacai-web.env`（勿覆盖）。
- 邮箱验证：注册需验证码（`POST /api/auth/email-code`）；邮件通道 Resend SMTP（smtp.resend.com:587），
  管理端「系统设置→邮件设置」可配置/测试；正式开放前需在 Resend 验证发信域名并改 no-reply@xpc.winann.com。
- 本机生产：192.168.50.11（50.11，Web 5000/P2P 9527，`/home/winann/xiaopacai-web`）。
- 真机：OPPO PKV110 @192.168.50.14（Web 设备 id=4，owner=parent，online；旧 debug APK 可收公告）。
- 模拟器：5554/5556/5558（debug），5560=Release x86_64 测试机；必须可见窗口；连生产走 `p5-relay.py`。

## 关键结论 / 已知问题
1. 真机公告问题已修：根因账号归属分裂，已 DB 归并 devices.id=4 与 announcements 1-5 → parent；实测发布→真机 ≤5s。
2. **SEC-K3 限速自锁已修复上线（8fd86f6）**：服务端补 `error_code=ip_rate_limited`，客户端长退避；
   UI 警告文案真机复测待办（E2E 曾被权限引导页遮挡；单测已覆盖）。
3. 真机装的是旧 debug APK；家长端“清除账号绑定与本地数据”等新功能需装新 Release APK（下载中心）。
4. 紧急公告 displayed 已落库；“我知道了”ack 在模拟器未闭环，待真机/稳定模拟器复测。
5. 域名 `xpc.winann.com` 已启用（HTTPS 反代 + HSTS + HTTP 301）；登录页与家长设置页的
   “网页管理”双链接已实现并部署本机 50.11，阿里云前端是否含该链接待部署确认。

## 已完成（2026-08-14~15）
- 安全基线（11 条红线）+ 公网安全测试提示词；安全验收报告 `docs/SECURITY_TEST_REPORT_20260814.md`。
- 安全修复合入：mTLS 指纹、归属校验、httpOnly Cookie、限速/CSRF/输入白名单、审计、诊断最小化等。
- LOGO 定稿与全端替换（Web/Android/Windows），预览 `brand/logo-final-preview.png`。
- scan-fix 合入（重连免码按指纹放行、确定性错误码、解绑释放归属、儿童端清配对码）。
- 每日限额重置、就寝时段强制执行、拦截链路加固（事件防崩溃+保守兜底+一键修复通知）。
- 设备管理器“解除保护”（家长密码验证后可卸载）；卸载引导文案修正。
- 网页管理双链接：Android 家长设置页卡片（IP+域名）+ Web 登录/注册页底部，已构建并部署本机 50.11；
  阿里云未部署（域名未就绪前先不上）。
- 公网默认口令处置：parent 已改强口令（凭据见技能库）；admin 由用户管理。
- Web 静态 gzip/brotli + 哈希长缓存（50.11 已生效）。
- HTTPS/域名反代（nginx，HTTP→301）+ 邮箱验证（Resend SMTP）已上线；K3 限速修复已上线。
- GitHub 已推送：xiaopacai main=`929e001`、xiaopacai-web master=`3f51ae8`。

## 待办（按优先级）
1. K3 UI 警告文案真机复测；Release 紧急公告 ack 复核（displayed 已落库，“我知道了”回执待闭环）。
2. 家长端「清除账号绑定与本地数据」UI 验收（错误密码拒绝/正确密码清除/新账号同步公告与策略）。
3. 收尾：5000 端口收敛（仅 nginx 本机来源）、Resend 发信域名验证（SPF/DKIM + no-reply@xpc.winann.com）、
   网页管理链接上阿里云（部署最新前端）、监控告警落地。
4. 上线验收报告更新；Release 签名打包流程验证（A1/B2 已就绪）。

## 关键路径/命令
- 仓库：`C:\Users\Public\bridge\work\xiaopacai`（android/windows）、`...\xiaopacai-web`（web/server）。
- 测试脚本：`C:\Users\Public\bridge\work\tmp-web-e2e\`（release-e2e-final.py、scanfix-bc-retest.py、
  aliyun_*.py、p5-relay.py）。
- 构建：Web `npm run build`；server `dotnet publish -c Release -r linux-x64 --self-contained true`；
  Android `gradlew assembleRelease`（签名读 gradle.properties）；Windows `dotnet build/test`。
- 部署阿里云：tar 打包 → aliyun_sftp push → `aliyun_update.py`（保留 Data/downloads、env 不动）。
- 工具：dotnet `C:\dotnet`、JDK `C:\jdk17`、Android SDK `C:\android-sdk`、ADB 在 platform-tools。
- 阿里云 P2P 证书指纹（2026-08-15 已更新）：`a518ac3b57130ec697e9927de23d4bf69388f844651c6ea46404bb0157ca449e`。

## 注意
- 生产数据库当前为明文 SQLite（服务器侧已装 sqlite3 供巡检）；属待加固项（R6.2）。
- GitHub 推送：用户已授权每次推送；仓库改动先本地 commit，按用户指令/惯例推送。
- 技能库 current-state.md 含凭据，严禁外传/写入日志与信件。
