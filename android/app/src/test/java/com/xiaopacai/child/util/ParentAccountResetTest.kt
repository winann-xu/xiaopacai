package com.xiaopacai.child.util

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * [TASK-ACCOUNT-V1] 换账号清理单元测试 (JUnit + Mockito)
 *
 * 验证「云端验证失败/离线不可清除」安全门：
 * - 云端验证失败（密码错误 401）→ 拒绝清除，且不触碰任何数据；
 * - 网络不可用（客户端抛异常）→ 拒绝清除（离线拒绝语义）；
 * - 账号清除仅移除 JWT 与账号邮箱，保留服务器地址配置。
 *
 * 注意：完整成功路径依赖 KeyStoreManager（Android KeyStore）与 SQLCipher 原生库，
 * 需在 Android 仪器测试 (androidTest) 中执行（Codex 验收时覆盖）。
 */
class ParentAccountResetTest {

    /** 伪造云端登录客户端（避免真实网络） */
    private val fakeClient = object : CloudAccountManager.CloudLoginClient {
        var failWithAuth = false
        var failWithNetwork = false
        override fun postLogin(host: String, port: Int, email: String, password: String): Triple<Int, String, String> {
            if (failWithNetwork) throw java.io.IOException("network unreachable")
            return if (failWithAuth) Triple(401, "", "{\"error\":\"invalid_credentials\"}")
            else Triple(200, "{\"accessToken\":\"fake-token\"}", "")
        }
    }

    @Before
    fun setUp() {
        // [TASK-ACCOUNT-V1] 注入可替换的登录客户端（单元测试不走真实网络）
        CloudAccountManager.loginClient = fakeClient
        fakeClient.failWithAuth = false
        fakeClient.failWithNetwork = false
    }

    private fun mockPrefs(): Pair<SharedPreferences, SharedPreferences.Editor> {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getString(anyString(), org.mockito.ArgumentMatchers.isNull())).thenReturn(null)
        `when`(prefs.getInt(anyString(), anyInt())).thenReturn(5000)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.clear()).thenReturn(editor)
        `when`(editor.putInt(anyString(), anyInt())).thenReturn(editor)
        `when`(editor.putLong(anyString(), anyLong())).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        return prefs to editor
    }

    private fun mockContext(webPrefs: SharedPreferences, guardianPrefs: SharedPreferences): Context {
        val context = mock(Context::class.java)
        `when`(context.getSharedPreferences("xiaopacai_web_prefs", Context.MODE_PRIVATE)).thenReturn(webPrefs)
        `when`(context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)).thenReturn(guardianPrefs)
        return context
    }

    // ==================== 云端验证失败 / 离线不可清除 ====================

    @Test
    fun resetAccount_cloudAuthFails_failsWithoutTouchingData() {
        val (webPrefs, _) = mockPrefs()
        val (guardianPrefs, guardianEditor) = mockPrefs()
        // 服务器地址已配置（否则先报「未配置服务器地址」）
        `when`(webPrefs.getString("web_host", null)).thenReturn("192.168.1.5")
        val context = mockContext(webPrefs, guardianPrefs)

        fakeClient.failWithAuth = true
        val result = ParentAccountReset.resetAccount(context, "parent@example.com", "wrong")

        assertTrue(result is ParentAccountReset.ResetResult.Failed)
        assertEquals("邮箱或密码错误，已取消清除", (result as ParentAccountReset.ResetResult.Failed).reason)
        // 未触碰任何数据
        verify(webPrefs, never()).edit()
        verify(guardianEditor, never()).remove(anyString())
    }

    @Test
    fun resetAccount_offline_failsWithoutTouchingData() {
        val (webPrefs, _) = mockPrefs()
        val (guardianPrefs, guardianEditor) = mockPrefs()
        `when`(webPrefs.getString("web_host", null)).thenReturn("192.168.1.5")
        val context = mockContext(webPrefs, guardianPrefs)

        fakeClient.failWithNetwork = true
        val result = ParentAccountReset.resetAccount(context, "parent@example.com", "pw")

        assertTrue(result is ParentAccountReset.ResetResult.Failed)
        assertEquals("网络不可用，家长身份验证需要联网，已取消清除",
            (result as ParentAccountReset.ResetResult.Failed).reason)
        verify(webPrefs, never()).edit()
        verify(guardianEditor, never()).remove(anyString())
    }

    @Test
    fun resetAccount_serverNotConfigured_failsWithoutTouchingData() {
        val (webPrefs, _) = mockPrefs()
        val (guardianPrefs, guardianEditor) = mockPrefs()
        val context = mockContext(webPrefs, guardianPrefs)

        val result = ParentAccountReset.resetAccount(context, "parent@example.com", "pw")

        assertTrue(result is ParentAccountReset.ResetResult.Failed)
        assertTrue((result as ParentAccountReset.ResetResult.Failed).reason.contains("服务器地址"))
        verify(webPrefs, never()).edit()
        verify(guardianEditor, never()).remove(anyString())
    }

    // ==================== 账号绑定清除（不含 KeyStore 路径） ====================

    @Test
    fun clearAccount_removesTokenAndEmailButKeepsServerBase() {
        val (webPrefs, webEditor) = mockPrefs()
        val context = mock(android.content.Context::class.java)
        `when`(context.getSharedPreferences("xiaopacai_web_prefs", Context.MODE_PRIVATE)).thenReturn(webPrefs)

        CloudAccountManager.clearAccount(context)

        verify(webEditor).remove("web_token")
        verify(webEditor).remove("account_email")
        // 服务器地址配置保留（换账号后无需重填）
        verify(webEditor, never()).remove("web_host")
        verify(webEditor, never()).remove("web_port")
        verify(webEditor).apply()
    }
}
