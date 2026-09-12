// [android-tv] 事件广播接收器 — TVEventReceiver
// 小趴菜 TVOS 版 — 处理电视系统级事件

package com.xiaopacai.tvos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log

/**
 * 电视事件广播接收器
 * 
 * 监听事件：
 * 1. 应用更新（MY_PACKAGE_REPLACED）→ 重新初始化
 * 2. 屏幕状态变化 → 锁屏/解锁
 * 3. 网络变化 → 重连云端
 * 4. 睡眠/唤醒 → 更新状态
 */
class TVEventReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TVEventReceiverTV"

        // 小米电视特定广播
        const val MIUI_SCREEN_ON = "com.miui.screen.on"
        const val MIUI_SCREEN_OFF = "com.miui.screen.off"
        const val MIUI_POWER_CONNECTED = "com.miui.power_connected"
        const val MIUI_POWER_DISCONNECTED = "com.miui.power_disconnected"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 应用更新后自动重启服务
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "📦 应用已更新，重新初始化")
                restartServices(context)
            }

            // 屏幕状态变化
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "📺 屏幕已打开")
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "📺 屏幕已关闭")
            }

            // 网络状态变化
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                Log.i(TAG, "🌐 网络状态变化")
            }

            // 小米电视电源状态
            MIUI_POWER_CONNECTED -> {
                Log.i(TAG, "🔌 电源已连接")
            }
            MIUI_POWER_DISCONNECTED -> {
                Log.i(TAG, "🔌 电源已断开")
            }

            // 小米电视特定事件
            MIUI_SCREEN_ON -> {
                Log.i(TAG, "📺 小米屏幕已打开")
            }
            MIUI_SCREEN_OFF -> {
                Log.i(TAG, "📺 小米屏幕已关闭")
            }
        }
    }

    /**
     * 应用更新后重启所有服务
     */
    private fun restartServices(context: Context) {
        listOf(
            TVGuardianForegroundService::class.java,
            CloudSyncServiceTV::class.java,
            AntiBypassServiceTV::class.java
        ).forEach { svcClass ->
            try {
                val intent = Intent(context, svcClass).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "重启 ${svcClass.simpleName} 失败: ${e.message}")
            }
        }
    }
}
