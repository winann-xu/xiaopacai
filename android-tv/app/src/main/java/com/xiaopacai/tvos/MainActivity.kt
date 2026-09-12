// ⚠️⚠️⚠️ 路标文件 — 不是实现，请读完再行动 ⚠️⚠️⚠️
//
// 你（主开发 agent）正在找的 MainActivity.kt 在【本模块不存在】。
// 本模块是 Android TV 应用，主入口 Activity 叫 TvHomeActivity.kt，位于同目录的 ui 包：
//     android-tv/app/src/main/java/com/xiaopacai/tvos/ui/TvHomeActivity.kt
//
// ── 你一直在困惑的事，一次说清 ──
// 1. 你此前的 Turn 1 在 xiaopacai/android/app-tv/ 下创建了 MainActivity.kt / MainMenuScreen.kt，
//    那违反 TASK.md §Scope（android/ 是【只读参照区】，明确"禁止改"）。
// 2. 那些文件已被人工清理并移入废纸篓 —— 【不是丢失，是清理】。
// 3. 继续 find/ls 搜索它们没有任何意义，只会让上下文膨胀、每轮 prompt 处理耗时数分钟。
// 4. android-tv/ 才是你的任务目录，而且【已经包含完整实现】（不是空目录）：
//      ui/TvHomeActivity.kt、ui/lock/、ui/whitelist/、ui/bedtime/、ui/announcement/、
//      ui/settings/、ui/theme/、service/LockOverlayService.kt（356 行，锁屏已实现）、util/
//
// ── 立即停止探索，按顺序做这 3 件事 ──
//   1. 读 android-tv/AGENTS.md            （L1 规则 + 单次 write ≤400 行且 ≤12000 字符 + §七 续接协议）
//   2. 读 android-tv/TASK.md              （7 条机器可判的验收标准）
//   3. 读 android-tv/docs/CHECKPOINT.json （从 next_action 继续 = TASK-TV-05：超时锁屏 E2E）
//
// 读完后【只做 next_action 指定的那一个原子动作】，不要顺手做别的。
//
// ── 禁令（AGENTS.md §2.1 / §2.3） ──
//   ❌ 不得在 xiaopacai/ 下创建或修改任何文件
//   ❌ 不得用 write 重写已存在的大文件（应逐段 edit）
//   ❌ 不得用完全相同的参数重试失败的调用
//   ❌ 不得连续探索超过 2 次而不推进任务
//
// 本文件仅含注释，不含任何实现，不得作为代码基础，也不得被 import。
