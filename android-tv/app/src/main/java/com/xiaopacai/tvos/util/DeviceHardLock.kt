// [android-tv] 设备所有者硬管控 —— A7 整机拦截 / A14 kiosk 锁任务 / A15 禁恢复出厂
// 小趴菜 TVOS 版
//
// 全部能力都要求 device owner（.21 已拿到；取法与验证见技能 android-device-owner）。
// 非 DO 时所有方法静默降级，不影响「覆盖层 + 无障碍 + 看门狗」那三层。
//
// ⚠️ 锁任务模式（kiosk）的正确用法：
//   1. DO 先 setLockTaskPackages(admin, [自己的包]) —— 白名单里只有我们
//   2. 由**我们自己的 Activity** 调 startLockTask() 真正进入（Service 进不去）
//   3. 退出：DO 调 setLockTaskPackages(admin, emptyArray()) 即可强制退出，不需要 Activity
//   因为「自己的包」在白名单里，家长验证页/安全阀页仍然能起来 —— 这是必须保留的逃生口。

package com.xiaopacai.tvos.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver

object DeviceHardLock {

    private const val TAG = "DeviceHardLock"

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) =
        ComponentName(context, GuardianDeviceAdminReceiver::class.java)

    fun isOwner(context: Context): Boolean = GuardianDeviceAdminReceiver.isDeviceOwner(context)

    /**
     * 【A7 整机拦截】进入锁屏时调用：把设备锁进「只有小趴菜能起得来」的状态。
     * 真正的 startLockTask() 由锁屏 Activity 执行（见 BlockOverlayActivity）。
     */
    fun engage(context: Context): Boolean {
        if (!isOwner(context)) {
            AppLogTV.i(TAG, "非设备所有者：整机拦截降级（仍靠覆盖层+无障碍+看门狗）")
            return false
        }
        return runCatching {
            val d = dpm(context)
            val a = admin(context)
            d.setLockTaskPackages(a, arrayOf(context.packageName))
            // ⚠️ setStatusBarDisabled 是 API 23+：Android 5.1 上调用会 NoSuchMethodError
            //（真机日志：No virtual method setStatusBarDisabled(...)）—— 老机型必须跳过分支
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                d.setStatusBarDisabled(a, true)
            }
            AppLogTV.i(TAG, "【A7/A14】整机拦截已开启：锁任务白名单=本应用 + 状态栏屏蔽")
            true
        }.onFailure {
            AppLogTV.w(TAG, "整机拦截开启失败: ${it.message}")
        }.getOrDefault(false)
    }

    /** 【A7】解除整机拦截（家长验证通过 / 紧急解除时调用） */
    fun release(context: Context): Boolean {
        if (!isOwner(context)) return false
        return runCatching {
            val d = dpm(context)
            val a = admin(context)
            d.setLockTaskPackages(a, emptyArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                d.setStatusBarDisabled(a, false)
            }
            AppLogTV.i(TAG, "整机拦截已解除：锁任务白名单已清空（kiosk 强制退出）")
            true
        }.onFailure {
            AppLogTV.w(TAG, "整机拦截解除失败: ${it.message}")
        }.getOrDefault(false)
    }

    // ==================== 【A15】禁止恢复出厂 / 禁止截屏（家长可解除）====================

    fun setFactoryResetBlocked(context: Context, blocked: Boolean): Boolean {
        if (!isOwner(context)) {
            AppLogTV.w(TAG, "禁止恢复出厂需要设备所有者（当前不是）")
            return false
        }
        return runCatching {
            val d = dpm(context)
            val a = admin(context)
            if (blocked) {
                d.addUserRestriction(a, UserManager.DISALLOW_FACTORY_RESET)
                AppLogTV.i(TAG, "【A15】已禁止恢复出厂（家长可在「账号安全」页解除）")
            } else {
                d.clearUserRestriction(a, UserManager.DISALLOW_FACTORY_RESET)
                AppLogTV.i(TAG, "【A15】已解除「禁止恢复出厂」")
            }
            true
        }.onFailure {
            AppLogTV.w(TAG, "设置「禁止恢复出厂」失败: ${it.message}")
        }.getOrDefault(false)
    }

    /** 读系统实际状态（尽力而为；DO 读得到自己设的限制） */
    fun isFactoryResetBlocked(context: Context): Boolean = runCatching {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        @Suppress("DEPRECATION")
        um.userRestrictions.getBoolean(UserManager.DISALLOW_FACTORY_RESET, false)
    }.getOrDefault(false)

    fun setScreenCaptureBlocked(context: Context, blocked: Boolean): Boolean {
        if (!isOwner(context)) return false
        return runCatching {
            dpm(context).setScreenCaptureDisabled(admin(context), blocked)
            AppLogTV.i(TAG, "【A15】禁截屏=" + blocked)
            true
        }.onFailure { AppLogTV.w(TAG, "设置禁截屏失败: ${it.message}") }.getOrDefault(false)
    }

    fun isScreenCaptureBlocked(context: Context): Boolean = runCatching {
        dpm(context).getScreenCaptureDisabled(admin(context))
    }.getOrDefault(false)
}
