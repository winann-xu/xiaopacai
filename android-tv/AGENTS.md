# AGENTS.md — 小趴菜 TVOS 版（小米电视）主开发规范

> 任务文件夹：`/Users/winann/01-project/05-codex_project/projects/003-xiaopacai/android-tv/`
> 用途：主开发 agent 的 L1 系统提示词（逐字节静态）+ 分批分阶段实施硬约束。
> 依据：《小趴菜_TVOS版_小米_主开发派发版.md》§二 / §四。

## 一、L1 系统提示词（逐字节静态，禁止再改）

You are a precise coding agent working on the task folder in <task_file>.

<rules>
1. ONE atomic goal per turn. Read first, modify second, verify third.
   Independent read-only tool calls may be batched in one turn.
2. Exact file names, paths and formats from <task_file> are authoritative.
   Do not guess names or paths; if truly unknown, say what is missing and stop.
3. Never retry a failed call with identical arguments. Change approach or report.
4. Reply in terse bullets or plain text. No greetings, no restating the request,
   no code fences or pasted code unless the task requires producing code.
5. Before declaring completion, re-run every verification command in <task_file>
   and confirm each success criterion with real evidence.
6. When every success criterion is verified, reply with exactly: DONE
   (defined in <task_file>). Until then, keep working.
</rules>

<memory>
<task_file> is the goal. PROGRESS.md is the state: read it at start and after
any interruption; update it after every meaningful step (facts, failed attempts, next).
</memory>

## 二、分批分阶段实施硬约束

### 2.1 单次写操作上限（最关键）

违反此条会直接导致任务失败：输出被截断 → 模型重试 → 再次生成超长内容 → 空转数十分钟。

- 单次 `write` 的文件内容：**≤ 400 行 且 ≤ 12000 字符**（两者取小）
- 超限文件必须拆分写入：
  1. 先 `write` 骨架（imports + 空实现，≤ 100 行）
  2. 再用 `edit` 分多次追加实现，每次 ≤ 300 行
- **禁止**用 `write` 重写已存在的大文件（> 400 行）——包括"清理冗余 import"这类操作，必须用 `edit` 逐段删改
- **禁止**用 `write` 反复重写同一文件来"重试修复"（会陷入截断循环）
- 单轮回复的输出总量 ≤ 12000 字符

### 2.2 阶段推进（每轮只做一件原子动作）

| 阶段 | 动作 | 产物 |
|---|---|---|
| 1 分析 | `read` / `glob` 摸清现状（只读可并行） | 事实记录 |
| 2 骨架 | `write` 骨架文件（≤ 100 行） | 可编译空壳 |
| 3 实现 | 一次一个文件，用 `edit` 增量补充 | 完整实现 |
| 4 验证 | `./gradlew test` 或 `assembleDebug` | 真实命令输出 |

### 2.3 失败处理

- 禁止用完全相同的参数重试失败的调用（L1 §3）
- 同一动作连续失败 2 次 → 停止，写入 `PROGRESS.md` 的 `Failed` 段并报告阻塞
- 文件损坏时：用 `edit` 逐个删除问题片段，**不要** `write` 重写整个文件

### 2.4 本机环境事实（避免无谓探索）

- 本机为 macOS Apple Silicon，实际路径 `/Users/winann/01-project/05-codex_project/projects/003-xiaopacai/`
  （派发件中的 `/home/winann/...` 为 Linux 写法，本机不适用）
- 本地模型 output 上限 65536 tokens；长上下文下生成速度 28–60 tok/s，超长单轮输出会耗时数十分钟
- Robolectric 在 ARM Mac 存在原生库缺失（约 29 个测试失败属已知环境限制，非代码缺陷）

## 三、状态同步与中断续接（依据派发件 §七）

### 3.1 会话第一步（必做）

读 `android-tv/PROGRESS.md` + `android-tv/docs/CHECKPOINT.json`，记录起始 Token。

### 3.2 任务选择（严格按 CHECKPOINT.json）

- 只执行 `status = pending` 且 `depends_on` 全部为 `done` 的任务
- `status = blocked` 必须先给出恢复方案（修复 / 降级 / 绕过 + ADR）再继续
- `status = done` 不重复执行

### 3.3 每完成一个任务

更新 `CHECKPOINT.json` 的 `status` / `evidence` → `git commit`（消息含 `[TASK-TV-xx]`）→ 继续下一个。

### 3.4 每完成一个原子动作

更新 `PROGRESS.md` 的 `Status` / `Done` / `Failed` / `Verification` / `Next` / `Open questions`（保持 ≤ 200 行）。
