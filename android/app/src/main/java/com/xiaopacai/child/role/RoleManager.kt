package com.xiaopacai.child.role

import android.content.Context
import android.util.Log

/**
 * [TASK-ROLE-P1] 角色管理器
 *
 * 管理同一 APP 内的双角色（child/parent）：
 * - 首次启动：角色未设定，进入引导页
 * - 儿童端：免密直进守护界面（现有逻辑）
 * - 家长端：云端账号（邮箱+密码）验证后进入（[TASK-ACCOUNT-V1] ADR 0009）
 * - 角色切换：调用方必须先完成云端验证（SystemGateDialog / 家长登录页），
 *   验证通过后直接调用 [setCurrentRole] 切换角色。
 *
 * [TASK-ACCOUNT-V1] 本地家长密码体系（PBKDF2）已退役，角色状态仅存本地偏好，
 * 家长身份验证统一由 CloudAccountManager 云端完成，密码永不落盘。
 */
object RoleManager {

    private const val TAG = "RoleManager"
    private const val PREFS_NAME = "xiaopacai_role_prefs"
    private const val KEY_CURRENT_ROLE = "current_role"
    private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"

    /**
     * 角色枚举
     */
    enum class Role(val value: String) {
        UNSET("unset"),   // 首次启动，未选择角色
        CHILD("child"),   // 儿童端
        PARENT("parent")  // 家长端
    }

    /**
     * 获取当前角色
     * UNSET 表示首次启动未选择
     */
    fun getCurrentRole(context: Context): Role {
        val prefs = getPrefs(context)
        val roleStr = prefs.getString(KEY_CURRENT_ROLE, Role.UNSET.value) ?: Role.UNSET.value
        return try {
            Role.valueOf(roleStr.uppercase())
        } catch (e: IllegalArgumentException) {
            Role.UNSET
        }
    }

    /**
     * 设置当前角色（[TASK-ACCOUNT-V1] 仅在云端验证通过后调用）
     */
    fun setCurrentRole(context: Context, role: Role) {
        getPrefs(context).edit()
            .putString(KEY_CURRENT_ROLE, role.value)
            .putBoolean(KEY_IS_FIRST_LAUNCH, false)
            .apply()
        Log.i(TAG, "角色已切换为: ${role.value}")
    }

    /**
     * 是否首次启动（未选择过角色）
     */
    fun isFirstLaunch(context: Context): Boolean {
        val prefs = getPrefs(context)
        return !prefs.contains(KEY_CURRENT_ROLE) ||
            prefs.getString(KEY_CURRENT_ROLE, Role.UNSET.value) == Role.UNSET.value
    }

    /**
     * 获取 SharedPreferences
     */
    private fun getPrefs(context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
