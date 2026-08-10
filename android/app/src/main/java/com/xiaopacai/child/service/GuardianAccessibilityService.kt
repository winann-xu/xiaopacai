package com.xiaopacai.child.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent

/**
 * [TASK-D1-02] 小趴菜无障碍服务
 *
 * 用于实现超时停用的核心拦截机制：
 * - 监听前台应用切换事件（TYPE_WINDOW_STATE_CHANGED）
 * - 当检测到超时状态时，展示全屏守护界面
 * - 拦截非白名单应用的启动（通过识别前台包名 + 覆盖守护界面）
 *
 * 能力边界说明：
 * 无障碍服务无法真正"锁定"系统或阻止应用启动，
 * 只能在前台应用切换时检测并覆盖守护界面。
 * 这是 Android 平台限制，非本软件缺陷。
 */
class GuardianAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()

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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 仅处理窗口状态变化事件
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // 获取当前前台应用包名
        val packageName = event.packageName?.toString() ?: return

        // TODO: [TASK-D2-03] 检查是否处于超时停用状态
        // TODO: [TASK-D2-03] 检查当前应用是否在白名单中
        // TODO: [TASK-D2-03] 若非白名单且超时，启动全屏守护界面覆盖
    }

    override fun onInterrupt() {
        // 服务被系统中断时的清理
    }
}
