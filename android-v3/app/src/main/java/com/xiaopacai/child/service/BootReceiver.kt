package com.xiaopacai.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * [TASK-D1-02] 开机广播接收器
 *
 * 设备重启后自动拉守护前台服务，增强保活能力。
 * 需在 AndroidManifest 中注册 RECEIVE_BOOT_COMPLETED 权限。
 *
 * [TASK-OPT-12-P2] 增强：开机延迟 10 秒后再启动守护（需求6），
 * 给系统（网络/存储/前台服务框架）留出就绪时间，提高拉起成功率。
 * 延迟期间通过 goAsync + Handler 保持广播接收器存活。
 *
 * 注意：部分 OEM 可能限制开机自启动，
 * 需要用户手动在系统设置中允许"自启动"权限。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        /** 开机延迟启动守护的时间（毫秒） */
        private const val BOOT_START_DELAY_MS = 10_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "收到开机广播，将在 ${BOOT_START_DELAY_MS / 1000}s 后启动守护")
        val pendingResult = goAsync()

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // 设备启动完成，延迟后自动启动守护服务
                GuardianForegroundService.start(context)
                // [TASK-OPT-12-P2] 同时调度双守护自检与诊断上报
                AntiBypassService.scheduleSelfCheck(context)
                DiagnosticsCollector.start(context)
                Log.i(TAG, "守护服务已启动（开机延迟完成）")
            } catch (e: Exception) {
                Log.e(TAG, "开机启动守护失败: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }, BOOT_START_DELAY_MS)
    }
}
