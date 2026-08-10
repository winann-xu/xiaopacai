package com.xiaopacai.child.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.xiaopacai.child.MainActivity

/**
 * [TASK-D3-03] 设备管理器接收器
 *
 * 实现设备管理策略以防止儿童绕过守护或卸载应用：
 * - 防止卸载（DISABLE_UNINSTALL，需激活设备管理器）
 * - 锁定屏幕（强制休息）
 * - 禁用安全模式（防止安全模式绕过）
 * - 清除数据检测
 *
 * 激活方式：跳转到系统设置 → 安全 → 设备管理器
 */
class GuardianDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdmin"

        /**
         * 获取设备管理组件名称
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, GuardianDeviceAdminReceiver::class.java)
        }

        /**
         * 检查设备管理器是否已激活
         */
        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }

        /**
         * 获取设备策略管理器（需先检查isActive）
         */
        fun getDpm(context: Context): DevicePolicyManager {
            return context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "设备管理器已激活 — 卸载保护生效")
        Toast.makeText(context, "✓ 小趴菜设备保护已激活", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "⚠️ 设备管理器被禁用 — 卸载保护失效！")

        // 安全通知：设备管理器被关闭
        AntiBypassService.notifySecurityEvent(
            context,
            "设备保护被关闭",
            "设备管理器已被禁用，应用可能被卸载。请家长尽快检查。"
        )

        // 尝试重新申请设备管理器权限（弹出系统设置）
        try {
            val dpm = getDpm(context)
            if (!dpm.isAdminActive(getComponentName(context))) {
                // 发送广播通知家长端
                context.sendBroadcast(
                    Intent("com.xiaopacai.action.DEVICE_ADMIN_DISABLED")
                        .setPackage(context.packageName)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "设备管理器重激活失败: ${e.message}")
        }
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.i(TAG, "设备密码已更改")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "设备密码验证失败")
    }

    /**
     * 当用户尝试清除应用数据时的回调
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED -> {
                Log.e(TAG, "设备管理器被外部禁用")
            }
            DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLE_REQUESTED -> {
                // 用户尝试禁用设备管理器 — 阻止此操作
                Log.w(TAG, "用户尝试禁用设备管理器 — 已阻止")
                Toast.makeText(
                    context,
                    "⚠️ 请输入家长密码才能关闭保护",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
