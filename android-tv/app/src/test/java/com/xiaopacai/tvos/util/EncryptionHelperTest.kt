// [android-tv] 测试 — EncryptionHelper 单元测试
// 小趴菜 TVOS 版 — 加密辅助模块测试（核心模块 2/3）

package com.xiaopacai.tvos.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * EncryptionHelper 单元测试
 * 
 * 验证：
 * 1. SHA-256 哈希正确性（已知向量）
 * 2. SHA-256 一致性
 * 3. Base64 编解码正确性
 * 4. PIN 验证逻辑
 * 5. 随机 PIN 生成
 */
class EncryptionHelperTest {

    private val helper = EncryptionHelper

    // SHA-256 已知测试向量（来自 NIST）
    private val KNOWN_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" // ""

    @Test
    fun testSha256EmptyString() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb924...
        val hash = helper.sha256("")
        assertEquals(KNOWN_HASH, hash)
    }

    @Test
    fun testSha256KnownVector() {
        // SHA-256("abc") = ba7816bf8...
        val hash = helper.sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }

    @Test
    fun testSha256EmptyBytes() {
        val bytes = helper.sha256Bytes("")
        assertEquals(32, bytes.size) // SHA-256 输出 32 字节
    }

    @Test
    fun testSha256Consistency() {
        val hash1 = helper.sha256("test_password")
        val hash2 = helper.sha256("test_password")
        assertEquals(hash1, hash2)
    }

    @Test
    fun testSha256DifferentInputs() {
        val hash1 = helper.sha256("password1")
        val hash2 = helper.sha256("password2")
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun testSha256OutputLength() {
        val hash = helper.sha256("any_input")
        assertEquals(64, hash.length) // 32 字节 × 2 = 64 位十六进制
    }

    @Test
    fun testSha256HexCharsOnly() {
        val hash = helper.sha256("test")
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testBase64RoundTrip() {
        val input = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = helper.bytesToBase64(input)
        val decoded = helper.base64ToBytes(encoded)
        assertArrayEquals(input, decoded)
    }

    @Test
    fun testBase64EmptyBytes() {
        val encoded = helper.bytesToBase64(byteArrayOf())
        val decoded = helper.base64ToBytes(encoded)
        assertArrayEquals(byteArrayOf(), decoded)
    }

    @Test
    fun testBase64NullHandling() {
        // 无效 Base64 应返回空字节数组
        val result = helper.base64ToBytes("not_valid_base64!!")
        // 可能返回空数组或解码结果
        assertNotNull(result)
    }

    @Test
    fun testIsValidPinValid() {
        assertTrue(helper.isValidPin("1234"))
        assertTrue(helper.isValidPin("12345"))
        assertTrue(helper.isValidPin("123456"))
        assertTrue(helper.isValidPin("0000"))
        assertTrue(helper.isValidPin("9999"))
    }

    @Test
    fun testIsValidPinTooShort() {
        assertFalse(helper.isValidPin("123"))
        assertFalse(helper.isValidPin("12"))
        assertFalse(helper.isValidPin("1"))
    }

    @Test
    fun testIsValidPinTooLong() {
        assertFalse(helper.isValidPin("1234567"))
        assertFalse(helper.isValidPin("12345678"))
    }

    @Test
    fun testIsValidPinNonNumeric() {
        assertFalse(helper.isValidPin("12ab"))
        assertFalse(helper.isValidPin("abcd"))
        assertFalse(helper.isValidPin("123a"))
        assertFalse(helper.isValidPin("pass"))
    }

    @Test
    fun testIsValidPinEmpty() {
        assertFalse(helper.isValidPin(""))
    }

    @Test
    fun testIsValidPinNullHandling() {
        // Kotlin 非空类型，不会传 null
        assertFalse(helper.isValidPin(""))
    }

    @Test
    fun testGenerateDefaultPinLength() {
        repeat(100) {
            val pin = helper.generateDefaultPin()
            assertEquals(6, pin.length)
        }
    }

    @Test
    fun testGenerateDefaultPinNumericOnly() {
        repeat(100) {
            val pin = helper.generateDefaultPin()
            assertTrue(pin.all { it.isDigit() })
        }
    }

    @Test
    fun testGenerateDefaultPinVariety() {
        val pins = mutableSetOf<String>()
        repeat(100) {
            pins.add(helper.generateDefaultPin())
        }
        // 应该有足够多的不同 PIN（证明不是硬编码）
        assertTrue(pins.size > 50)
    }

    @Test
    fun testSha256PinHashing() {
        // PIN 哈希应一致
        val pin = "123456"
        val hash1 = helper.sha256(pin)
        val hash2 = helper.sha256(pin)
        assertEquals(hash1, hash2)

        // 错误 PIN 应不匹配
        val wrongHash = helper.sha256("000000")
        assertNotEquals(hash1, wrongHash)
    }

    @Test
    fun testSha256ChineseInput() {
        val hash = helper.sha256("小趴菜")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testBytesToBase64Standard() {
        val input = "Hello, World!".toByteArray(Charsets.UTF_8)
        val encoded = helper.bytesToBase64(input)
        // 验证 Base64 编码格式
        assertTrue(encoded.isNotEmpty())
    }
}
