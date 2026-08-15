package com.xiaopacai.child.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [TASK-D1-02] 权限引导页（快捷版）
 *
 * 引导家长/儿童完成小趴菜所需的系统权限：
 * 1. 使用情况访问 2. 无障碍服务 3. 通知权限 4. 忽略电池优化（5. 厂商自启动，可选）
 *
 * 快捷能力：
 * - “一键引导”：自动按顺序打开每一项，从设置页返回后自动跳到下一项，减少手动来回
 * - 通知权限：Android 13+ 直接弹系统授权对话框（不再进设置页翻开关）
 * - 电池优化：优先直接请求对话框，失败回退通用设置页
 * - 进度显示 + 厂商自启动深链（小米/华为/OPPO/vivo）
 */
@Composable
fun PermissionGuideScreen(
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // 实时权限状态
    var usageStatsGranted by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var accessibilityGranted by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var batteryGranted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var autoStartGranted by remember { mutableStateOf(isAutoStartGranted(context)) }

    // 一键引导模式：从设置页返回且某项刚授权时，自动打开下一项
    var autoGuide by remember { mutableStateOf(false) }

    fun grantedCount(): Int =
        listOf(usageStatsGranted, accessibilityGranted, notificationGranted, batteryGranted).count { it }

    var lastGrantedCount by remember { mutableIntStateOf(grantedCount()) }

    // 通知权限：直接请求系统授权对话框
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationGranted = granted }

    // 打开“下一项待授权”的设置/请求
    fun openNextPending() {
        when {
            !usageStatsGranted -> openPermissionSettings(
                context,
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                { appDetailsIntent(context) })
            !accessibilityGranted -> openPermissionSettings(
                context,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                { appDetailsIntent(context) })
            !notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                try {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) {
                    openPermissionSettings(
                        context,
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        },
                        { appDetailsIntent(context) })
                }
            }
            !batteryGranted -> requestBatteryOptimization(context)
        }
    }

    // 从系统设置/对话框返回时刷新；一键引导下自动跳到下一项
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                usageStatsGranted = hasUsageStatsPermission(context)
                accessibilityGranted = isAccessibilityServiceEnabled(context)
                notificationGranted = hasNotificationPermission(context)
                batteryGranted = isIgnoringBatteryOptimizations(context)
                autoStartGranted = isAutoStartGranted(context)
                val newCount = grantedCount()
                if (autoGuide && newCount > lastGrantedCount) {
                    lastGrantedCount = newCount
                    // 延迟片刻让返回动画结束，再打开下一项
                    scope.launch {
                        delay(400)
                        openNextPending()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 外部授权自动检测：家长从系统设置手动开启权限时，
    // 本页每 2 秒自动刷新状态；全部就绪后无需任何操作，直接进入儿童端。
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val u = hasUsageStatsPermission(context)
            val a = isAccessibilityServiceEnabled(context)
            val n = hasNotificationPermission(context)
            val b = isIgnoringBatteryOptimizations(context)
            val s = isAutoStartGranted(context)
            if (u != usageStatsGranted || a != accessibilityGranted || n != notificationGranted ||
                b != batteryGranted || s != autoStartGranted
            ) {
                usageStatsGranted = u
                accessibilityGranted = a
                notificationGranted = n
                batteryGranted = b
                autoStartGranted = s
            }
        }
    }

    // 全部必需权限就绪后自动跳转
    val allGranted = usageStatsGranted && accessibilityGranted && notificationGranted && batteryGranted
    LaunchedEffect(allGranted) {
        if (allGranted) {
            autoGuide = false
            onAllGranted()
        }
    }

    val doneCount = listOf(usageStatsGranted, accessibilityGranted, notificationGranted, batteryGranted).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            imageVector = Icons.Default.VerifiedUser,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "欢迎使用小趴菜 🥬",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "共需开启 4 项权限（已开启 $doneCount/4）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = doneCount / 4f,
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 一键引导
        Button(
            onClick = {
                autoGuide = true
                lastGrantedCount = grantedCount()
                openNextPending()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !allGranted,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Bolt, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (autoGuide) "引导中…每项完成后自动跳下一项" else "一键引导（自动跳转每一项）")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // [TASK-MILESTONE-V3] 需求 6：删除初始化流程「电脑 ADB 一键授权」卡片（D4 决策：
        // 普通用户界面一律不出现 ADB/命令/调试提示；测试通道由 debug 构建的 DebugTriggerActivity 承担）

        PermissionCard(
            icon = Icons.Default.Timer,
            title = "使用情况访问",
            description = "采集各应用使用时长，用于计算今日使用总量与超时判断。",
            path = "开启路径：\n" +
                "通用：设置 → 应用 → 小趴菜 → 权限 → 使用情况访问权限 → 允许\n" +
                "备选：设置 → 隐私/安全 → 特殊应用权限 → 使用情况访问 → 小趴菜 → 允许\n" +
                "OPPO/小米/华为：设置 → 应用管理 → 小趴菜 → 权限 → 使用情况访问权限 → 允许",
            isGranted = usageStatsGranted,
            onRequestPermission = {
                openPermissionSettings(
                    context,
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                    { appDetailsIntent(context) })
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Default.Accessibility,
            title = "无障碍服务",
            description = "识别前台应用、展示守护界面、拦截非白名单应用。",
            path = "开启路径：\n" +
                "通用：设置 → 无障碍/辅助功能 → 已安装的服务 → 小趴菜 → 打开开关\n" +
                "OPPO：设置 → 其他设置 → 无障碍 → 已安装的服务 → 小趴菜 → 开启\n" +
                "小米：设置 → 更多设置 → 无障碍 → 已下载的应用 → 小趴菜 → 开启\n" +
                "华为/荣耀：设置 → 辅助功能 → 无障碍 → 已安装的服务 → 小趴菜 → 开启",
            isGranted = accessibilityGranted,
            onRequestPermission = {
                openPermissionSettings(
                    context,
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    { appDetailsIntent(context) })
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Default.Notifications,
            title = "通知权限",
            description = "接收家长公告推送与超时提醒（Android 13+ 弹窗确认，最快）。",
            path = "开启路径：\n" +
                "通用：设置 → 通知/通知与状态栏 → 应用通知管理 → 小趴菜 → 允许通知\n" +
                "Android 13+：点击“去开启”直接弹出系统授权窗口 → 选择“允许”\n" +
                "误点“不允许”后：设置 → 应用 → 小趴菜 → 通知 → 打开全部开关",
            isGranted = notificationGranted,
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    notificationGranted = true
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Default.BatterySaver,
            title = "忽略电池优化",
            description = "避免系统在后台杀死守护服务（优先弹窗，失败进设置页）。",
            path = "开启路径：\n" +
                "通用：设置 → 应用 → 小趴菜 → 电池/耗电 → 允许后台运行、不受限制\n" +
                "备选：设置 → 电池 → 后台管理/耗电管理 → 小趴菜 → 允许自启动/后台运行\n" +
                "OPPO：设置 → 电池 → 更多设置 → 耗电管理 → 允许完全后台行为",
            isGranted = batteryGranted,
            onRequestPermission = { requestBatteryOptimization(context) }
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 可选：厂商自启动（国产 ROM 保活）
        PermissionCard(
            icon = Icons.Default.Power,
            title = "开机自启动（可选）",
            description = "国产 ROM 需额外允许自启动才能开机恢复守护；点击跳转厂商设置。",
            path = "开启路径：\n" +
                "通用：设置 → 应用管理 → 小趴菜 → 自启动/开机启动 → 允许\n" +
                "OPPO/一加/真我：设置 → 应用 → 应用管理 → 小趴菜 → 自启动 → 开启\n" +
                "小米/红米：设置 → 应用设置 → 授权管理 → 自启动管理 → 允许小趴菜\n" +
                "华为/荣耀：设置 → 应用 → 应用启动管理 → 小趴菜 → 手动管理 → 允许自启动\n" +
                "vivo/iQOO：i管家 → 应用管理 → 权限管理 → 自启动 → 允许小趴菜",
            isGranted = autoStartGranted,
            onRequestPermission = { openAutoStartSettings(context) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                text = "提示：点“一键引导”后，每完成一项返回即自动打开下一项；\n灰色按钮/对勾表示已授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(16.dp)
            )
        }

        // [TASK-MILESTONE-V3] 需求 5：能力边界如实说明（上滑结束/强制停止）
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = "能力边界说明：Android 不允许任何应用阻止用户手动结束进程。\n" +
                    "• 孩子在最近任务上滑结束小趴菜：守护会在约 5 秒内自动恢复并重新执行管控，同时通知家长；\n" +
                    "• 在系统设置中「强制停止」小趴菜：系统会一并取消恢复机制，需重新打开小趴菜才能恢复守护（打开即恢复并通知家长）。\n" +
                    "完成以上授权（尤其「忽略电池优化」与「自启动」）可显著降低被系统结束的概率。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** 权限卡片组件 */
@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    path: String,
    isGranted: Boolean,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(40.dp),
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (path.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isGranted) {
                Icon(Icons.Default.CheckCircle, "已授权", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onRequestPermission,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("去开启", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 统一打开权限设置页：主意图失败回退应用详情 */
private fun openPermissionSettings(
    context: android.content.Context,
    primary: Intent,
    fallback: () -> Intent
) {
    try {
        context.startActivity(primary)
    } catch (e: Exception) {
        Log.w("PermissionGuide", "权限设置意图打开失败（${primary.action}）: ${e.message}，回退应用详情")
        try {
            context.startActivity(fallback())
        } catch (e2: Exception) {
            Log.e("PermissionGuide", "回退打开应用详情失败: ${e2.message}")
        }
    }
}

/** 本应用系统详情页（通用回退目标） */
private fun appDetailsIntent(context: android.content.Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }

/** 请求忽略电池优化：优先直接弹窗，失败回退通用设置页 */
private fun requestBatteryOptimization(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        openPermissionSettings(
            context,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            { appDetailsIntent(context) })
    }
}

/** 是否已忽略电池优化 */
private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    return try {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}

/** 打开厂商自启动设置（小米/华为/OPPO/vivo 等） */
private fun openAutoStartSettings(context: android.content.Context) {
    val targets = listOf(
        "com.oplus.battery" to "com.oplus.startupapp.view.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.startupapp.ui.StartupAppListActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    )
    for ((pkg, act) in targets) {
        try {
            val intent = Intent().apply {
                setClassName(pkg, act)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            // 尝试下一个厂商
        }
    }
    // 兜底：应用详情页（用户手动找“自启动”）
    openPermissionSettings(context, appDetailsIntent(context)) { appDetailsIntent(context) }
}

/** 厂商自启动是否已开启（粗略判断：不存在对应安全中心时视为无需处理） */
private fun isAutoStartGranted(context: android.content.Context): Boolean {
    val brands = listOf("xiaomi", "huawei", "honor", "oppo", "vivo", "oneplus", "realme")
    val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
    // 非国产 ROM：视为无需自启动授权
    // 国产 ROM：无法可靠查询自启动开关，保守显示未授权，引导用户去厂商设置
    return brands.none { manufacturer.contains(it) }
}
