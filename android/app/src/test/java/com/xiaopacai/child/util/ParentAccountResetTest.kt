package com.xiaopacai.child.util

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * [TASK-PRELAUNCH-PARENT-RESET] 换账号清理单元测试 (JUnit + Mockito)
 *
 * 验证「失败不可清除」安全门：
 * - 家长密码未设置 → 拒绝清除，且不触碰任何数据；
 * - 家长密码验证失败 → 拒绝清除，且不触碰任何数据；
 * - 家长密码清除函数仅移除哈希与盐值。
 *
 * 注意：完整成功路径依赖 KeyStoreManager（Android KeyStore）与 SQLCipher 原生库，
 * 需在 Android 仪器测试 (androidTest) 中执行（Codex 验收时覆盖）。
 */
class ParentAccountResetTest {

    private fun mockPrefs(name: String): Pair<SharedPreferences, SharedPreferences.Editor> {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.clear()).thenReturn(editor)
        `when`(editor.putInt(anyString(), anyInt())).thenReturn(editor)
        `when`(editor.putLong(anyString(), anyLong())).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        return prefs to editor
    }

    // ==================== 失败不可清除 ====================

    @Test
    fun resetAccount_passwordNotSet_failsWithoutTouchingData() {
        val context = mock(Context::class.java)
        val (authPrefs, _) = mockPrefs("xiaopacai_parent_auth")
        val (webPrefs, _) = mockPrefs("xiaopacai_web_prefs")
        val (guardianPrefs, guardianEditor) = mockPrefs("guardian_prefs")

        `when`(context.getSharedPreferences("xiaopacai_parent_auth", Context.MODE_PRIVATE)).thenReturn(authPrefs)
        `when`(context.getSharedPreferences("xiaopacai_web_prefs", Context.MODE_PRIVATE)).thenReturn(webPrefs)
        `when`(context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)).thenReturn(guardianPrefs)
        `when`(authPrefs.contains("parent_password_hash")).thenReturn(false)

        val result = ParentAccountReset.resetAccount(context, "whatever")

        assertTrue(result is ParentAccountReset.ResetResult.Failed)
        assertEquals("家长密码尚未设置，无法清除账号", (result as ParentAccountReset.ResetResult.Failed).reason)
        // 未触碰任何数据
        verify(webPrefs, never()).edit()
        verify(guardianEditor, never()).remove(anyString())
    }

    @Test
    fun resetAccount_passwordVerifyFails_failsWithoutTouchingData() {
        val context = mock(Context::class.java)
        val (authPrefs, authEditor) = mockPrefs("xiaopacai_parent_auth")
        val (webPrefs, _) = mockPrefs("xiaopacai_web_prefs")
        val (guardianPrefs, guardianEditor) = mockPrefs("guardian_prefs")

        `when`(context.getSharedPreferences("xiaopacai_parent_auth", Context.MODE_PRIVATE)).thenReturn(authPrefs)
        `when`(context.getSharedPreferences("xiaopacai_web_prefs", Context.MODE_PRIVATE)).thenReturn(webPrefs)
        `when`(context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)).thenReturn(guardianPrefs)
        // 密码已设置，但哈希读取失败 → 验证失败（不触碰 KeyStore 解密）
        `when`(authPrefs.contains("parent_password_hash")).thenReturn(true)
        `when`(authPrefs.getLong("password_lockout_until", 0L)).thenReturn(0L)
        `when`(authPrefs.getString(anyString(), org.mockito.ArgumentMatchers.isNull())).thenReturn(null)
        `when`(authPrefs.edit()).thenReturn(authEditor)

        val result = ParentAccountReset.resetAccount(context, "wrong-password")

        assertTrue(result is ParentAccountReset.ResetResult.Failed)
        assertEquals("家长密码验证失败，已取消清除", (result as ParentAccountReset.ResetResult.Failed).reason)
        // 未触碰任何数据
        verify(webPrefs, never()).edit()
        verify(guardianEditor, never()).remove(anyString())
    }

    // ==================== 家长密码清除 ====================

    @Test
    fun clearPassword_removesHashSaltAndCounters() {
        val context = mock(Context::class.java)
        val (authPrefs, authEditor) = mockPrefs("xiaopacai_parent_auth")
        `when`(context.getSharedPreferences("xiaopacai_parent_auth", Context.MODE_PRIVATE)).thenReturn(authPrefs)

        ParentPasswordManager.clearPassword(context)

        verify(authEditor).remove("parent_password_hash")
        verify(authEditor).remove("parent_password_salt")
        verify(authEditor).putInt("password_failed_attempts", 0)
        verify(authEditor).putLong("password_lockout_until", 0)
        verify(authEditor).apply()
    }
}
