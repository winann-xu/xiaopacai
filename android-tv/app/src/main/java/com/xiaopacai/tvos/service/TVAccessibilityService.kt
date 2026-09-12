// [android-tv] 无障碍守护服务 —— 【项2】防「换台逃逸」
// 小趴菜 TVOS 版
//
// 作用：超时（锁屏）状态下，孩子如果按 HOME / 切到别的 App / 进系统设置，
// 这里监听前台窗口变化，立刻把锁屏重新盖回去，做到「真的出不去」。
//
// 说明：
//   - 需要家长手动开启一次（Android 无障碍权限无法由 App 自行授予）：
//       设置 → 无障碍 → 小趴菜电视守护 → 开启
//   - 只需要包名（TYPE_WINDOW_STATE_CHANGED 自带），所以 XML 里
//       canRetrieveWindowContent=false —— 不读取屏幕内容，隐私最小化。
//   - 只在「限时已用完」时拦截；平时完全放行，不影响正常看电视。

package com.xiaopacai.tvos.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.xiaopacai.tvos.util.TimeoutExecutorTV

class TVAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍守护已连接，开始拦截「换台逃逸」")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = e.packageName?.toString() ?: return
        // 自家界面不拦
        if (pkg == packageName) return
        // 系统输入法弹窗、通知栏等非 Activity 窗口不拦
        if (e.className == null) return
        // 只有「限时已用完 / 家长已禁用」时才需要把孩子挡回去
        if (!TimeoutExecutorTV.isLocked) return

        Log.i(TAG, "超时状态下检测到其他应用在前台：$pkg → 重新盖回锁屏")
        runCatching { TimeoutExecutorTV.lockScreen(this) }
            .onFailure { Log.e(TAG, "重新锁屏失败", it) }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍守护被中断")
    }

    companion object {
        private const val TAG = "TVAccessibility"
    }
}
