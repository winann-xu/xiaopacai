# ⚠️ 主开发 agent：停下来先读这个

## 你正在找的 MainActivity.kt 不存在 —— 本模块入口叫 TvHomeActivity.kt

你在正确的目录（`android-tv/`，包名 `com.xiaopacai.tvos`）。**不要再 find MainActivity.kt**。

实际文件（`android-tv/app/src/main/java/com/xiaopacai/tvos/`）：
- `ui/TvHomeActivity.kt` ← **主入口 Activity**
- `ui/lock/`、`ui/whitelist/`、`ui/bedtime/`、`ui/announcement/`、`ui/settings/`、`ui/theme/`
- `service/LockOverlayService.kt` ← 锁屏前台服务（已实现，356 行）
- `util/`

## 你之前困惑的原因（一次说清）

1. 你的 Turn 1 曾在 **`xiaopacai/android/app-tv/`** 建过文件 —— 那违反 TASK.md §Scope（android/ 是**只读参照区**），已被人工清理。
2. **它不是丢失，是清理**。继续 find 只会浪费算力。
3. 你现在看到的 `android-tv/` 才是任务目录，且**已包含完整实现**（不是空的）。

## 立即执行（3 步，别再做其他探索）

```
1. 读 android-tv/AGENTS.md           ← L1 规则 + 单次 write ≤400行/12000字符
2. 读 android-tv/TASK.md             ← 7 条验收标准
3. 读 android-tv/docs/CHECKPOINT.json ← 从 next_action 继续 = TASK-TV-05 锁屏 E2E
```

读完后，只做 CHECKPOINT 里 `next_action` 指定的那**一个原子动作**。

## 禁令

- ❌ 不要在 `xiaopacai/` 下创建或修改任何文件
- ❌ 不要用 `write` 重写已有大文件（用 `edit` 逐段改）
- ❌ 不要连续探索超过 2 次而不推进任务
