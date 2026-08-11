package com.xiaopacai.child.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.xiaopacai.child.ui.BlockOverlayActivity
import com.xiaopacai.child.ui.overlay.AnnouncementOverlayActivity

/**
 * [TASK-D1-02][TASK-D2-03] 小趴菜无障碍服务
 *
 * 用于实现应用拦截的核心机制：
 * - 监听前台应用切换事件（TYPE_WINDOW_STATE_CHANGED）
 * - 调用 AppInterceptor 判断是否应拦截
 * - 拦截时启动全屏覆盖 Activity（BlockOverlayActivity）
 *
 * 能力边界说明：
 * 无障碍服务无法真正"锁定"系统或阻止应用启动，
 * 只能在前台应用切换时检测并覆盖守护界面。
 * 这是 Android 平台限制，非本软件缺陷。
 * 详见 OEM_KEEPALIVE.md 文档。
 */
class GuardianAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GuardianA11y"

        /** 上次拦截的目标包名（避免重复启动拦截界面） */
        private var lastBlockedPackage: String? = null

        /** 上次拦截时间（毫秒），用于防抖 */
        private var lastBlockedTime: Long = 0

        /** 防抖间隔（毫秒） */
        private const val DEBOUNCE_MS = 3000L

        /** 当前服务实例（供 SyncManager 从无障碍特权上下文启动紧急公告） */
        @Volatile
        private var instance: GuardianAccessibilityService? = null

        /**
         * [TASK-OPT-4] 从无障碍服务上下文启动紧急公告全屏界面。
         * 无障碍服务持有系统 BAL 特权（Android 12+ 后台 Activity 启动限制豁免），
         * 是"游戏中/视频中强制置顶"的可靠通道。
         */
        fun showAnnouncementOverlay(
            announcementId: String,
            title: String,
            content: String
        ): Boolean {
            val service = instance ?: return false
            return try {
                AnnouncementOverlayActivity.launch(service, announcementId, title, content)
                true
            } catch (e: Exception) {
                Log.e(TAG, "紧急公告启动失败: ${e.message}")
                false
            }
        }
    }

    private lateinit var interceptor: AppInterceptor

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 初始化拦截引擎
        interceptor = AppInterceptor(this)

        // 配置无障碍服务监听的事件类型
        val info = AccessibilityServiceInfo().apply {
            // 监听窗口状态变化（前台应用切换）
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

            // 反馈类型：仅监听，不主动交互
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // 通知超时（0 = 即时通知）
            notificationTimeout = 0

            // 可监听所有包（包括系统应用）
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        this.serviceInfo = info

        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 仅处理窗口状态变化事件
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // 获取当前前台应用包名
        val packageName = event.packageName?.toString() ?: return

        // 忽略空包名和自身包名
        if (packageName.isEmpty() || packageName == this@GuardianAccessibilityService.packageName) {
            // 自身应用切换不拦截
            return
        }

        // 防抖：同一包名 3 秒内不重复处理
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage &&
            (now - lastBlockedTime) < DEBOUNCE_MS) {
            return
        }

        // 调用拦截引擎判断
        val result = interceptor.shouldIntercept(packageName)

        if (result.intercept) {
            Log.i(TAG, "拦截应用: $packageName, 原因: ${result.reason}")
            lastBlockedPackage = packageName
            lastBlockedTime = now
            showBlockOverlay(packageName, result.reason)
        }
    }

    /**
     * 显示全屏拦截覆盖界面
     *
     * @param targetPackage 被拦截的应用包名
     * @param reason 拦截原因
     */
    private fun showBlockOverlay(targetPackage: String, reason: String) {
        try {
            val intent = Intent(this, BlockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("target_package", targetPackage)
                putExtra("reason", reason)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动拦截界面失败: ${e.message}", e)
        }
    }

    override fun onInterrupt() {
        // 服务被系统中断时的清理
        Log.w(TAG, "无障碍服务被中断")
        lastBlockedPackage = null
        lastBlockedTime = 0
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
