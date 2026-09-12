// [android-tv] 广播接收器 — BootReceiver
// 小趴菜 TVOS 版 — 开机自启动 + 小米电视自动启动白名单

package com.xiaopacai.tvos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 开机广播接收器
 * 
 * 功能：
 * 1. 系统启动后重启守护服务
 * 2. 小米电视特定：请求加入自动启动白名单
 * 3. 请求忽略电池优化
 * 4. 请求使用时长统计权限
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiverTV"

        // 小米电视自动启动管理 Activity
        private const val MIUI_AUTO_START_ACTIVITY =
            "com.miui.securitycenter.MainActivity"
        private const val MIUI_AUTO_START_PACKAGE =
            "com.miui.securitycenter"

        // 小米电视电池优化白名单 Activity
        private const val MIUI_BATTERY_ACTIVITY =
            "com.miui.powercenter.PowerMonitorActivity"
        private const val MIUI_BATTERY_PACKAGE =
            "com.miui.powercenter"
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.miui.poweron.RECEIVE_BOOT_COMPLETED" -> {
                onBootComplete(context)
            }
            "com.miui.gps.NTP_UPDATE" -> {
                // 小米 GPS 更新广播（可能触发重新定位）
            }
        }
    }

    /**
     * 系统启动完成后的处理
     */
    private fun onBootComplete(context: Context) {
        Log.i(TAG, "🔄 系统启动完成，启动守护服务")

        // 1~3. 【项2】统一由看门狗拉起全部守护组件（守护前台服务 / 防绕过 / 云同步 / 锁屏），
        //      并启动「每分钟自检」闹钟 —— 这一层在进程被杀后依然有效。
        GuardianWatchdog.ensureGuardsRunning(context)
        GuardianWatchdog.schedule(context)

        // 4. 请求小米电视自动启动白名单（MIUI/HyperOS 特有）
        requestMiuiAutoStart(context)

        // 5. 请求忽略电池优化
        requestIgnoreBatteryOptimization(context)

        // 6. 重置每日计时器
        GlobalScope.launch {
            com.xiaopacai.tvos.util.TimeoutExecutorTV.resetDaily()
        }
    }

    /**
     * 请求小米电视自动启动白名单
     * 小米电视需要手动在安全中心中添加自启动权限
     */
    private fun requestMiuiAutoStart(context: Context) {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    MIUI_AUTO_START_PACKAGE,
                    MIUI_AUTO_START_ACTIVITY
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // 小米 Intent 参数：指定要加入白名单的应用
                putExtra("packageName", context.packageName)
            }
            context.startActivity(intent)
            Log.i(TAG, "已请求加入小米自动启动白名单")
        } catch (e: Exception) {
            Log.w(TAG, "无法打开小米自动启动设置: ${e.message}")
            // 在电视环境下，可能无法直接跳转
            // 用户需手动操作：设置 > 应用 > 小趴菜 > 自启动
        }
    }

    /**
     * 请求忽略电池优化（Doze 模式豁免）
     */
    private fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "已请求忽略电池优化")
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法请求电池优化豁免: ${e.message}")
            }
        }
    }
}
