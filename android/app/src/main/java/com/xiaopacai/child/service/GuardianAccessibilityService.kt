package com.xiaopacai.child.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.xiaopacai.child.ui.BlockOverlayActivity
import com.xiaopacai.child.ui.overlay.AnnouncementOverlayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val checkScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var checkJob: Job? = null

    /** 最近一次看到的前台包名（事件通道记录，供巡检兜底） */
    private var lastForegroundPackage: String? = null

    /** 巡检间隔：即使儿童停在受限应用内不切换，也会周期性拦截 */
    private val CHECK_INTERVAL_MS = 5000L

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

        // [FIX] 周期前台巡检：堵住“超时后停留在受限应用内不切换就不拦截”的绕过
        checkJob = checkScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                try {
                    periodicForegroundCheck()
                } catch (e: Exception) {
                    Log.w(TAG, "巡检异常: ${e.message}")
                }
            }
        }

        Log.i(TAG, "无障碍服务已连接")
    }

    /**
     * 每 5 秒检查当前前台应用是否需要拦截。
     * 解决：仅靠 TYPE_WINDOW_STATE_CHANGED 事件，停留在受限应用内超时后不会触发拦截。
     */
    private suspend fun periodicForegroundCheck() {
        val packageName = withContext(Dispatchers.IO) { getForegroundPackage() } ?: return

        if (packageName == this@GuardianAccessibilityService.packageName) return
        if (com.xiaopacai.child.ui.overlay.AnnouncementOverlayActivity.hasPendingUrgent()) return

        val result = withContext(Dispatchers.IO) {
            interceptor.shouldIntercept(packageName)
        }
        if (result.intercept) {
            val now = System.currentTimeMillis()
            if (packageName == lastBlockedPackage && (now - lastBlockedTime) < DEBOUNCE_MS) return
            Log.i(TAG, "巡检拦截: $packageName, 原因: ${result.reason}")
            lastBlockedPackage = packageName
            lastBlockedTime = now
            showBlockOverlay(packageName, result.reason)
        }
    }

    /**
     * 获取当前前台应用包名（多级兜底）：
     * 1) 活跃窗口（getRootInActiveWindow，多数设备可用）
     * 2) 事件通道最后记录的前台包名
     * 3) 使用情况统计（UsageStatsManager，需「使用情况访问」权限，最可靠）
     */
    private fun getForegroundPackage(): String? {
        try {
            getRootInActiveWindow()?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {}

        lastForegroundPackage?.let { return it }

        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 60_000, end)
            val ev = UsageEvents.Event()
            var pkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    !ev.packageName.isNullOrBlank()) {
                    pkg = ev.packageName
                }
            }
            pkg
        } catch (e: Exception) {
            Log.w(TAG, "查询前台应用失败: ${e.message}")
            null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 仅处理窗口状态变化事件
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // 获取当前前台应用包名
        val packageName = event.packageName?.toString() ?: return
        lastForegroundPackage = packageName

        // 忽略空包名和自身包名
        if (packageName.isEmpty() || packageName == this@GuardianAccessibilityService.packageName) {
            // 自身应用切换不拦截
            return
        }

        // [TASK-OPT-12-P2] 紧急公告防绕过（需求4）：未确认前被切走（HOME/返回），重新拉起全屏公告
        if (com.xiaopacai.child.ui.overlay.AnnouncementOverlayActivity.hasPendingUrgent()) {
            Log.w(TAG, "紧急公告未确认，检测到前台切换到: $packageName，重新拉起公告")
            com.xiaopacai.child.ui.overlay.AnnouncementOverlayActivity.relaunchPending(this)
            return
        }

        // [TASK-OPT-12-P2] 应用信息页检测（需求6）：打开系统应用详情页疑似尝试卸载/停用
        val className = event.className?.toString() ?: ""
        if (packageName == "com.android.settings" &&
            (className.contains("InstalledAppDetails") ||
             className.contains("AppInfoBase") ||
             className.contains("ApplicationDetails"))) {
            AntiBypassService.onAppInfoPageOpened(this)
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
        checkJob?.cancel()
        checkScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
