package com.xiaopacai.child.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import com.xiaopacai.child.service.AntiBypassService
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * [TASK-D3-03] 家长密码管理器
 *
 * 管理家长验证密码，用于：
 * - 退出守护模式
 * - 修改应用设置
 * - 卸载应用
 * - 关闭无障碍服务
 * - 禁用设备管理器
 *
 * 安全措施：
 * - PBKDF2-HMAC-SHA256 密钥派生（100,000 次迭代，防暴力破解）
 * - 随机盐值（每次密码设置生成新盐）
 * - 密码哈希永不存储明文
 * - 使用 KeyStore 加密存储盐值和哈希
 */
object ParentPasswordManager {

    private const val TAG = "ParentPassword"
    private const val PREFS_NAME = "xiaopacai_parent_auth"

    // PBKDF2 参数
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 100_000  // 10万次迭代
    private const val HASH_LENGTH = 256  // 输出 256 bit
    private const val SALT_LENGTH = 32   // 盐值 256 bit

    // SharedPreferences Key
    private const val KEY_PASSWORD_HASH = "parent_password_hash"
    private const val KEY_PASSWORD_SALT = "parent_password_salt"
    private const val KEY_FAILED_ATTEMPTS = "password_failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "password_lockout_until"

    // 安全限制
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L  // 锁定 5 分钟

    /**
     * 检查家长密码是否已设置
     */
    fun isPasswordSet(context: Context): Boolean {
        val prefs = getSecurePrefs(context)
        return prefs.contains(KEY_PASSWORD_HASH)
    }

    /**
     * 设置或修改家长密码
     *
     * @param context 应用上下文
     * @param newPassword 新密码（6-16位数字或字母）
     * @param oldPassword 旧密码（首次设置时为 null）
     * @return true 如果设置成功
     */
    fun setPassword(context: Context, newPassword: String, oldPassword: String? = null): Boolean {
        // 验证密码强度
        if (!isValidPasswordFormat(newPassword)) {
            Log.w(TAG, "密码格式无效（需要6-16位数字或字母）")
            return false
        }

        val prefs = getSecurePrefs(context)

        // 如果已有密码，验证旧密码
        if (isPasswordSet(context)) {
            if (oldPassword == null || !verifyPassword(context, oldPassword)) {
                Log.w(TAG, "旧密码验证失败，无法修改密码")
                return false
            }
        }

        // 生成新盐值和哈希
        val salt = generateSalt()
        val hash = derivePasswordHash(newPassword, salt)

        // 使用 KeyStore 加密存储
        try {
            val encryptedHash = KeyStoreManager.encryptForStorage(hash)
            val encryptedSalt = KeyStoreManager.encryptForStorage(
                salt.joinToString(",") { it.toString() }
            )

            prefs.edit()
                .putString(KEY_PASSWORD_HASH, encryptedHash)
                .putString(KEY_PASSWORD_SALT, encryptedSalt)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0)
                .apply()

            Log.i(TAG, "家长密码已更新")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "密码存储失败: ${e.message}")
            return false
        }
    }

    /**
     * 验证家长密码
     *
     * @param context 应用上下文
     * @param password 待验证密码
     * @return true 如果密码正确且未锁定
     */
    fun verifyPassword(context: Context, password: String): Boolean {
        val prefs = getSecurePrefs(context)

        // 检查是否处于锁定状态
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        if (lockoutUntil > 0 && System.currentTimeMillis() < lockoutUntil) {
            val remainingSeconds = (lockoutUntil - System.currentTimeMillis()) / 1000
            Log.w(TAG, "密码验证已锁定，剩余 ${remainingSeconds}秒")
            return false
        }

        // [SEC-P1] 未设置家长密码时一律拒绝验证（删除默认密码 000000 后门，红线 R4.x）：
        // 首次使用必须由家长在「家长模式」中显式设置密码后才能进入受保护功能
        if (!isPasswordSet(context)) {
            Log.w(TAG, "家长密码尚未设置，拒绝验证（请家长先在家长模式中设置密码）")
            return false
        }

        // 解密存储的盐值和哈希
        return try {
            val encryptedHash = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
            val encryptedSalt = prefs.getString(KEY_PASSWORD_SALT, null) ?: return false

            val storedHash = KeyStoreManager.decryptFromStorage(encryptedHash)
            val saltStr = KeyStoreManager.decryptFromStorage(encryptedSalt)
            val salt = saltStr.split(",").map { it.toByte() }.toByteArray()

            val inputHash = derivePasswordHash(password, salt)
            val match = MessageDigest.isEqual(
                storedHash.toByteArray(Charsets.UTF_8),
                inputHash.toByteArray(Charsets.UTF_8)
            )

            if (match) {
                // 验证成功：重置失败计数
                prefs.edit()
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0)
                    .apply()
            } else {
                // 验证失败：增加计数
                recordFailedAttempt(context, prefs)
            }

            match
        } catch (e: Exception) {
            Log.e(TAG, "密码验证异常: ${e.message}")
            false
        }
    }

    /**
     * 记录失败尝试并处理锁定
     */
    private fun recordFailedAttempt(context: Context, prefs: SharedPreferences) {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            editor.putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            Log.w(TAG, "密码验证锁定 ${LOCKOUT_DURATION_MS / 60000}分钟（$attempts 次失败尝试）")

            // 通知家长端
            AntiBypassService.notifySecurityEvent(
                context,
                "密码尝试锁定",
                "连续 $attempts 次密码输入错误，已临时锁定 5 分钟。"
            )
        }

        editor.apply()
    }

    /**
     * 重置失败计数（用于管理员远程解锁）
     */
    fun resetFailedAttempts(context: Context) {
        getSecurePrefs(context).edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0)
            .apply()
    }

    /**
     * [TASK-PRELAUNCH-PARENT-RESET] 清除家长密码（换账号清理专用）。
     * 调用方必须先完成密码验证（ParentAccountReset.resetAccount 内保证），
     * 清除后下次进入家长端走首次设置密码流程（"新账号绑定"状态）。
     */
    fun clearPassword(context: Context) {
        getSecurePrefs(context).edit()
            .remove(KEY_PASSWORD_HASH)
            .remove(KEY_PASSWORD_SALT)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0)
            .apply()
        Log.i(TAG, "家长密码已清除（换账号清理）")
    }

    /**
     * 使用 PBKDF2-HMAC-SHA256 派生密码哈希
     */
    private fun derivePasswordHash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_LENGTH)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val hash = factory.generateSecret(spec).encoded
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * 生成密码学安全的随机盐值
     */
    private fun generateSalt(): ByteArray {
        return SecureRandom().let { random ->
            ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        }
    }

    /**
     * 验证密码格式
     * 要求：6-16位数字或字母（不含特殊字符以避免输入困难）
     */
    fun isValidPasswordFormat(password: String): Boolean {
        return password.length in 6..16 && password.all { it.isLetterOrDigit() }
    }

    /**
     * 获取加密 SharedPreferences
     */
    private fun getSecurePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
