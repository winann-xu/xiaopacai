# 🚀 主开发 agent — 从这里开始（000 号文件，最先读）

## 你现在在哪 / 该做什么

你在 **`android-tv/`** —— 这是**正确的工作目录**（包名 `com.xiaopacai.tvos`）。
**不要再找 `MainActivity.kt`**：本模块的入口 Activity 叫 **`TvHomeActivity.kt`**（位于 `app/src/main/java/com/xiaopacai/tvos/ui/`）。

## 立刻按顺序做（三步）

1. 读本目录 **`AGENTS.md`** —— L1 规则、单次 `write` ≤ 400 行且 ≤ 12000 字符、§七 续接协议
2. 读本目录 **`TASK.md`** —— 7 条机器可判的验收标准
3. 读 **`docs/CHECKPOINT.json`** —— 从 `next_action` 继续（当前 = **TASK-TV-05**：超时锁屏 E2E）

## 背景澄清（你刚才一直在困惑的事）

- 你此前在 **`xiaopacai/android/app-tv/`** 下创建了 `MainActivity.kt` / `MainMenuScreen.kt` / `navigation/`。
- 那违反 TASK.md §Scope（**禁止改 android/**，该目录是只读参照区），已被人工清理并移入废纸篓。
- **不是丢失，是清理** —— 不要再 find/ls 寻找它们，继续找只会浪费算力。
- 你的产物本来就该落在这个目录（`android-tv/`）内。

## 当前代码结构（已存在，别重建）

```
android-tv/app/src/main/java/com/xiaopacai/tvos/
├── ui/          TvHomeActivity.kt（主入口）、lock/、whitelist/、bedtime/、
│                announcement/、settings/、theme/
├── service/     LockOverlayService.kt（356 行，已实现 TYPE_APPLICATION_OVERLAY 锁屏）
└── util/
```

## 禁止事项（详见 AGENTS.md §2.1 / §2.3）

- ❌ 在 `xiaopacai/` 下创建或修改任何文件
- ❌ 用 `write` 重写已存在的大文件（应逐段 `edit`）
- ❌ 单次 `write` 超过 400 行 / 12000 字符
- ❌ 用完全相同的参数重试失败的调用
- ❌ 反复 find/ls 探索而不推进任务（连续 2 次失败就停下写 PROGRESS.md）
