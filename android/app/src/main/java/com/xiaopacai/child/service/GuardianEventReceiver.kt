package com.xiaopacai.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * [TASK-HARDENING-V1.1.1] Bug4-A：无障碍权限丢失「事件触发」即时自检
 *
 * 事件通道：
 * - ACTION_SCREEN_ON / ACTION_USER_PRESENT：亮屏/解锁立即查
 *   （系统广播仅支持动态注册 → 由 GuardianForegroundService 注册）
 * - ACTION_MY_PACKAGE_REPLACED：应用更新后立即查（Manifest 静态注册兜底，
 *   更新后无障碍服务会被系统关停，第一时间发现并通知）
 *
 * 每次触发经 AntiBypassService 30 秒节流；发现无障碍被关 → 高优通知 +
 * 一键直达无障碍设置。每分钟兜底由 AntiBypassService 轮询覆盖。
 *
 * 边界如实说明：第三方应用无法自动重新开启无障碍服务（平台硬限制），
 * 只能检测 + 引导 + 告警。
 */
class GuardianEventReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GuardianEventReceiver"

        /** 动态注册（守护服务内）使用的系统广播过滤 */
        fun dynamicFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "事件触发自检: ${intent.action}")
                AntiBypassService.triggerImmediateCheck(context)
            }
        }
    }
}
