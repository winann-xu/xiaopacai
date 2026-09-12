// [android-tv] 工具类 — 加密辅助
// 小趴菜 TVOS 版 — SHA-256 + Base64

package com.xiaopacai.tvos.util

import java.security.MessageDigest
import java.util.Base64
import java.util.Random

/**
 * 加密工具类
 */
object EncryptionHelper {

    /**
     * SHA-256 哈希（返回 hex 字符串）
     */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * SHA-256 哈希（返回 byte 数组）
     */
    fun sha256Bytes(input: String): ByteArray {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
    }

    /**
     * 将字节数组转为 Base64 字符串
     */
    fun bytesToBase64(data: ByteArray): String {
        return Base64.getEncoder().encodeToString(data)
    }

    /**
     * 将 Base64 字符串转为字节数组
     */
    fun base64ToBytes(data: String): ByteArray {
        return try {
            Base64.getDecoder().decode(data)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /**
     * 生成随机 PIN（6 位数字）
     */
    fun generateDefaultPin(): String {
        val chars = "0123456789"
        val random = Random()
        return (1..6)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    /**
     * 验证 PIN 格式（4-6 位数字）
     */
    fun isValidPin(pin: String): Boolean {
        return pin.length in 4..6 && pin.all { it.isDigit() }
    }
}
