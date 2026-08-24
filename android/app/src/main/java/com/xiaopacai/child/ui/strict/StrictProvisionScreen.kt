package com.xiaopacai.child.ui.strict

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.adbshell.ProvisionMachine
import com.xiaopacai.child.adbshell.StrictPreconditions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [TASK-STRICT-PROVISION-V1] 强管制模式引导页（ADR 0018）
 *
 * 流程：前置检查 → 分步引导（开发者选项/无线调试）→ 通知栏输入配对码 →
 * 后台自配对（App 内嵌官方 adb，LADB 模式）→ dpm set-device-owner → 完成。
 * v1.3.2：ColorOS 真机实测配对服务仅在设置页前台存活，配对码改由
 * 通知栏内联输入（Shizuku 同款），用户无需离开系统设置页。
 * 安全红线：执行前二次确认；dpm 失败分类提示、不自动重试（状态机保障）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrictProvisionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf<ProvisionMachine.Step>(ProvisionMachine.Step.Idle) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    /** 直接进入通知栏配对引导（通知权限已确认） */
    fun beginProvisionFlow() {
        running = true
        showConfirm = false
        step = ProvisionMachine.next(step, ProvisionMachine.Event.GuideDone)
        PairingStatusStore.reset()
        message = "请在通知栏输入配对码…"
        PairingCodeNotification.showAwaitingCode(context)
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginProvisionFlow()
        } else {
            message = "未授予通知权限，无法使用通知栏配对；请在系统设置中允许通知后重试"
            running = false
        }
    }

    fun preCheck() {
        running = true
        scope.launch(Dispatchers.IO) {
            val binaryPresent = runCatching {
                java.io.File(context.applicationInfo.nativeLibraryDir, "libadb.so").exists()
            }.getOrDefault(false)
            val isDo = runCatching {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isDeviceOwnerApp(context.packageName)
            }.getOrDefault(false)
            val result = StrictPreconditions.evaluate(
                sdkInt = Build.VERSION.SDK_INT,
                isDeviceOwner = isDo,
                binaryPresent = binaryPresent
            )
            val next = when (result) {
                StrictPreconditions.PreconditionResult.Ok ->
                    ProvisionMachine.next(step, ProvisionMachine.Event.PreCheckOk)
                StrictPreconditions.PreconditionResult.SdkTooOld ->
                    ProvisionMachine.next(
                        step, ProvisionMachine.Event.PreCheckFailed(ProvisionMachine.ProvisionError.SDK_TOO_OLD)
                    )
                StrictPreconditions.PreconditionResult.AlreadyActive ->
                    ProvisionMachine.next(
                        step, ProvisionMachine.Event.PreCheckFailed(ProvisionMachine.ProvisionError.ALREADY_ACTIVE)
                    )
                StrictPreconditions.PreconditionResult.BinaryMissing ->
                    ProvisionMachine.next(
                        step, ProvisionMachine.Event.PreCheckFailed(ProvisionMachine.ProvisionError.BINARY_MISSING)
                    )
            }
            withContext(Dispatchers.Main) {
                step = next
                running = false
            }
        }
    }

    fun startNotificationProvision() {
        running = true
        showConfirm = false
        step = ProvisionMachine.next(step, ProvisionMachine.Event.GuideDone)
        PairingStatusStore.reset()
        // Android 13+ 通知需运行时授权；通知是通知栏配对的唯一入口
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            message = "需要通知权限：请允许后，在通知栏输入配对码"
        } else {
            beginProvisionFlow()
        }
    }

    // 后台预置服务进度 → 界面状态机
    val pairingStatus by PairingStatusStore.status.collectAsState()
    LaunchedEffect(pairingStatus) {
        when (pairingStatus) {
            is PairingStatusStore.Status.AwaitingCode -> {
                message = "请在通知栏输入配对码…"
            }
            is PairingStatusStore.Status.Running -> {
                message = (pairingStatus as PairingStatusStore.Status.Running).stepText
            }
            is PairingStatusStore.Status.Succeeded -> {
                running = false
                PairingCodeNotification.cancel(context)
                message = "强管制模式已激活 ✓"
                step = ProvisionMachine.next(step, ProvisionMachine.Event.PairOk)
                step = ProvisionMachine.next(step, ProvisionMachine.Event.ConnectOk)
                step = ProvisionMachine.next(step, ProvisionMachine.Event.ProvisionOk)
            }
            is PairingStatusStore.Status.Failed -> {
                running = false
                val failed = pairingStatus as PairingStatusStore.Status.Failed
                message = failed.message
                step = when (failed.error) {
                    ProvisionMachine.ProvisionError.PAIR_FAILED,
                    ProvisionMachine.ProvisionError.DISCOVERY_FAILED ->
                        ProvisionMachine.next(step, ProvisionMachine.Event.PairFailed)
                    ProvisionMachine.ProvisionError.CONNECTION_FAILED -> {
                        val afterPair = ProvisionMachine.next(step, ProvisionMachine.Event.PairOk)
                        ProvisionMachine.next(afterPair, ProvisionMachine.Event.ConnectFailed)
                    }
                    else -> {
                        val afterPair = ProvisionMachine.next(step, ProvisionMachine.Event.PairOk)
                        val afterConnect = ProvisionMachine.next(afterPair, ProvisionMachine.Event.ConnectOk)
                        ProvisionMachine.next(
                            afterConnect,
                            ProvisionMachine.Event.ProvisionFailed(
                                failed.dpmOutcome
                                    ?: com.xiaopacai.child.adbshell.AdbOutputParser.DpmOutcome.UNKNOWN_FAILURE
                            )
                        )
                    }
                }
            }
            else -> Unit
        }
    }

    // 启动前置检查
    LaunchedEffect(Unit) {
        step = ProvisionMachine.next(ProvisionMachine.Step.Idle, ProvisionMachine.Event.Start)
        preCheck()
    }
    BackHandler(enabled = running) { /* 运行中禁止返回，避免中断预置 */ }

    // 二次确认
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("确认执行系统级预置？") },
            text = {
                Text(
                    "将把小趴菜设为设备的 Device Owner（强管制模式）。\n\n" +
                        "· 需在无账号/出厂重置状态下才能成功；\n" +
                        "· 失败可能触发系统清数据，本应用不会自动重试；\n" +
                        "· 解除方式：恢复出厂设置（受控解除界面将在后续版本提供）；\n" +
                        "· 确认后请保持系统「无线调试」配对弹窗页面前台，在通知栏输入配对码。"
                )
            },
            confirmButton = {
                TextButton(onClick = { startNotificationProvision() }) { Text("确认执行") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("强管制模式", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !running) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val s = step) {
                is ProvisionMachine.Step.Failed -> FailureCard(
                    s, message,
                    onRetry = {
                        // 重试：回到配对步并重新展示通知栏输入引导
                        running = true
                        PairingStatusStore.reset()
                        step = ProvisionMachine.next(s, ProvisionMachine.Event.Retry)
                        message = "请在通知栏输入新配对码…（请重新点按「使用配对码配对设备」获取新码）"
                        PairingCodeNotification.showAwaitingCode(context)
                    },
                    onBack = onBack
                )

                ProvisionMachine.Step.PreCheck ->
                    Text(if (running) "正在检查设备条件…" else "前置检查未通过", fontSize = 14.sp)

                ProvisionMachine.Step.Guide -> GuideCard(
                    running = running,
                    onStart = {
                        showConfirm = true
                    },
                    onOpenDeveloperOptions = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }.onFailure {
                            Toast.makeText(context, "无法打开开发者选项，请手动进入系统设置", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                ProvisionMachine.Step.Pair, ProvisionMachine.Step.Connect,
                ProvisionMachine.Step.Provision ->
                    Text(message.ifBlank { "执行中…" }, fontSize = 14.sp)

                ProvisionMachine.Step.Done -> DoneCard(onBack)
                ProvisionMachine.Step.Idle -> Text("准备中…", fontSize = 14.sp)
            }

            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun GuideCard(
    running: Boolean,
    onStart: () -> Unit,
    onOpenDeveloperOptions: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("第一步：开启无线调试", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "1. 打开「开发者选项」（本页下方按钮直达）；\n" +
                    "2. 开启「USB 调试」；\n" +
                    "3. 开启「无线调试」，并点按「使用配对码配对设备」，保持该页面打开。\n" +
                    "注意：配对码约 2 分钟内有效、每次弹窗都会变化；超时请重新点按弹窗获取新码。\n" +
                    "OPPO/ColorOS 注意：开发者选项内请关闭「权限监控」（中文版隐藏时切英文开启 Disable system optimization）。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onOpenDeveloperOptions, enabled = !running) {
                Text("打开开发者选项")
            }

            Divider()

            Text("第二步：通知栏输入配对码", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "点「开始配对并预置」后，下拉通知栏，在小趴菜配对通知中输入「配对端口:配对码」。\n" +
                    "配对端口 = 配对弹窗里「IP 地址和端口」冒号后的数字（如 39019:123456）。\n" +
                    "（保持系统配对弹窗页面打开，无需切换回本应用；配对与预置在后台自动完成）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onStart, enabled = !running) {
                Text("开始配对并预置")
            }
        }
    }
}

@Composable
private fun FailureCard(
    failed: ProvisionMachine.Step.Failed,
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val isAlreadyActive = failed.error == ProvisionMachine.ProvisionError.ALREADY_ACTIVE
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAlreadyActive) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (failed.error) {
                    ProvisionMachine.ProvisionError.SDK_TOO_OLD ->
                        "当前设备为 Android ${Build.VERSION.SDK_INT}，强管制模式需要 Android 11 及以上（无线调试）。"
                    ProvisionMachine.ProvisionError.ALREADY_ACTIVE ->
                        "小趴菜已是本设备的 Device Owner（强管制已激活）。"
                    ProvisionMachine.ProvisionError.BINARY_MISSING ->
                        "内置 adb 组件缺失，请重新安装最新版本。"
                    ProvisionMachine.ProvisionError.PAIR_FAILED,
                    ProvisionMachine.ProvisionError.DISCOVERY_FAILED ->
                        "配对未成功。$message"
                    ProvisionMachine.ProvisionError.CONNECTION_FAILED ->
                        "连接未成功。$message"
                    ProvisionMachine.ProvisionError.DPM_ACCOUNTS_PRESENT ->
                        "预置被拒：设备存在账号。$message"
                    ProvisionMachine.ProvisionError.DPM_ALREADY_SET ->
                        "设备已是 Device Owner。$message"
                    ProvisionMachine.ProvisionError.DPM_TEST_ONLY ->
                        "当前为调试包。$message"
                    ProvisionMachine.ProvisionError.DPM_ROM_REJECTED ->
                        "定制 ROM 拒绝预置。$message"
                    ProvisionMachine.ProvisionError.DPM_UNKNOWN ->
                        "预置失败：$message"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (failed.retryable) {
                Button(onClick = onRetry) { Text("重试") }
            } else if (isAlreadyActive) {
                Button(onClick = onBack) { Text("完成") }
            } else {
                Text(
                    "该状态不会自动重试；可回到上一步检查后重新进入。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DoneCard(onBack: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✓ 强管制模式已激活", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF2E7D32))
            Text(
                "小趴菜已是本设备的 Device Owner：防卸载最强、可系统级管控。\n\n" +
                    "说明（能力边界如实告知）：\n" +
                    "· 授权已完成，日常运行不再依赖无线调试/电脑；\n" +
                    "· 解除方式：恢复出厂设置（受控解除界面将在后续版本提供）；\n" +
                    "· 安全模式启动、Recovery 恢复出厂、root 设备仍可能绕过，无法绝对锁定。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onBack) { Text("完成") }
        }
    }
}
