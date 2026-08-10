package com.xiaopacai.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * [TASK-D1-02] 开机广播接收器
 *
 * 设备重启后自动拉守护前台服务，增强保活能力。
 * 需在 AndroidManifest 中注册 RECEIVE_BOOT_COMPLETED 权限。
 *
 * 注意：部分 OEM 可能限制开机自启动，
 * 需要用户手动在系统设置中允许"自启动"权限。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 设备启动完成，自动启动守护服务
            GuardianForegroundService.start(context)
        }
    }
}
