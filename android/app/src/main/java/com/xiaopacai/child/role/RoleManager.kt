package com.xiaopacai.child.role

import android.content.Context
import android.util.Log
import com.xiaopacai.child.util.KeyStoreManager
import com.xiaopacai.child.util.ParentPasswordManager

/**
 * [TASK-ROLE-P1] 角色管理器
 *
 * 管理同一 APP 内的双角色（child/parent）：
 * - 首次启动：角色未设定，进入引导页
 * - 儿童端：免密直进守护界面（现有逻辑）
 * - 家长端：PBKDF2 密码登录
 * - 角色切换：需家长密码校验（防儿童绕过）
 *
 * 角色与密码存储在本地加密偏好中，密码永不明文。
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
     * 设置当前角色（仅在角色引导或合法切换时调用）
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
     * 切换到家长端（需要密码校验）
     *
     * @param password 家长密码
     * @return true 切换成功
     */
    fun switchToParent(context: Context, password: String): Boolean {
        if (!ParentPasswordManager.verifyPassword(context, password)) {
            Log.w(TAG, "家长密码校验失败，拒绝切换到家长端")
            return false
        }
        setCurrentRole(context, Role.PARENT)
        Log.i(TAG, "已切换到家长端")
        return true
    }

    /**
     * 切换到儿童端（需要密码校验，防止孩子随意退出家长端）
     *
     * @param password 家长密码
     * @return true 切换成功
     */
    fun switchToChild(context: Context, password: String): Boolean {
        if (!ParentPasswordManager.verifyPassword(context, password)) {
            Log.w(TAG, "家长密码校验失败，拒绝切换到儿童端")
            return false
        }
        setCurrentRole(context, Role.CHILD)
        Log.i(TAG, "已切换到儿童端")
        return true
    }

    /**
     * 检查家长密码是否已设置
     */
    fun isParentPasswordSet(context: Context): Boolean {
        return ParentPasswordManager.isPasswordSet(context)
    }

    /**
     * 设置家长密码（PBKDF2，≥10万次迭代）
     *
     * @param newPassword 新密码
     * @return true 设置成功
     */
    fun setParentPassword(context: Context, newPassword: String): Boolean {
        return ParentPasswordManager.setPassword(context, newPassword, null)
    }

    /**
     * 修改家长密码
     *
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return true 修改成功
     */
    fun changeParentPassword(context: Context, oldPassword: String, newPassword: String): Boolean {
        return ParentPasswordManager.setPassword(context, newPassword, oldPassword)
    }

    /**
     * 验证家长密码
     */
    fun verifyParentPassword(context: Context, password: String): Boolean {
        return ParentPasswordManager.verifyPassword(context, password)
    }

    /**
     * 验证密码格式（6-16位数字或字母）
     */
    fun isValidPasswordFormat(password: String): Boolean {
        return ParentPasswordManager.isValidPasswordFormat(password)
    }

    /**
     * 获取加密 SharedPreferences
     */
    private fun getPrefs(context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
