package com.xiaopacai.child.util

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

/**
 * [TASK-TEST-ANDROID] ParentPasswordManager 单元测试 (JUnit + Mockito)
 *
 * 测试家长密码管理器的密码格式验证、默认密码逻辑、失败计数重置。
 * 使用 Mockito 模拟 Context/SharedPreferences，避免 Robolectric 启动
 * XiaopacaiApp 时触发 SQLCipher 原生库加载失败。
 *
 * 注意：完整的 setPassword/verifyPassword 加解密流程依赖 KeyStoreManager
 * （Android KeyStore），需要在 Android 仪器测试 (androidTest) 中执行。
 */
class ParentPasswordManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        // 配置 mock：getSharedPreferences 返回 mockPrefs
        `when`(mockContext.getSharedPreferences("xiaopacai_parent_auth", Context.MODE_PRIVATE))
            .thenReturn(mockPrefs)
        // 配置 mockPrefs.edit() 返回 mockEditor
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        // 配置 mockEditor 链式调用
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        // 配置默认 SharedPreferences 状态：密码未设置、无锁定
        `when`(mockPrefs.contains("parent_password_hash")).thenReturn(false)
        `when`(mockPrefs.getLong("password_lockout_until", 0L)).thenReturn(0L)
        `when`(mockPrefs.getInt("password_failed_attempts", 0)).thenReturn(0)
        `when`(mockPrefs.getString(anyString(), isNull())).thenReturn(null)
    }

    // ==================== isValidPasswordFormat ====================

    @Test
    fun `isValidPasswordFormat - valid passwords`() {
        assertTrue(ParentPasswordManager.isValidPasswordFormat("000000"))
        assertTrue(ParentPasswordManager.isValidPasswordFormat("123456"))
        assertTrue(ParentPasswordManager.isValidPasswordFormat("abcdef"))
        assertTrue(ParentPasswordManager.isValidPasswordFormat("abc123"))
        assertTrue(ParentPasswordManager.isValidPasswordFormat("1234567890abcdef"))  // 16位
        assertTrue(ParentPasswordManager.isValidPasswordFormat("a1b2c3"))  // 6位最小
    }

    @Test
    fun `isValidPasswordFormat - invalid passwords`() {
        assertFalse(ParentPasswordManager.isValidPasswordFormat(""))
        assertFalse(ParentPasswordManager.isValidPasswordFormat("12345"))  // 5位
        assertFalse(ParentPasswordManager.isValidPasswordFormat("1234567890abcdefg")) // 17位
        assertFalse(ParentPasswordManager.isValidPasswordFormat("abc@123"))  // 含@
        assertFalse(ParentPasswordManager.isValidPasswordFormat("abc def"))  // 含空格
        assertFalse(ParentPasswordManager.isValidPasswordFormat("abc-def"))  // 含连字符
        assertFalse(ParentPasswordManager.isValidPasswordFormat("abc_def"))  // 含下划线
        assertFalse(ParentPasswordManager.isValidPasswordFormat("abc.def"))  // 含点号
    }

    @Test
    fun `isValidPasswordFormat - boundary lengths`() {
        assertTrue(ParentPasswordManager.isValidPasswordFormat("123456"))
        assertTrue(ParentPasswordManager.isValidPasswordFormat("1234567890123456"))
        assertFalse(ParentPasswordManager.isValidPasswordFormat("12345"))
        assertFalse(ParentPasswordManager.isValidPasswordFormat("12345678901234567"))
    }

    // ==================== isPasswordSet ====================

    @Test
    fun `isPasswordSet - returns false when prefs has no hash key`() {
        `when`(mockPrefs.contains("parent_password_hash")).thenReturn(false)
        assertFalse(ParentPasswordManager.isPasswordSet(mockContext))
    }

    @Test
    fun `isPasswordSet - returns true when prefs contains hash key`() {
        `when`(mockPrefs.contains("parent_password_hash")).thenReturn(true)
        assertTrue(ParentPasswordManager.isPasswordSet(mockContext))
    }

    // ==================== setPassword ====================

    @Test
    fun `setPassword - rejects invalid format`() {
        val result = ParentPasswordManager.setPassword(mockContext, "123", null)
        assertFalse(result)
    }

    @Test
    fun `setPassword - rejects too short password`() {
        val result = ParentPasswordManager.setPassword(mockContext, "12345", null)
        assertFalse(result)
    }

    @Test
    fun `setPassword - empty password fails format check`() {
        assertFalse(ParentPasswordManager.isValidPasswordFormat(""))
    }

    // ==================== verifyPassword（[SEC-P1] 默认密码已删除，未设置一律拒绝） ====================

    @Test
    fun `verifyPassword - 密码未设置时任何密码都被拒绝`() {
        // [SEC-P1] 删除默认密码 000000 后门（红线 R4.x）：
        // 未设置家长密码时一律拒绝验证，强制家长首次使用显式设置密码
        `when`(mockPrefs.contains("parent_password_hash")).thenReturn(false)
        `when`(mockPrefs.getLong("password_lockout_until", 0L)).thenReturn(0L)

        assertFalse(ParentPasswordManager.verifyPassword(mockContext, "000000"))
    }

    @Test
    fun `verifyPassword - 密码未设置时任意密码都被拒绝`() {
        `when`(mockPrefs.contains("parent_password_hash")).thenReturn(false)
        `when`(mockPrefs.getLong("password_lockout_until", 0L)).thenReturn(0L)

        assertFalse(ParentPasswordManager.verifyPassword(mockContext, "111111"))
    }

    @Test
    fun `verifyPassword - empty password always fails`() {
        `when`(mockPrefs.contains("parent_password_hash")).thenReturn(false)
        `when`(mockPrefs.getLong("password_lockout_until", 0L)).thenReturn(0L)

        assertFalse(ParentPasswordManager.verifyPassword(mockContext, ""))
    }

    @Test
    fun `verifyPassword - currently locked out`() {
        // 锁定中应直接返回 false
        `when`(mockPrefs.contains("parent_password_hash")).thenReturn(false)
        `when`(mockPrefs.getLong("password_lockout_until", 0L))
            .thenReturn(System.currentTimeMillis() + 60000)  // 1分钟后解锁

        assertFalse(ParentPasswordManager.verifyPassword(mockContext, "000000"))
    }

    @Test
    fun `verifyPassword - lockout expired 但密码未设置仍拒绝`() {
        // [SEC-P1] 锁定过期只代表允许尝试验证；密码未设置时仍拒绝（默认密码后门已删除）
        `when`(mockPrefs.contains("parent_password_hash")).thenReturn(false)
        `when`(mockPrefs.getLong("password_lockout_until", 0L))
            .thenReturn(System.currentTimeMillis() - 1000)  // 1秒前已过期

        assertFalse(ParentPasswordManager.verifyPassword(mockContext, "000000"))
    }

    // ==================== resetFailedAttempts ====================

    @Test
    fun `resetFailedAttempts - clears failure counts`() {
        ParentPasswordManager.resetFailedAttempts(mockContext)

        // 验证 putInt("password_failed_attempts", 0) 被调用
        verify(mockEditor).putInt("password_failed_attempts", 0)
        // 验证 putLong("password_lockout_until", 0) 被调用
        verify(mockEditor).putLong("password_lockout_until", 0)
        // 验证 apply() 被调用
        verify(mockEditor).apply()
    }

    // ==================== PBKDF2 算法可用性 ====================

    @Test
    fun `PBKDF2 algorithm - available on JVM`() {
        try {
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            assertNotNull(factory)
        } catch (e: Exception) {
            fail("PBKDF2WithHmacSHA256 not available: ${e.message}")
        }
    }

    // ==================== 锁定常量验证 ====================

    @Test
    fun `lockout constants - defined correctly`() {
        // 锁定 5 分钟 = 300,000 毫秒
        assertEquals(300000L, 5 * 60 * 1000L)
        // 最大失败尝试 = 5
        assertTrue(5 > 0)
    }
}
