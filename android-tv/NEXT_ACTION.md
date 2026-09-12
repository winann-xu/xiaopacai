# NEXT_ACTION.md — 独立指令文件（监控方维护，执行方**只读**）

> 最后更新：2026-09-10 18:25 ｜ 本文件请勿编辑，避免与监控方写冲突。

## 🎉 环境已补齐 —— 你之前 blocked 的 5 项任务全部解锁

| 组件 | 状态 | 位置 / 命令 |
|---|---|---|
| **JDK 17**（Temurin 17.0.20.1） | ✅ 已装 | `$HOME/jdk/jdk-17.0.20.1+1/Contents/Home` |
| **Gradle** | ✅ 8.9，已用 JDK17 验证 | `cd android-tv && ./gradlew test` |
| **Android emulator** | ✅ 37.1.11.0 | `$HOME/Library/Android/sdk/emulator/emulator` |
| **Android TV 系统镜像** | ✅ 4.1G | `system-images;android-31;android-tv;arm64-v8a` |
| **AVD `XiaopacaiTV`** | ✅ **正在运行** | `adb devices` → `emulator-5554  device`（sdk=31, `sdk_google_atv64_arm64`）|
| **.NET SDK** | ✅ 8.0.425 | `$HOME/.dotnet/dotnet` |

环境变量已写入 `~/.zshrc`：`JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `DOTNET_ROOT`，
`PATH` 已含 `$JAVA_HOME/bin`、`$DOTNET_ROOT`、`platform-tools`、`emulator`。

## 你的任务状态（`docs/CHECKPOINT.json` 已同步更新）

```
✅ done    : TV-01 构建 · TV-02 LEANBACK · TV-08 清理 · TV-09 文档归位
⬜ pending : TV-03 单测全绿 · TV-04 心跳闭环 · TV-05 锁屏 E2E · TV-06 D-pad 走查 · TV-07 五项用例
```

## 建议执行顺序（依赖驱动，**一次一个原子动作**）

### 第 1 步 · `TASK-TV-03` 单测基线（先确认真实状态）
```
cd android-tv && ./gradlew test
```
- 观察此前记录的 29 个失败是否仍存在（原判为 Robolectric ARM 缺原生库，JDK 就位后可能已缓解）
- **如实记录**：若仍失败，写清失败数量与原因，不要粉饰
- 完成后更新 `docs/CHECKPOINT.json` 的 `evidence` 字段

### 第 2 步 · `TASK-TV-05` 超时锁屏 E2E（核心验收）
- 装机：`./gradlew installDebug` 或 `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 截图用：`adb exec-out screencap -p > /tmp/shot.png`
  （AVD 以 `-no-window` 无头模式运行，**不能**靠观察窗口截图）
- 覆盖：锁屏出现 / 倒计时 / 家长豁免解除 / 次日重置；并验证 HOME 键不可绕过
- 脚本骨架已存在：`android-tv/tests/gui/LockOverlayE2ETest.py`（70 行，可继续补全）

### 第 3 步 · `TASK-TV-06` / `TASK-TV-07`
- D-pad 焦点走查 + 五项真实用例（白名单 / 就寝 / 公告 / 豁免请求 / 篡改告警）

### 并行可选 · `TASK-TV-04` 心跳闭环
- 先补 `xiaopacai-web/docs/e2e/heartbeat_test.py`，再用 `~/.dotnet/dotnet run` 起服务验证
- 与 TV-05 无依赖，可穿插进行

## 铁律（沿用；违反的历史事故见 CHECKPOINT `deviations`）

1. **单次 `write` ≤ 400 行且 ≤ 12000 字符**；大文件先写骨架再 `edit` 追加（DEV-02 教训）
2. **禁止**用 `write` 重写已存在的大文件；修改一律用 `edit`
3. **禁止**在 `xiaopacai/` 下创建或修改任何文件（只读参照区，DEV-01 教训）
4. **禁止**用相同参数重试失败调用；同一动作连续失败 2 次即停止，写入 `PROGRESS.md` 的 `Failed` 段
5. **禁止**连续只读探索 >2 次而不推进任务（DEV-04 教训）
6. 进度更新只在 `PROGRESS.md` 的 `## Status` **追加 3-5 行**，不要重写、不要反复润色（DEV-05 教训）

## 关于你此前遇到的 `file changed since it was read`

那是监控方为下发指令而并发修改 `PROGRESS.md` 造成的（该做法已停止，现改用本只读文件）。
**不是**后台 watcher、**不是**索引服务、**不是**文件系统故障、**不是** DSH bug。
冲突源已消除，现在可正常编辑。
