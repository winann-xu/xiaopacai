# 版本管控规范（VERSIONING）

[TASK-MILESTONE-V3] 需求 1 · 自里程碑 V3 起，所有升级走 Git 版本管控。
本文档为小趴菜双仓库（xiaopacai-web / xiaopacai）统一规范。

## 一、语义化版本

采用 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订`（X.Y.Z）。

| 升级类型 | 示例 | 场景 |
|---|---|---|
| 主版本 X | 1.0.0 → 2.0.0 | 架构重构、不兼容变更（协议/数据库不兼容升级） |
| 次版本 Y | 1.0.0 → 1.1.0 | 新功能（本期 V3 即 1.1.0） |
| 修订 Z | 1.0.0 → 1.0.1 | 缺陷修复、文案修正 |

- 发布 tag 一律使用纯版本号：`v1.1.0`（不带后缀；预发布/内部包不占用正式 tag）。
- 每次升级顺序：**更新 CHANGELOG.md → 打 Git tag → 构建验证 → 部署记录对应 commit/tag**。

## 二、各端版本号规则

| 端 | 规则 |
|---|---|
| Android | `versionName` = Git tag 去掉 `v`（构建时自动读取）；`versionCode` = major×10000 + minor×100 + patch 自动推导（如 v1.1.0 → 10100），保证单调递增；无 tag 的开发构建 versionName 为 `dev-短哈希`、versionCode 用 dev 兜底值 |
| Web 前端 | 构建产物注入 `__APP_VERSION__`（Vite define，读 Git tag，无 tag 为 `dev-短哈希`） |
| 服务端 | 部署包记录 commit/tag；部署后 /api/health 可核对版本 |

历史兼容：Android 此前 versionCode=1（1.0.0）；新方案 v1.1.0 → versionCode=10100 > 1，存量设备可正常升级。

## 三、CHANGELOG 规范

- 格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，中英文「新增/变更/修复/移除」分区。
- 每个版本条目标注任务 ID（如 `[TASK-MILESTONE-V3]`）与日期。
- 两仓库各自维护自己的 CHANGELOG.md；同版本联动发布时条目互相引用。

## 四、部署记录要求

阿里云每次部署记录（DEPLOY.md 或部署信）必须包含：版本 tag、双端 commit、部署时间、环境变量变更、回滚点（备份路径）。

## 五、里程碑历史

| 版本 | tag | 说明 |
|---|---|---|
| 1.1.0 | v1.1.0 | 里程碑 V3（本期起执行本规范；历史版本 1.0.0/1.0.1 未打 tag，不追溯补打） |
