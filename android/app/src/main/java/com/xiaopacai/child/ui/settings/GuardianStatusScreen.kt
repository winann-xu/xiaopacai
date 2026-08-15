package com.xiaopacai.child.ui.settings

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.service.AntiBypassService
import com.xiaopacai.child.service.DiagnosticsCollector
import com.xiaopacai.child.service.GuardianDeviceAdminReceiver
import com.xiaopacai.child.ui.components.SystemGateDialog
import com.xiaopacai.child.ui.theme.XiaopacaiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [TASK-OPT-12-P2] 守护状态页（需求6）
 *
 * 展示各守护能力就绪状态，并提供一键引导修复：
 * - 设备管理器（防卸载核心）
 * - 无障碍服务（超时拦截）
 * - 使用情况访问（时长采集）
 * - 开机自启动（防重启不启动）
 * - 电池优化（保活）
 *
 * 附故障诊断区：显示上次上报内容摘要 + "立即上报"按钮（需求5 手动触发）。
 */

/**
 * 守护状态页 Activity 包装
 */
class GuardianStatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaopacaiTheme(darkTheme = false) {
                GuardianStatusScreen(onBack = { finish() })
            }
        }
    }
}

/** 守护状态条目数据模型 */
private data class GuardianStatusItem(
    val key: String,
    val title: String,
    val description: String,
    val ready: Boolean,
    val guideLabel: String,
    val guideAction: (Context) -> Unit,
    val manageLabel: String? = null,
    val manageAction: ((Context) -> Unit)? = null
)

/**
 * 守护状态页主体
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianStatusScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // [TASK-ACCOUNT-V1] 解除设备管理器必须家长云端验证（防儿童自行卸载；离线拒绝）
    var showUnlockAdminDialog by remember { mutableStateOf(false) }

    // 诊断上报状态提示
    var reportMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    val items = remember {
        buildStatusItems(
            context,
            onRequestUnlockDeviceAdmin = { showUnlockAdminDialog = true }
        )
    }
    val readyCount = items.count { it.ready }

    // [TASK-ACCOUNT-V1] 家长云端验证：解除设备管理器（允许卸载）前必须验证
    if (showUnlockAdminDialog) {
        SystemGateDialog(
            title = "家长验证",
            description = "解除设备管理器后才能卸载小趴菜，请输入家长账号邮箱与登录密码。",
            confirmText = "验证并解除",
            onDismiss = { showUnlockAdminDialog = false },
            onVerified = {
                showUnlockAdminDialog = false
                try {
                    val dpm = GuardianDeviceAdminReceiver.getDpm(context)
                    dpm.removeActiveAdmin(
                        GuardianDeviceAdminReceiver.getComponentName(context)
                    )
                    Toast.makeText(
                        context,
                        "设备保护已解除，现在可以卸载应用",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "解除失败：${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("守护状态", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
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
            // 总览卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (readyCount == items.size)
                        Color(0xFFE8F5E9)
                    else
                        Color(0xFFFFF3E0)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (readyCount == items.size) "✅ 守护一切就绪" else "⚠️ 有 ${items.size - readyCount} 项需要修复",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (readyCount == items.size) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Text(
                        text = "就绪 $readyCount / ${items.size}",
                        fontSize = 12.sp,
                        color = Color(0xFF795548),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // 各守护项状态
            items.forEach { item ->
                GuardianStatusCard(item)
            }

            // 诊断上报区（需求5 手动触发 + 家长可关闭）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔧 故障诊断上报",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        // 诊断上报总开关（默认开启，家长可关闭）
                        Switch(
                            checked = DiagnosticsCollector.isEnabled(context),
                            onCheckedChange = {
                                DiagnosticsCollector.setEnabled(context, it)
                                Toast.makeText(
                                    context,
                                    if (it) "诊断上报已开启" else "诊断上报已关闭",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                    Text(
                        text = "上报应用版本、Android 版本、设备型号、权限状态、服务状态、最近崩溃、P2P 连接历史、数据库大小、网络类型。内容仅用于排查问题，请家长知悉。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (reportMessage != null) {
                        Text(
                            text = reportMessage!!,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Button(
                        onClick = {
                            // 在 IO 线程执行采集与发送
                            coroutineScope.launch {
                                val sent = withContext(Dispatchers.IO) {
                                    DiagnosticsCollector.report(context)
                                }
                                reportMessage = if (sent) {
                                    "已上报给家长端 ✓"
                                } else {
                                    "家长端未连接，报告已缓存，重连后自动补传"
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text("立即上报", fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 单条守护状态卡片：状态指示 + 说明 + 一键修复按钮
 */
@Composable
private fun GuardianStatusCard(item: GuardianStatusItem) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (item.ready) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已就绪",
                        tint = Color(0xFF2E7D32)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "未就绪",
                        tint = Color(0xFFE65100)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 标题 + 说明
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = item.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // 一键修复按钮（未就绪时显示）
            if (!item.ready) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { item.guideAction(context) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(item.guideLabel, fontSize = 12.sp)
                }
            }

            // [REQ] 已就绪但提供“管理”操作（如家长解除设备管理器后卸载）
            if (item.ready && item.manageLabel != null && item.manageAction != null) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { item.manageAction?.invoke(context) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(item.manageLabel, fontSize = 12.sp)
                }
            }
        }
    }
}

// ==================== 状态构建与跳转 ====================

/**
 * 构建守护状态条目列表
 */
private fun buildStatusItems(
    context: Context,
    onRequestUnlockDeviceAdmin: () -> Unit
): List<GuardianStatusItem> {
    return listOf(
        // 1. 设备管理器（防卸载核心）
        GuardianStatusItem(
            key = "deviceAdmin",
            title = "设备管理器",
            description = "防卸载核心保护，需在系统设置中激活\n开启路径：设置 → 安全/密码与安全 → 设备管理应用 → 小趴菜 → 激活",
            ready = GuardianDeviceAdminReceiver.isActive(context),
            guideLabel = "去激活",
            guideAction = { ctx ->
                try {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            GuardianDeviceAdminReceiver.getComponentName(ctx)
                        )
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "激活后可防止小趴菜被卸载，请点击激活按钮"
                        )
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "无法跳转设备管理器设置", Toast.LENGTH_SHORT).show()
                }
            },
            // [REQ] 已激活时提供“解除保护”：家长密码验证后 removeActiveAdmin，之后可卸载
            manageLabel = "解除保护",
            manageAction = { _ -> onRequestUnlockDeviceAdmin() }
        ),
        // 2. 无障碍服务（超时拦截）
        GuardianStatusItem(
            key = "accessibility",
            title = "无障碍服务",
            description = "超时停用拦截的核心通道\n开启路径：设置 → 无障碍/辅助功能 → 已安装的服务 → 小趴菜 → 打开开关",
            ready = AntiBypassService.isAccessibilityServiceEnabled(context),
            guideLabel = "去开启",
            guideAction = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        ),
        // 3. 使用情况访问（时长采集）
        GuardianStatusItem(
            key = "usageStats",
            title = "使用情况访问",
            description = "采集各应用使用时长的基础权限\n开启路径：设置 → 应用 → 小趴菜 → 权限 → 使用情况访问权限 → 允许",
            ready = AntiBypassService.isUsageStatsPermissionGranted(context),
            guideLabel = "去授权",
            guideAction = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        ),
        // 4. 开机自启动（防重启不启动）
        GuardianStatusItem(
            key = "bootAutoStart",
            title = "开机自启动",
            description = "设备重启后自动恢复守护\n开启路径：设置 → 应用管理 → 小趴菜 → 自启动/开机启动 → 允许（各品牌入口不同，见初始化引导页）",
            ready = hasBootPermission(context),
            guideLabel = "去设置",
            guideAction = { ctx ->
                // 跳转应用详情页（OEM 自启动管理入口因厂商而异，详情页最通用）
                try {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    })
                } catch (e: Exception) {
                    Toast.makeText(ctx, "无法跳转应用设置", Toast.LENGTH_SHORT).show()
                }
            }
        ),
        // 5. 电池优化（保活）
        GuardianStatusItem(
            key = "battery",
            title = "电池优化",
            description = "关闭后防止后台守护被杀\n开启路径：设置 → 应用 → 小趴菜 → 电池/耗电 → 允许后台运行、不受限制",
            ready = !AntiBypassService.isBatteryOptimizationEnabled(context),
            guideLabel = "去关闭",
            guideAction = { ctx ->
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                        }
                    )
                } catch (e: Exception) {
                    // 部分 ROM 不支持直接跳转，退回应用详情页
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    })
                }
            }
        )
    )
}

/**
 * 检查开机自启动权限（RECEIVE_BOOT_COMPLETED 是否已授予）
 */
private fun hasBootPermission(context: Context): Boolean {
    return try {
        context.packageManager.checkPermission(
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
            context.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }
}
