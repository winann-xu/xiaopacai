package com.xiaopacai.child.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [TASK-D3-02] Android KeyStore 密钥管理服务
 *
 * 使用 AndroidKeyStore 安全地管理数据库加密密钥与敏感数据。
 * 密钥存储于 TEE/SE 安全硬件中（如果设备支持），永不离开安全环境。
 *
 * 安全等级：
 * - AES-256-GCM 加密（硬件支持时由 TEE 保护）
 * - 密钥不可导出（KeyProperties.PURPOSE_ENCRYPT/DECRYPT）
 * - 用户认证绑定（可选）
 */
object KeyStoreManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val DB_KEY_ALIAS = "xiaopacai_db_master_key"
    private const val SECURE_PREFS_KEY_ALIAS = "xiaopacai_secure_prefs_key"

    // AES-GCM 认证标签长度（128 bit）
    private const val GCM_TAG_LENGTH = 128
    // GCM IV/Nonce 长度（96 bit，推荐值）
    private const val GCM_IV_LENGTH = 12

    /**
     * 获取或创建数据库主密钥
     *
     * 如果 KeyStore 中已存在则直接加载；否则生成新的 AES-256 密钥并持久化到硬件安全模块。
     * 密钥仅在需要时加载到内存，用完即释放。
     *
     * @return 数据库加密密钥（AES-256，32 字节）
     */
    fun getOrCreateDbMasterKey(): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // 检查密钥是否已存在
        if (keyStore.containsAlias(DB_KEY_ALIAS)) {
            val entry = keyStore.getEntry(DB_KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey.encoded  // 加载已有密钥
        }

        // 生成新密钥（AES-256-GCM，存储在 TEE 中）
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            DB_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)  // 强制随机 IV（防重放）
            .build()

        keyGenerator.init(spec)
        val secretKey = keyGenerator.generateKey()

        return secretKey.encoded
    }

    /**
     * 从 KeyStore 删除数据库主密钥（用于取消配对时清理）
     */
    fun deleteDbMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(DB_KEY_ALIAS)) {
            keyStore.deleteEntry(DB_KEY_ALIAS)
        }
    }

    /**
     * 验证数据库密钥是否可用
     *
     * @return true 如果密钥存在且可正常读写
     */
    fun isDbKeyAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.containsAlias(DB_KEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    // === 安全 SharedPreferences 加密 ===

    /**
     * 使用 KeyStore 密钥加密敏感数据（用于存储到 SharedPreferences）
     *
     * 加密方案：AES-256-GCM，每次加密生成新的随机 IV
     *
     * @param plainText 明文
     * @return Base64 编码的密文（IV + 密文 + GCM 标签）
     */
    fun encryptForStorage(plainText: String): String {
        val secretKey = getOrCreateEncryptionKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv  // 随机生成的 IV（GCM 模式自动生成）
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // 拼接 IV + 密文（IV 长度固定 12 字节，GCM 标签自动附加在密文末尾）
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密从 SharedPreferences 读取的敏感数据
     *
     * @param encodedText Base64 编码的密文
     * @return 明文
     */
    fun decryptFromStorage(encodedText: String): String {
        val combined = Base64.decode(encodedText, Base64.NO_WRAP)
        val secretKey = getOrCreateEncryptionKey()

        // 分离 IV（前12字节）和密文（剩余部分含 GCM 标签）
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val plainBytes = cipher.doFinal(cipherText)
        return String(plainBytes, Charsets.UTF_8)
    }

    /**
     * 获取或创建用于 SharedPreferences 加密的 AES 密钥
     */
    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(SECURE_PREFS_KEY_ALIAS)) {
            val entry = keyStore.getEntry(SECURE_PREFS_KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            SECURE_PREFS_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
