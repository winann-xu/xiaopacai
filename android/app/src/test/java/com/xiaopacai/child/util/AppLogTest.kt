package com.xiaopacai.child.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [TASK-MILESTONE-V3] 需求 14：运行日志环形缓冲单元测试
 *
 * 覆盖：敏感信息打码（密码/验证码/JWT/64 位 hex/普通文本放行）、
 * 5000 条环形上限、文件持久化往返（重启加载）、清空。
 */
class AppLogTest {

    private var tmpDir: File? = null

    /** 测试注入的日志文件路径（initWithFile 需要文件而非目录） */
    private fun logFile() = File(tmpDir!!, "xpc_applog.txt")

    @Before
    fun setUp() {
        AppLog.resetForTest()
        tmpDir = createTempDir("xpc-applog-test")
    }

    @After
    fun tearDown() {
        AppLog.resetForTest()
        tmpDir?.deleteRecursively()
    }

    // ==================== 脱敏：写入时打码 ====================

    @Test
    fun maskSecrets_masksSecretAssignments() {
        assertEquals("password=***", AppLog.maskSecrets("password=abc123"))
        assertEquals("token: ***", AppLog.maskSecrets("token: abc.def"))
        assertEquals("api_key=***", AppLog.maskSecrets("api_key=sk-1234567890"))
        assertEquals("secret=***，连接失败", AppLog.maskSecrets("secret=xyz，连接失败"))
        assertEquals("PASSWORD=***", AppLog.maskSecrets("PASSWORD=hunter2"))
    }

    @Test
    fun maskSecrets_masksVerificationCodes() {
        assertEquals("验证码 ***，5 分钟内有效", AppLog.maskSecrets("验证码 123456，5 分钟内有效"))
        assertEquals("verification code: ***", AppLog.maskSecrets("verification code: 8888"))
        assertEquals("校验码***", AppLog.maskSecrets("校验码123456"))
        assertEquals("SMS code ***", AppLog.maskSecrets("SMS code 246810"))
        // 裸 "code:" 无验证码语义（HTTP code 等），不误伤
        assertEquals("HTTP code 500", AppLog.maskSecrets("HTTP code 500"))
    }

    @Test
    fun maskSecrets_masksJwtAndHex64() {
        assertEquals(
            "***",
            AppLog.maskSecrets("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sigAaaaAaaaAaaaAaaaAaaa")
        )
        assertEquals("***", AppLog.maskSecrets("a".repeat(64)))
        assertEquals("密钥 ***", AppLog.maskSecrets("密钥 ${"f".repeat(64)}"))
    }

    @Test
    fun maskSecrets_keepsNormalText() {
        val text = "设备列表已同步: 3 台"
        assertEquals(text, AppLog.maskSecrets(text))
        assertEquals("应用启动 v1.1.0（10100）", AppLog.maskSecrets("应用启动 v1.1.0（10100）"))
        // 非 64 位 hex、无标签的短数字不误伤
        assertEquals("心跳 15 分钟", AppLog.maskSecrets("心跳 15 分钟"))
    }

    // ==================== 环形缓冲上限 ====================

    @Test
    fun ringBuffer_capsAtMaxEntries() {
        for (i in 1..(AppLog.MAX_ENTRIES + 50)) AppLog.d("Test", "entry-$i")
        val all = AppLog.entries()
        assertEquals(AppLog.MAX_ENTRIES, all.size)
        // 最新在前：最新一条是最后写入的，最旧一条是第 51 条（前 50 条被挤出）
        assertEquals("entry-${AppLog.MAX_ENTRIES + 50}", all.first().msg)
        assertEquals("entry-51", all.last().msg)
    }

    // ==================== 文件持久化 ====================

    @Test
    fun fileRoundTrip_persistsSanitizedEntries() {
        AppLog.initWithFile(logFile())
        AppLog.i("Test", "first")
        AppLog.i("Test", "second password=hunter2")
        // 模拟重启：重置单例后重新从文件加载
        AppLog.resetForTest()
        AppLog.initWithFile(logFile())
        val all = AppLog.entries()
        // 除 init 自记一行外，两条 Test 日志完整还原
        assertEquals(2, all.count { it.tag == "Test" })
        assertTrue(all.any { it.msg == "first" })
        // 脱敏内容原样持久化（明文不落盘）
        assertTrue(all.any { it.msg == "second password=***" })
        assertTrue(all.none { it.msg.contains("hunter2") })
    }

    @Test
    fun fileRoundTrip_toleratesCorruptLines() {
        AppLog.initWithFile(logFile())
        AppLog.i("Test", "good-line")
        // 追加一行损坏数据（非 JSON）
        logFile().appendText("not-json-garbage\n")
        AppLog.resetForTest()
        AppLog.initWithFile(logFile())
        val all = AppLog.entries()
        assertTrue(all.any { it.msg == "good-line" })
    }

    // ==================== 清空 ====================

    @Test
    fun clear_emptiesBufferAndFile() {
        AppLog.initWithFile(logFile())
        AppLog.i("Test", "to-clear")
        AppLog.clear()
        // 内存即刻清空（init 自记行也被清掉）
        assertEquals(0, AppLog.entries().size)
        // 重启后文件也为空（仅新 init 自记一行）
        AppLog.resetForTest()
        AppLog.initWithFile(logFile())
        assertTrue(AppLog.entries().none { it.msg == "to-clear" })
    }
}
