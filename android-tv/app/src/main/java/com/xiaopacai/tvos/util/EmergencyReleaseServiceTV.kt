// [android-tv] 工具类 — 家长紧急解除（参照手机版 EmergencyReleaseService）
//
// 目的：解决「超时锁屏 + 断网 = 家长也解不开」的死锁。
// 机制（与手机版一致）：
//   1. 限时解除：默认 60 分钟，到期自动恢复守护，不需要再操作；
//   2. 验证方式：优先本地「紧急密码」（离线可用），未设置时回退云端账号密码登录；
//   3. 防爆破：连续 3 次失败锁定 5 分钟。
// 解除期间 TimeoutExecutorTV 不再锁屏。

package com.xiaopacai.tvos.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.coroutines.resume

object EmergencyReleaseServiceTV {

    private const val TAG = "EmergencyReleaseTV"
    private const val PREFS_NAME = "emergency_release_prefs"

    private const val KEY_ACTIVE = "emergency_active"
    private const val KEY_START_TIME = "emergency_start_time"
    private const val KEY_DURATION_MINUTES = "emergency_duration_minutes"
    private const val KEY_FAILED_ATTEMPTS = "emergency_failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "emergency_lockout_until"
    private const val KEY_PASSWORD_HASH = "emergency_password_hash"

    const val DEFAULT_DURATION_MINUTES = 60
    const val MAX_FAILED_ATTEMPTS = 3
    private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
    private const val OFFLINE_HINT = "网络不可用；建议先在「家长设置 → 紧急解除」里设一个紧急密码，断网也能解除"

    /** 验证结果 */
    sealed class Result {
        object Success : Result()
        data class Incorrect(val reason: String) : Result()
        data class LockedOut(val message: String) : Result()
    }

    // ==================== 解除状态 ====================

    fun isActive(context: Context): Boolean {
        if (!prefs(context).getBoolean(KEY_ACTIVE, false)) return false
        val elapsed = System.currentTimeMillis() - prefs(context).getLong(KEY_START_TIME, 0L)
        val durationMs = durationMinutes(context) * 60 * 1000L
        if (elapsed >= durationMs) {
            deactivate(context)
            return false
        }
        return true
    }

    fun getRemainingMinutes(context: Context): Int {
        if (!prefs(context).getBoolean(KEY_ACTIVE, false)) return 0
        val elapsed = System.currentTimeMillis() - prefs(context).getLong(KEY_START_TIME, 0L)
        val durationMs = durationMinutes(context) * 60 * 1000L
        return ((durationMs - elapsed) / 60_000L).toInt().coerceAtLeast(0)
    }

    fun activate(context: Context, durationMinutes: Int = DEFAULT_DURATION_MINUTES) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_START_TIME, System.currentTimeMillis())
            .putInt(KEY_DURATION_MINUTES, durationMinutes)
            .apply()
        Log.i(TAG, "紧急解除已激活，$durationMinutes 分钟后自动恢复守护")
    }

    fun deactivate(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_START_TIME, 0L)
            .apply()
        Log.i(TAG, "紧急解除已结束，守护恢复")
    }

    // ==================== 本地紧急密码（离线可用） ====================

    fun setPassword(context: Context, password: String) {
        prefs(context).edit().putString(KEY_PASSWORD_HASH, sha256(password)).apply()
    }

    fun clearPassword(context: Context) {
        prefs(context).edit().remove(KEY_PASSWORD_HASH).apply()
    }

    fun hasPassword(context: Context): Boolean = storedHash(context) != null

    // ==================== 验证 ====================

    /**
     * 家长验证。
     * - 已设本地紧急密码 → 本地校验（离线可用）；
     * - 未设 → 云端账号密码校验（account 为空时用已记忆的家长账号）。
     * 说明：本地密码存在时不会回退云端——避免"本地错但云端对"造成语义混乱；
     * 家长如需改回云端验证，可在设置里清除紧急密码。
     */
    suspend fun verifyPassword(context: Context, password: String, account: String? = null): Result {
        if (isLockedOut(context)) {
            val seconds = (lockoutUntil(context) - System.currentTimeMillis()) / 1000
            return Result.LockedOut("尝试次数过多，请等待 ${seconds / 60 + 1} 分钟")
        }

        val stored = storedHash(context)
        if (stored != null) {
            return if (sha256(password) == stored) {
                resetFailures(context)
                Result.Success
            } else {
                recordFailure(context)
                Result.Incorrect("紧急密码错误")
            }
        }

        val target = account?.trim()?.takeIf { it.isNotBlank() }
            ?: SharedPreferenceHelperTV.getParentAccount(context).first().trim().takeIf { it.isNotBlank() }
        if (target == null) {
            return Result.Incorrect("请先输入家长账号（邮箱）")
        }

        return when (val result = cloudVerify(context, target, password)) {
            is Result.Success -> {
                resetFailures(context)
                result
            }
            is Result.Incorrect -> {
                recordFailure(context)
                result
            }
            else -> result
        }
    }

    private suspend fun cloudVerify(context: Context, account: String, password: String): Result {
        val host = SharedPreferenceHelperTV.getCloudHost(context).first()
        val port = SharedPreferenceHelperTV.getCloudPort(context).first()
        val url = NetworkHelper.buildBaseUrl(host, port) + "/api/auth/login"
        val payload = JSONObject().apply {
            put("Username", account)
            put("Password", password)
        }.toString()

        val (code, _) = suspendCancellableCoroutine<Pair<Int, String>> { cont ->
            NetworkHelper.postJsonDetailed(url, payload) { c, body ->
                if (cont.isActive) cont.resume(c to body)
            }
        }

        return when {
            code in 200..299 -> Result.Success
            code == 401 -> Result.Incorrect("账号或密码错误")
            code == 429 -> Result.Incorrect("失败次数过多，请 1 小时后重试")
            code <= 0 -> Result.Incorrect(OFFLINE_HINT)
            else -> Result.Incorrect("验证失败（HTTP $code）")
        }
    }

    // ==================== 内部 ====================

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun durationMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DURATION_MINUTES, DEFAULT_DURATION_MINUTES)

    private fun storedHash(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD_HASH, null)?.takeIf { it.isNotBlank() }

    private fun lockoutUntil(context: Context): Long =
        prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0L)

    private fun isLockedOut(context: Context): Boolean =
        lockoutUntil(context) > System.currentTimeMillis()

    private fun recordFailure(context: Context) {
        val attempts = prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs(context).edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
            Log.w(TAG, "验证失败次数过多，锁定 5 分钟")
        }
        editor.apply()
    }

    private fun resetFailures(context: Context) {
        prefs(context).edit()
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
