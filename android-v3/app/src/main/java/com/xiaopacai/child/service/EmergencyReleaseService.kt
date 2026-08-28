package com.xiaopacai.child.service

import android.content.Context
import android.util.Log
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.CloudAccountManager
import com.xiaopacai.child.util.KeyStoreManager
import kotlinx.coroutines.*
import java.security.MessageDigest

object EmergencyReleaseService {

    private const val TAG = "EmergencyRelease"
    private const val PREFS_NAME = "emergency_release_prefs"
    private const val KEY_ACTIVE = "emergency_active"
    private const val KEY_START_TIME = "emergency_start_time"
    private const val KEY_DURATION_MINUTES = "emergency_duration_minutes"
    private const val KEY_FAILED_ATTEMPTS = "emergency_failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "emergency_lockout_until"
    private const val KEY_PASSWORD_HASH = "parent_password_hash"

    const val DEFAULT_DURATION_MINUTES = 60
    const val MAX_FAILED_ATTEMPTS = 3
    const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L

    fun isActive(context: Context): Boolean {
        if (!isReleaseActive(context)) return false
        val elapsed = System.currentTimeMillis() - getStartTime(context)
        val durationMs = getDurationMinutes(context) * 60 * 1000L
        if (elapsed >= durationMs) {
            deactivate(context)
            return false
        }
        return true
    }

    fun getRemainingMinutes(context: Context): Int {
        if (!isReleaseActive(context)) return 0
        val elapsed = System.currentTimeMillis() - getStartTime(context)
        val durationMs = getDurationMinutes(context) * 60 * 1000L
        val remaining = ((durationMs - elapsed) / (60 * 1000)).toInt()
        return remaining.coerceAtLeast(0)
    }

    fun activate(context: Context, durationMinutes: Int = DEFAULT_DURATION_MINUTES): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_START_TIME, System.currentTimeMillis())
            .putInt(KEY_DURATION_MINUTES, durationMinutes)
            .apply()
        AppLog.i(TAG, "紧急解除已激活，时长 $durationMinutes 分钟")
        val collector = GuardianForegroundService.getCollector()
        collector?.pauseEnforcement(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // [TASK-V208-UNBIND-FIX] 解绑后无归属账号/令牌，云端上报必然 403：
                // 本地紧急解除照常生效，云端记录跳过（避免误导性报错）。
                if (CloudAccountManager.getBoundEmail(context) != null && CloudAccountManager.getToken(context) != null) {
                    val result = CloudSyncService.requestEmergencyRelease(context, "家长紧急停用 $durationMinutes 分钟", durationMinutes)
                    if (result is CloudSyncService.CloudResult.Failed) {
                        AppLog.w(TAG, "云端紧急解除上报失败: ${result.reason}")
                    }
                } else {
                    AppLog.i(TAG, "设备未绑定家长账号，紧急解除仅本地生效（不上报云端）")
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "云端紧急解除上报异常: ${e.message}")
            }
        }
        return true
    }

    fun deactivate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_START_TIME, 0)
            .apply()
        AppLog.i(TAG, "紧急解除已停用，守护恢复")
        val collector = GuardianForegroundService.getCollector()
        collector?.pauseEnforcement(false)
    }

    /**
     * 家长密码验证。
     * - 已设置本地紧急密码 → 本地 SHA-256 校验；
     * - 已绑定账号 → 云端邮箱+密码验证；
     * - 未绑定（解绑后）→ 使用调用方提供的邮箱（email 参数）云端验证，
     *   不修改本地绑定状态；两者皆无时给出明确提示，不再用空邮箱请求导致“邮箱或密码错误”。
     */
    fun verifyPassword(context: Context, password: String, email: String? = null): PasswordResult {
        if (isLockedOut(context)) {
            val remaining = (getLockoutUntil(context) - System.currentTimeMillis()) / 1000
            return PasswordResult.LockedOut("尝试次数过多，请等待 ${remaining / 60 + 1} 分钟")
        }

        val storedHash = getStoredPasswordHash(context)
        if (storedHash == null) {
            val boundEmail = CloudAccountManager.getBoundEmail(context)
            val verifyEmail = email?.trim()?.takeIf { it.isNotBlank() }
                ?: boundEmail?.trim()?.takeIf { it.isNotBlank() }
            if (verifyEmail == null) {
                return PasswordResult.Incorrect("设备未绑定家长账号，请先输入账号邮箱进行验证")
            }
            // [TASK-V208-UNBIND-FIX] 解绑后使用界面输入的邮箱做无状态验证：
            // 不把邮箱/JWT 写入本地绑定（避免首页误显示“已绑定”）。
            val result = if (boundEmail != null) {
                CloudAccountManager.login(context, boundEmail, password)
            } else {
                CloudAccountManager.verifyCredentials(context, verifyEmail, password)
            }
            when (result) {
                is CloudAccountManager.LoginResult.Success -> {
                    resetFailedAttempts(context)
                    return PasswordResult.Success
                }
                is CloudAccountManager.LoginResult.Failed -> {
                    recordFailedAttempt(context)
                    return PasswordResult.Incorrect(result.reason)
                }
            }
        } else {
            val inputHash = hashPassword(password)
            if (inputHash == storedHash) {
                resetFailedAttempts(context)
                return PasswordResult.Success
            } else {
                recordFailedAttempt(context)
                return PasswordResult.Incorrect("密码错误")
            }
        }
    }

    fun setPassword(context: Context, password: String) {
        val hash = hashPassword(password)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .apply()
    }

    fun hasPasswordSet(context: Context): Boolean {
        return getStoredPasswordHash(context) != null ||
            CloudAccountManager.getBoundEmail(context) != null
    }

    sealed class PasswordResult {
        object Success : PasswordResult()
        data class Incorrect(val reason: String) : PasswordResult()
        data class LockedOut(val message: String) : PasswordResult()
    }

    private fun isReleaseActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)
    }

    private fun getStartTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_START_TIME, 0)
    }

    private fun getDurationMinutes(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DURATION_MINUTES, DEFAULT_DURATION_MINUTES)
    }

    private fun getStoredPasswordHash(context: Context): String? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PASSWORD_HASH, null) ?: return null
        return KeyStoreManager.decryptPrefsValue(stored).ifBlank { null }
    }

    private fun hashPassword(password: String): String {
        val bytes = password.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun isLockedOut(context: Context): Boolean {
        val lockoutUntil = getLockoutUntil(context)
        return lockoutUntil > System.currentTimeMillis()
    }

    private fun getLockoutUntil(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LOCKOUT_UNTIL, 0)
    }

    private fun recordFailedAttempt(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply()
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            prefs.edit()
                .putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
                .apply()
            AppLog.w(TAG, "紧急解除密码尝试次数过多，锁定 5 分钟")
        }
    }

    private fun resetFailedAttempts(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }
}
