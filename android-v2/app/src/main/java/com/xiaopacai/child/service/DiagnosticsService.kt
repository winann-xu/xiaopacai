package com.xiaopacai.child.service

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.xiaopacai.child.model.DiagnosticRecord

object DiagnosticsService {

    fun runHealthCheck(context: Context): List<DiagnosticRecord> {
        return listOf(
            checkDeviceAdmin(context),
            checkAccessibility(context),
            checkUsageStats(context),
            checkBootPermission(context),
            checkBatteryOptimization(context),
            checkCloudConnection(context)
        )
    }

    private fun checkDeviceAdmin(context: Context): DiagnosticRecord {
        val active = GuardianDeviceAdminReceiver.isActive(context)
        return DiagnosticRecord(
            checkKey = "deviceAdmin",
            title = "设备管理器",
            description = "防卸载核心保护，需在系统设置中激活",
            ready = active
        )
    }

    private fun checkAccessibility(context: Context): DiagnosticRecord {
        val active = AntiBypassService.isAccessibilityServiceEnabled(context)
        return DiagnosticRecord(
            checkKey = "accessibility",
            title = "无障碍服务",
            description = "超时停用拦截的核心通道",
            ready = active
        )
    }

    private fun checkUsageStats(context: Context): DiagnosticRecord {
        val granted = AntiBypassService.isUsageStatsPermissionGranted(context)
        return DiagnosticRecord(
            checkKey = "usageStats",
            title = "使用情况访问",
            description = "采集各应用使用时长的基础权限",
            ready = granted
        )
    }

    private fun checkBootPermission(context: Context): DiagnosticRecord {
        val granted = try {
            context.packageManager.checkPermission(
                android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
                context.packageName
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        return DiagnosticRecord(
            checkKey = "bootAutoStart",
            title = "开机自启动",
            description = "设备重启后自动恢复守护",
            ready = granted
        )
    }

    private fun checkBatteryOptimization(context: Context): DiagnosticRecord {
        val disabled = !AntiBypassService.isBatteryOptimizationEnabled(context)
        return DiagnosticRecord(
            checkKey = "battery",
            title = "电池优化",
            description = "关闭后防止后台守护被杀",
            ready = disabled
        )
    }

    private fun checkCloudConnection(context: Context): DiagnosticRecord {
        val state = CloudSyncService.connectionState.value
        val connected = state == CloudSyncService.CloudSyncState.CONNECTED
        return DiagnosticRecord(
            checkKey = "cloudConnection",
            title = "云端连接",
            description = "与家长端云服务的通信链路",
            ready = connected
        )
    }

    fun getFixAction(context: Context, checkKey: String): (() -> Unit)? {
        return when (checkKey) {
            "deviceAdmin" -> {
                {
                    try {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                GuardianDeviceAdminReceiver.getComponentName(context))
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "激活后可防止小趴菜被卸载")
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            "accessibility" -> {
                { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            "usageStats" -> {
                { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            "bootAutoStart" -> {
                {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    } catch (_: Exception) {}
                }
            }
            "battery" -> {
                {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    }
                }
            }
            "cloudConnection" -> null
            else -> null
        }
    }
}
