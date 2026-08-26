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

    fun verifyPassword(context: Context, password: String): PasswordResult {
        if (isLockedOut(context)) {
            val remaining = (getLockoutUntil(context) - System.currentTimeMillis()) / 1000
            return PasswordResult.LockedOut("尝试次数过多，请等待 ${remaining / 60 + 1} 分钟")
        }

        val storedHash = getStoredPasswordHash(context)
        if (storedHash == null) {
            val result = CloudAccountManager.login(context,
                CloudAccountManager.getBoundEmail(context) ?: "", password)
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
