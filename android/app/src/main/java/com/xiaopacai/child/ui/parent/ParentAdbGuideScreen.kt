package com.xiaopacai.child.ui.parent

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [REQ] 家长端首页「孩子手机快速授权指南」
 *
 * 面向家长：孩子手机安装小趴菜后，只需在电脑上执行几条 ADB 命令，
 * 全部权限即刻开通；本页命令已在 OPPO PKV110 / ColorOS / Android 16 真机验证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentAdbGuideScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 5 条真机验证命令（与儿童端权限引导页一致）
    val adbCmds = buildString {
        appendLine("adb shell pm grant com.xiaopacai.child android.permission.POST_NOTIFICATIONS")
        appendLine("adb shell appops set com.xiaopacai.child GET_USAGE_STATS allow")
        appendLine("adb shell dumpsys deviceidle whitelist +com.xiaopacai.child")
        appendLine("adb shell settings put secure enabled_accessibility_services com.xiaopacai.child/.service.GuardianAccessibilityService")
        appendLine("adb shell settings put secure accessibility_enabled 1")
    }.trimEnd()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("孩子手机快速授权指南") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 概览
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, null, Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("电脑 ADB 一键授权 · 约 30 秒", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在电脑上执行下面 5 条命令，孩子手机的使用情况/通知/电池/无障碍权限即刻全部开通。" +
                            "手机端会自动检测（约 2 秒），全部开通后自动进入儿童端，设置一次永久生效（重启自动恢复）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 步骤一
            StepCard(step = "1", title = "孩子手机：开启 USB 调试")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GuideLine("① 设置 → 关于本机 → 连续点击「版本号」7 次，打开开发者选项")
                    GuideLine("② 设置 → 开发者选项 → 打开「USB 调试」")
                    GuideLine("③ 用 USB 线连接电脑；手机弹窗「允许 USB 调试」时点【允许】")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "没有 USB 线？可在开发者选项里开「无线调试」，电脑执行 adb pair / adb connect 连接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 步骤二
            StepCard(step = "2", title = "电脑：安装并打开 ADB 工具")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GuideLine("① 电脑下载 Android 官方「platform-tools」并解压")
                    GuideLine("② 在解压目录打开命令行（Windows 按住 Shift 右键 → 打开 PowerShell）")
                    GuideLine("③ 先输入 adb devices，能列出孩子手机即为连接成功")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "更省事：到小趴菜下载中心（web 端 → 下载中心）下载「电脑一键授权脚本」，双击运行，按提示连接手机即可，无需手动输入命令。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 步骤三
            StepCard(step = "3", title = "电脑：复制并执行命令")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = adbCmds,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", adbCmds))
                            Toast.makeText(context, "5 条命令已复制，请粘贴到电脑执行", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制全部命令")
                    }
                }
            }

            // 完成后
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("完成后无需任何操作",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "手机端每 2 秒自动检测：5 条命令执行完，儿童端自动进入守护主页；重启手机也会自动恢复，无需重复设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // 常见问题
            StepCard(step = "？", title = "常见问题")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GuideLine("① 执行命令报「device not found」：确认 adb devices 能看到手机，弹窗点过允许")
                    GuideLine("② 命令提示 unknown/错误：确认复制的是 5 条完整命令，且每条单独一行")
                    GuideLine("③ 无障碍权限在部分国产手机强停应用后可能回退：正常使用不受影响，重启可恢复")
                    GuideLine("④ 安装新版本小趴菜不需要重新授权，权限跟随系统一直保留")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 步骤标题卡 */
@Composable
private fun StepCard(step: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(26.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Text(step, color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** 单行说明 */
@Composable
private fun GuideLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 3.dp)
    )
}
