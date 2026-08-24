package com.xiaopacai.child.ui.strict

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.adbshell.AdbOutputParser
import com.xiaopacai.child.adbshell.AdbPairingDiscovery
import com.xiaopacai.child.adbshell.AdbRunner
import com.xiaopacai.child.adbshell.ProvisionMachine
import com.xiaopacai.child.adbshell.StrictPreconditions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [TASK-STRICT-PROVISION-V1] 强管制模式引导页（ADR 0018）
 *
 * 流程：前置检查 → 分步引导（开发者选项/无线调试）→ 输入配对码 →
 * 自配对（App 内嵌官方 adb，LADB 模式）→ dpm set-device-owner → 完成。
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

    var host by remember { mutableStateOf("") }
    var adbPort by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var discovered by remember { mutableStateOf<AdbPairingDiscovery.DiscoveredAdbServices?>(null) }
    var discoveryError by remember { mutableStateOf<String?>(null) }

    val discovery = remember {
        AdbPairingDiscovery(
            scope = scope,
            onFound = { svc ->
                discovered = svc
                host = svc.host
                adbPort = svc.adbPort.toString()
                pairPort = svc.pairingPort.toString()
                discoveryError = null
            },
            onError = { discoveryError = it }
        )
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

    fun startProvision() {
        running = true
        showConfirm = false
        step = ProvisionMachine.next(step, ProvisionMachine.Event.GuideDone)
        scope.launch(Dispatchers.IO) {
            val runner = AdbRunner.create(context)
            val h = host.trim()
            val p = pairPort.trim().toIntOrNull()
            val a = adbPort.trim().toIntOrNull()
            var nextStep = step

            // 1) 配对
            message = "正在配对（配对码一次性有效，请保持系统配对页打开）…"
            val pairRes = runner.pair(h, p ?: 0, code.trim())
            if (pairRes == null) {
                nextStep = ProvisionMachine.next(
                    nextStep, ProvisionMachine.Event.PairFailed
                )
            } else if (AdbOutputParser.classifyPair(pairRes.exitCode, pairRes.output)
                != AdbOutputParser.PairOutcome.SUCCESS
            ) {
                message = "配对失败：${pairRes.output.ifBlank { "请检查配对码与端口" }}"
                nextStep = ProvisionMachine.next(nextStep, ProvisionMachine.Event.PairFailed)
            } else {
                message = "配对成功，正在连接无线调试…"
                nextStep = ProvisionMachine.next(nextStep, ProvisionMachine.Event.PairOk)

                // 2) 连接无线调试端口
                val connRes = runner.connect(h, a ?: 0)
                if (connRes == null ||
                    AdbOutputParser.classifyConnect(connRes.exitCode, connRes.output)
                    != AdbOutputParser.ConnectOutcome.SUCCESS
                ) {
                    message = "连接失败：${connRes?.output?.ifBlank { "无法连接无线调试端口" } ?: "参数无效"}"
                    nextStep = ProvisionMachine.next(nextStep, ProvisionMachine.Event.ConnectFailed)
                } else {
                    message = "已连接，正在执行系统级预置（dpm set-device-owner）…"
                    nextStep = ProvisionMachine.next(nextStep, ProvisionMachine.Event.ConnectOk)

                    // 3) 执行 Device Owner 预置（白名单命令，无线调试会话直连）
                    val serial = "$h:${a ?: 0}"
                    val dpmRes = runner.shell(
                        serial,
                        "dpm set-device-owner com.xiaopacai.child/.service.GuardianDeviceAdminReceiver"
                    )
                    runner.killServer()
                    val outcome = if (dpmRes == null) {
                        AdbOutputParser.DpmOutcome.UNKNOWN_FAILURE
                    } else {
                        AdbOutputParser.classifyDpm(dpmRes.exitCode, dpmRes.output)
                    }
                    nextStep = when (outcome) {
                        AdbOutputParser.DpmOutcome.SUCCESS -> {
                            message = "强管制模式已激活 ✓"
                            ProvisionMachine.next(nextStep, ProvisionMachine.Event.ProvisionOk)
                        }
                        else -> {
                            message = when (outcome) {
                                AdbOutputParser.DpmOutcome.ACCOUNTS_PRESENT ->
                                    "预置被系统拒绝：设备存在账号。请先恢复出厂或在无账号状态下操作（本操作不自动重试）。"
                                AdbOutputParser.DpmOutcome.ALREADY_DEVICE_OWNER ->
                                    "设备已是 Device Owner，无需重复预置。"
                                AdbOutputParser.DpmOutcome.TEST_ONLY_BUILD ->
                                    "当前为调试包，请安装正式 Release 版本后重试。"
                                AdbOutputParser.DpmOutcome.ROM_REJECTED ->
                                    "本机型系统拒绝第三方 Device Owner 预置（定制 ROM 限制），当前不支持强管制模式。"
                                else -> "预置失败：${dpmRes?.output?.ifBlank { "未知错误" } ?: "adb 执行失败"}"
                            }
                            ProvisionMachine.next(
                                nextStep, ProvisionMachine.Event.ProvisionFailed(outcome)
                            )
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                step = nextStep
                running = false
            }
        }
    }

    // 启动前置检查
    LaunchedEffect(Unit) {
        step = ProvisionMachine.next(ProvisionMachine.Step.Idle, ProvisionMachine.Event.Start)
        preCheck()
    }
    DisposableEffect(Unit) {
        onDispose { discovery.stop() }
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
                        "· 解除方式：恢复出厂设置（受控解除界面将在后续版本提供）。"
                )
            },
            confirmButton = {
                TextButton(onClick = { startProvision() }) { Text("确认执行") }
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
                        step = ProvisionMachine.next(s, ProvisionMachine.Event.Retry)
                    },
                    onBack = onBack
                )

                ProvisionMachine.Step.PreCheck ->
                    Text(if (running) "正在检查设备条件…" else "前置检查未通过", fontSize = 14.sp)

                ProvisionMachine.Step.Guide -> GuideCard(
                    host = host,
                    adbPort = adbPort,
                    pairPort = pairPort,
                    code = code,
                    discovered = discovered,
                    discoveryError = discoveryError,
                    running = running,
                    onHostChange = { host = it },
                    onAdbPortChange = { adbPort = it },
                    onPairPortChange = { pairPort = it },
                    onCodeChange = { code = it },
                    onDiscover = {
                        discoveryError = null
                        discovered = null
                        discovery.start()
                    },
                    onStart = {
                        if (host.isBlank() || adbPort.isBlank() || pairPort.isBlank() || code.length != 6) {
                            Toast.makeText(context, "请先完成发现或填写 IP、端口与 6 位配对码", Toast.LENGTH_SHORT).show()
                        } else {
                            showConfirm = true
                        }
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
    host: String,
    adbPort: String,
    pairPort: String,
    code: String,
    discovered: AdbPairingDiscovery.DiscoveredAdbServices?,
    discoveryError: String?,
    running: Boolean,
    onHostChange: (String) -> Unit,
    onAdbPortChange: (String) -> Unit,
    onPairPortChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onDiscover: () -> Unit,
    onStart: () -> Unit,
    onOpenDeveloperOptions: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("第一步：开启无线调试", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "1. 打开「开发者选项」（本页下方按钮直达）；\n" +
                    "2. 开启「USB 调试」；\n" +
                    "3. 开启「无线调试」，并点按「使用配对码配对设备」（保持该页面打开）。\n" +
                    "OPPO/ColorOS 注意：开发者选项内请关闭「权限监控」。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onOpenDeveloperOptions, enabled = !running) {
                Text("打开开发者选项")
            }

            Divider()

            Text("第二步：填写配对信息", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Button(onClick = onDiscover, enabled = !running) {
                Text("自动发现（推荐）")
            }
            if (discovered != null) {
                Text(
                    "已发现：${discovered.host}（配对端口 ${discovered.pairingPort} / 调试端口 ${discovered.adbPort}）",
                    fontSize = 12.sp,
                    color = Color(0xFF2E7D32)
                )
            }
            if (discoveryError != null) {
                Text(discoveryError, fontSize = 12.sp, color = Color(0xFFE65100))
            }

            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("本机 IP 地址（如 192.168.1.5）") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = adbPort,
                    onValueChange = onAdbPortChange,
                    label = { Text("无线调试端口") },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = pairPort,
                    onValueChange = onPairPortChange,
                    label = { Text("配对端口") },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = code,
                onValueChange = { v -> onCodeChange(v.filter { it.isDigit() }.take(6)) },
                label = { Text("6 位配对码") },
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onStart, enabled = !running && code.length == 6) {
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
