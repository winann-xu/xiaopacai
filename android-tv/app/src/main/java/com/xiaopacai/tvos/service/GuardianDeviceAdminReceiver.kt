// [android-tv] 设备管理员接收器 —— 【项2】防卸载
// 小趴菜 TVOS 版
//
// 作用：家长激活设备管理员后，本应用被「卸载」这一步会被系统拦下
// （孩子没法把守护 App 直接干掉）。
//
// 激活方式（电视上需要家长操作一次）：
//   设置 → 安全 → 设备管理员 → 小趴菜电视守护 → 激活
//   或从「家长设置 → 防护增强」按钮一键跳转过去。

package com.xiaopacai.tvos.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class GuardianDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "设备管理员已激活")
        val doOwner = isDeviceOwner(context)
        com.xiaopacai.tvos.util.AppLogTV.i(
            TAG,
            if (doOwner) "设备管理员已激活；本应用**已是设备所有者**" else "设备管理员已激活（非设备所有者）"
        )
        applyDeviceOwnerPolicies(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "设备管理员被关闭，防卸载能力已失效")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "关闭后孩子将可以直接卸载小趴菜电视守护，家长管控会失效。确定要关闭吗？"

    companion object {
        private const val TAG = "GuardianDeviceAdmin"

        /**
         * 【DO】本应用是否为设备所有者。
         * 设备所有者 = 比普通管理员高一个量级：可真正禁止卸载、隐藏/禁用任意应用（应用拦截）、
         * 锁任务模式（kiosk，退不出去）、禁止截屏等。
         */
        fun isDeviceOwner(context: Context): Boolean = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        }.getOrDefault(false)

        /**
         * 【DO】兑现设备所有者才有的能力：真正禁止卸载本应用。
         * 非 DO 调用 setUninstallBlocked 会抛 SecurityException（5.1 只有 device/profile owner 可调用），
         * 所以先判断再调；不是 DO 时退回「管理员已激活，卸载需先停用管理员」这层保护。
         */
        fun applyDeviceOwnerPolicies(context: Context) {
            if (!isDeviceOwner(context)) {
                Log.i(TAG, "非设备所有者：跳过 DO 专属策略（保留普通管理员保护）")
                com.xiaopacai.tvos.util.AppLogTV.i(
                    TAG, "普通管理员保护生效：卸载必须先停用管理员（停用时会劝退）"
                )
                return
            }
            runCatching {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(context, GuardianDeviceAdminReceiver::class.java)
                dpm.setUninstallBlocked(admin, context.packageName, true)
                Log.i(TAG, "【DO】已真正禁止卸载：${context.packageName}")
                com.xiaopacai.tvos.util.AppLogTV.i(TAG, "【DO】已真正禁止卸载本应用（系统卸载入口已封）")
            }.onFailure {
                Log.w(TAG, "【DO】禁止卸载调用失败", it)
                com.xiaopacai.tvos.util.AppLogTV.w(TAG, "【DO】禁止卸载失败: ${it.message}")
            }
        }

        /**
         * 【安全阀】解除设备所有者 + 停用设备管理员（危险操作，只在「账号安全」页由家长触发）。
         *
         * 为什么要它：拿到 DO 后本应用会 setUninstallBlocked(true)，于是这个包【装不上也卸不掉】
         * （换包/清场都要恢复出厂）。必须留一个家长可达的解除入口。
         *
         * 顺序：先解除「禁止卸载」→ 清 DO → 再停用普通管理员（这样系统卸载入口才真正可用）。
         */
        fun clearDeviceOwner(context: Context): Boolean = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, GuardianDeviceAdminReceiver::class.java)
            if (isDeviceOwner(context)) {
                runCatching { dpm.setUninstallBlocked(admin, context.packageName, false) }
                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(context.packageName)
                Log.i(TAG, "【安全阀】已清除设备所有者")
                com.xiaopacai.tvos.util.AppLogTV.i(TAG, "【安全阀】已解除设备所有者（现在可以卸载/换包）")
            } else {
                com.xiaopacai.tvos.util.AppLogTV.i(TAG, "【安全阀】当前不是设备所有者，跳过清 DO")
            }
            // 顺带停用普通管理员，否则卸载仍会被“请先停用管理员”拦住
            runCatching {
                if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
            }
            true
        }.onFailure {
            Log.e(TAG, "【安全阀】解除设备所有者失败", it)
            com.xiaopacai.tvos.util.AppLogTV.e(TAG, "【安全阀】解除失败: ${it.message}")
        }.getOrDefault(false)

        /** 【安全阀】只停用普通设备管理员（保留 DO 时系统会拒绝，属预期） */
        fun deactivateAdmin(context: Context): Boolean = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, GuardianDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
            com.xiaopacai.tvos.util.AppLogTV.i(TAG, "【安全阀】已停用设备管理员")
            true
        }.onFailure {
            Log.w(TAG, "【安全阀】停用管理员失败（若是 DO 属预期）", it)
            com.xiaopacai.tvos.util.AppLogTV.w(TAG, "【安全阀】停用管理员失败: ${it.message}")
        }.getOrDefault(false)

        /** 本应用是否已被禁止卸载（DO 生效的可视证据） */
        fun isUninstallBlocked(context: Context): Boolean = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isUninstallBlocked(
                ComponentName(context, GuardianDeviceAdminReceiver::class.java),
                context.packageName
            )
        }.getOrDefault(false)

        /** 是否已激活设备管理员 */
        fun isActive(context: Context): Boolean = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(ComponentName(context, GuardianDeviceAdminReceiver::class.java))
        }.getOrDefault(false)

        /** 跳转到系统「激活设备管理员」页面 */
        fun requestActivate(context: Context) {
            runCatching {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(context, GuardianDeviceAdminReceiver::class.java)
                    )
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "激活后可防止孩子直接卸载小趴菜电视守护"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.onFailure { Log.e(TAG, "跳转设备管理员页面失败", it) }
        }
    }
}
