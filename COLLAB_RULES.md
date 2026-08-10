# 小趴菜 双机协作规则（项目版）

- Git 唯一事实源：主工作区 /home/winann/xiaopacai（50.53），测试镜像 C:\Users\Public\bridge\work\xiaopacai（50.20）。
- 变更走 commit；同步用 git bundle（Claude 产出 bundle 放 bridge/work，Codex 拉取；反之亦然）。
- 同步白名单：android/ windows/ docs/ tests/ build/；排除 data/ logs/ dist/ 构建产物。
- 角色：Claude 主开发；Codex 主测试（全功能 GUI 测试 + 构建冒烟 + 回归）。
- 通信：实时 SSH（claude-real / codex exec）+ 桥接信箱（阶段总结与派发）。
- 纪律：任务 ID 标注 [TASK-xxx]；每任务完成即 commit + 更新 CHECKPOINT；
  关键决策写 docs/adr/；Token 用量写 docs/TOKEN_USAGE.md；凭据不落盘不进信件。
- 质量门禁：核心模块覆盖率 >=80%；全功能 GUI 测试截图留档；中文注释全覆盖。
- 超时停用（整机/部分 APP）为产品核心功能，必须实现并如实说明能力边界。
