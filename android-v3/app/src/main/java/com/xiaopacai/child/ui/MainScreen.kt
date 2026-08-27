package com.xiaopacai.child.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val PERMISSION_GUIDE = "permission_guide"
    const val GUARDIAN_HOME = "guardian_home"
    const val GUARD_STATUS = "guard_status"
    const val APP_CATEGORY = "app_category"
    const val LOG_VIEWER = "log_viewer"
    const val UPGRADE = "upgrade"
    const val ACCOUNT_SECURITY = "account_security"
    const val DO_SETUP = "do_setup"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val navController = rememberNavController()

    val startDestination = Routes.GUARDIAN_HOME

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.PERMISSION_GUIDE) {
            PermissionGuideScreen(
                onAllGranted = {
                    navController.navigate(Routes.GUARDIAN_HOME) {
                        popUpTo(Routes.PERMISSION_GUIDE) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.GUARDIAN_HOME) {
            GuardianHomeScreen(
                onNavigate = { route -> navController.navigate(route) }
            )
        }
        composable(Routes.GUARD_STATUS) {
            com.xiaopacai.child.ui.child.GuardStatusScreen(
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenLogViewer = { navController.navigate(Routes.LOG_VIEWER) },
                onOpenUpgrade = { navController.navigate(Routes.UPGRADE) },
                onOpenAccountSecurity = { navController.navigate(Routes.ACCOUNT_SECURITY) },
                onOpenAbout = { /* handled as dialog */ },
                onOpenDoSetup = { navController.navigate(Routes.DO_SETUP) }
            )
        }
        composable(Routes.APP_CATEGORY) {
            com.xiaopacai.child.ui.child.AppCategoryScreenV2(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.LOG_VIEWER) {
            com.xiaopacai.child.ui.child.LogViewerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.UPGRADE) {
            com.xiaopacai.child.ui.child.UpgradeScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.ACCOUNT_SECURITY) {
            com.xiaopacai.child.ui.child.AccountSecurityScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.DO_SETUP) {
            com.xiaopacai.child.ui.child.DoSetupScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
fun GuardianHomeScreen(onNavigate: (String) -> Unit = {}) {
    GuardianHomeContent(
        onNavigate = onNavigate
    )
}

private fun checkAllPermissions(context: Context): Boolean {
    return hasUsageStatsPermission(context) && isAccessibilityServiceEnabled(context) &&
        hasNotificationPermission(context)
}

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName = "${context.packageName}/.service.GuardianAccessibilityService"
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    return enabledServices.contains(serviceName) || enabledServices.contains("com.xiaopacai.child")
}

fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true
}
