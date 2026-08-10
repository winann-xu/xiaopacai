package com.xiaopacai.child.util

import android.content.Context
import android.util.Log

/**
 * [TASK-D3-05] 统一数据库密码提供者
 *
 * 所有需要访问数据库的组件都通过此工具获取密码，
 * 避免分散的 SharedPreferences 读取和硬编码。
 *
 * 优先级：
 * 1. AndroidKeyStore（TEE/SE 硬件保护，首选）
 * 2. 加密 SharedPreferences 回退方案
 */
object DbPassphraseProvider {

    private const val TAG = "DbPassphrase"

    /**
     * 获取数据库加密密码
     *
     * @param context 应用上下文
     * @return 密码字节数组
     */
    fun getPassphrase(context: Context): ByteArray {
        return try {
            KeyStoreManager.getOrCreateDbMasterKey()
        } catch (e: Exception) {
            Log.w(TAG, "KeyStore 不可用，使用备用加密方案: ${e.message}")
            getFallbackPassphrase(context)
        }
    }

    /**
     * 备用密码方案（KeyStore 完全不可用时）
     */
    private fun getFallbackPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("guardian_secure_prefs", Context.MODE_PRIVATE)

        // 尝试读取加密存储的密钥
        val encryptedKey = prefs.getString("db_key_encrypted", null)
        if (encryptedKey != null) {
            return try {
                KeyStoreManager.decryptFromStorage(encryptedKey).toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "备用密钥解密失败，使用最终回退: ${e.message}")
                getUltimateFallback(context)
            }
        }

        return getUltimateFallback(context)
    }

    /**
     * 最终回退（仅首次安装、KeyStore 完全不可用时触发）
     */
    private fun getUltimateFallback(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("guardian_secure_prefs", Context.MODE_PRIVATE)
        var seed = prefs.getString("db_key_seed", null)
        if (seed == null) {
            seed = java.util.UUID.randomUUID().toString()
            try {
                // 尝试加密存储
                val encrypted = KeyStoreManager.encryptForStorage(seed)
                prefs.edit().putString("db_key_encrypted", encrypted).apply()
            } catch (e: Exception) {
                // 连加密存储都不行，混淆后存
                prefs.edit().putString("db_key_seed", seed.reversed()).apply()
            }
        }
        return seed.toByteArray(Charsets.UTF_8)
    }
}
