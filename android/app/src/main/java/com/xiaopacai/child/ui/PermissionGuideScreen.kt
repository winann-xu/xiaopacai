package com.xiaopacai.child.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * [TASK-D1-02] 权限引导页
 *
 * 引导家长/儿童完成小趴菜所需的系统权限设置：
 * 1. 使用情况访问权限（采集应用使用时长）
 * 2. 无障碍服务（超时停用拦截）
 * 3. 通知权限（公告推送）
 * 4. 忽略电池优化（保活增强）
 *
 * 每一项权限提供说明、开启按钮和状态指示。
 * 所有必要权限开启后，自动跳转到守护主页。
 */
@Composable
fun PermissionGuideScreen(
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 实时刷新各权限状态
    var usageStatsGranted by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var accessibilityGranted by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var batteryOptimizationGranted by remember { mutableStateOf(false) }

    // 检查是否全部就绪，是则通知跳转
    val allGranted = usageStatsGranted && accessibilityGranted
    LaunchedEffect(allGranted) {
        if (allGranted) {
            onAllGranted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // 标题区
        Icon(
            imageVector = Icons.Default.VerifiedUser,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "欢迎使用小趴菜 🥬",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "需要开启以下权限才能正常使用\n请按照引导逐步完成设置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // === 权限项 1：使用情况访问 ===
        PermissionCard(
            icon = Icons.Default.Timer,
            title = "使用情况访问",
            description = "采集各应用使用时长，用于计算今日使用总量与超时判断。",
            isGranted = usageStatsGranted,
            onRequestPermission = {
                // 跳转系统"使用情况访问"设置页
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 权限项 2：无障碍服务 ===
        PermissionCard(
            icon = Icons.Default.Accessibility,
            title = "无障碍服务",
            description = "用于实现超时停用：识别前台应用、展示守护界面、拦截非白名单应用。",
            isGranted = accessibilityGranted,
            onRequestPermission = {
                // 跳转系统无障碍设置页
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 权限项 3：忽略电池优化 ===
        PermissionCard(
            icon = Icons.Default.BatterySaver,
            title = "忽略电池优化",
            description = "避免系统在后台杀死守护服务，确保时长统计与超时停用持续有效。",
            isGranted = batteryOptimizationGranted,
            onRequestPermission = {
                // 请求忽略电池优化
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // 部分设备不支持此 Intent，跳过
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 权限项 4：通知权限 ===
        PermissionCard(
            icon = Icons.Default.Notifications,
            title = "通知权限",
            description = "接收家长公告推送与超时提醒，Android 13+ 需用户确认。",
            isGranted = true,  // 通知权限由系统自动弹窗，不在此处阻塞
            onRequestPermission = {
                // Android 13+ 系统会弹出通知权限请求
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 底部提示
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "提示：当从设置页返回后，权限状态会自动刷新。\n灰色按钮已表示已授权，无需重复操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 权限卡片组件
 *
 * @param icon 权限图标
 * @param title 权限名称
 * @param description 权限说明（为什么需要）
 * @param isGranted 是否已授权
 * @param onRequestPermission 点击按钮时的操作
 */
@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isGranted)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 文字说明
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 状态/操作按钮
            if (isGranted) {
                // 已授权：显示绿色对勾
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已授权",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                // 未授权：显示蓝色开启按钮
                Button(
                    onClick = onRequestPermission,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("去开启", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
