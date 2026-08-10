package com.xiaopacai.child.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * [TASK-D1-02] 小趴菜儿童端主界面
 *
 * 管理应用导航：权限引导 → 守护主页。
 * 启动后首先检查必要权限，未授权则跳转权限引导页。
 */

// 导航路由定义
object Routes {
    const val PERMISSION_GUIDE = "permission_guide"
    const val GUARDIAN_HOME = "guardian_home"
}

/**
 * 主界面 Composable
 * 包含导航控制器，管理权限引导与守护主页的切换
 */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val navController = rememberNavController()

    // 检查所有必要权限是否已授权
    val hasAllPermissions = remember {
        checkAllPermissions(context)
    }

    // 根据权限状态决定起始路由
    val startDestination = if (hasAllPermissions) {
        Routes.GUARDIAN_HOME
    } else {
        Routes.PERMISSION_GUIDE
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // 权限引导页
        composable(Routes.PERMISSION_GUIDE) {
            PermissionGuideScreen(
                onAllGranted = {
                    navController.navigate(Routes.GUARDIAN_HOME) {
                        popUpTo(Routes.PERMISSION_GUIDE) { inclusive = true }
                    }
                }
            )
        }
        // 守护主页
        composable(Routes.GUARDIAN_HOME) {
            GuardianHomeScreen()
        }
    }
}

/**
 * 守护主页（骨架占位）
 * 实际内容在 D1-05 实现，此处显示基础结构
 */
@Composable
fun GuardianHomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "守护中",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "小趴菜守护中 🥬",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "剩余使用时长、超时停用、公告等将在 D1-05 实现",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// TODO: D1-05 将以下内容替换为完整版 GuardianHomeScreen

/**
 * 检查所有必要权限是否已授予
 *
 * @return true 如果使用情况访问权限、无障碍服务、通知权限均已授予
 */
private fun checkAllPermissions(context: Context): Boolean {
    val hasUsageStats = hasUsageStatsPermission(context)
    val hasAccessibility = isAccessibilityServiceEnabled(context)
    val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Android 13+ 需要运行时通知权限
        true  // 暂时跳过运行时检查，由系统弹窗处理
    } else {
        true
    }
    return hasUsageStats && hasAccessibility && hasNotification
}

/**
 * 检查使用情况访问权限
 * 系统设置中的"使用情况访问"开关
 */
fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * 检查无障碍服务是否已开启
 * "小趴菜守护服务"需要在系统无障碍设置中手动开启
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName = "${context.packageName}/.service.GuardianAccessibilityService"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(serviceName) || enabledServices.contains("com.xiaopacai.child")
}
