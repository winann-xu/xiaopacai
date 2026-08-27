package com.xiaopacai.child.util

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * [TASK-ACCOUNT-V1] 云端账号管理器单元测试 (JUnit + Mockito)
 *
 * 覆盖不依赖 Android KeyStore 的路径：
 * - 服务器地址/账号邮箱的持久化与读取；
 * - 登录失败路径（未配置服务器 / 401 凭据错误 / 网络异常离线拒绝）；
 * - 成功路径涉及 KeyStore 加密与真实网络，需 androidTest 覆盖（Codex 验收时执行）。
 */
class CloudAccountManagerTest {

    private val fakeClient = object : CloudAccountManager.CloudLoginClient {
        var response: Triple<Int, String, String> = Triple(200, "{\"accessToken\":\"t\"}", "")
        var throwNetwork = false
        override fun postLogin(host: String, port: Int, email: String, password: String): Triple<Int, String, String> {
            if (throwNetwork) throw java.io.IOException("no network")
            return response
        }
    }

    @Before
    fun setUp() {
        CloudAccountManager.loginClient = fakeClient
        fakeClient.response = Triple(200, "{\"accessToken\":\"t\"}", "")
        fakeClient.throwNetwork = false
    }

    private fun mockPrefs(): SharedPreferences {
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
        return prefs
    }

    private fun mockContext(webPrefs: SharedPreferences): Context {
        val context = mock(Context::class.java)
        `when`(context.getSharedPreferences("xiaopacai_web_prefs", Context.MODE_PRIVATE)).thenReturn(webPrefs)
        return context
    }

    // ==================== 服务器地址 ====================

    @Test
    fun saveServerBase_roundtrip() {
        val prefs = mockPrefs()
        val context = mockContext(prefs)
        `when`(prefs.getString("web_host", null)).thenReturn("192.168.50.11")
        `when`(prefs.getInt("web_port", 5000)).thenReturn(8443)

        CloudAccountManager.saveServerBase(context, "192.168.50.11", 8443)

        verify(prefs.edit()).putString("web_host", "192.168.50.11")
        verify(prefs.edit()).putInt("web_port", 8443)
        assertEquals("192.168.50.11", CloudAccountManager.getServerHost(context))
        assertEquals(8443, CloudAccountManager.getServerPort(context))
    }

    @Test
    fun getServerPort_defaultsTo5000() {
        val prefs = mockPrefs()
        val context = mockContext(prefs)
        assertEquals(5000, CloudAccountManager.getServerPort(context))
    }

    // ==================== 账号邮箱 ====================

    @Test
    fun boundEmail_roundtrip() {
        val prefs = mockPrefs()
        val context = mockContext(prefs)
        assertNull(CloudAccountManager.getBoundEmail(context))
        assertFalse(CloudAccountManager.isBound(context))
        // 直接注入偏好值模拟已绑定
        `when`(prefs.getString("account_email", null)).thenReturn("parent@example.com")
        assertEquals("parent@example.com", CloudAccountManager.getBoundEmail(context))
        assertTrue(CloudAccountManager.isBound(context))
    }

    // ==================== 登录失败路径 ====================

    @Test
    fun login_serverNotConfigured_fails() {
        val context = mockContext(mockPrefs())
        val result = CloudAccountManager.login(context, "a@b.com", "pw")
        assertTrue(result is CloudAccountManager.LoginResult.Failed)
        assertTrue((result as CloudAccountManager.LoginResult.Failed).reason.contains("服务器地址"))
    }

    @Test
    fun login_401_returnsCredentialError() {
        val prefs = mockPrefs()
        `when`(prefs.getString("web_host", null)).thenReturn("192.168.1.5")
        val context = mockContext(prefs)
        fakeClient.response = Triple(401, "", "{\"error\":\"invalid_credentials\"}")

        val result = CloudAccountManager.login(context, "a@b.com", "bad")

        assertTrue(result is CloudAccountManager.LoginResult.Failed)
        assertEquals("邮箱或密码错误", (result as CloudAccountManager.LoginResult.Failed).reason)
    }

    @Test
    fun login_networkError_returnsOfflineMessage() {
        val prefs = mockPrefs()
        `when`(prefs.getString("web_host", null)).thenReturn("192.168.1.5")
        val context = mockContext(prefs)
        fakeClient.throwNetwork = true

        val result = CloudAccountManager.login(context, "a@b.com", "pw")

        assertTrue(result is CloudAccountManager.LoginResult.Failed)
        assertEquals("网络不可用，家长身份验证需要联网",
            (result as CloudAccountManager.LoginResult.Failed).reason)
    }

    // ==================== [TASK-MILESTONE-V3] 132 信：登录失败文案细分 ====================

    /** 模拟设备有可用网络（activeNetwork + capabilities 均非空） */
    private fun mockContextWithNetwork(webPrefs: SharedPreferences): Context {
        val context = mockContext(webPrefs)
        val cm = mock(android.net.ConnectivityManager::class.java)
        `when`(cm.activeNetwork).thenReturn(mock(android.net.Network::class.java))
        `when`(cm.getNetworkCapabilities(org.mockito.ArgumentMatchers.any()))
            .thenReturn(mock(android.net.NetworkCapabilities::class.java))
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)
        return context
    }

    @Test
    fun loginNetworkError_httpsRequired_copy() {
        val context = mockContextWithNetwork(mockPrefs())
        val e = CloudConnectionException(CloudConnectionException.Kind.HTTPS_REQUIRED)
        assertEquals("服务器未启用 HTTPS 或地址有误",
            CloudAccountManager.loginNetworkErrorMessage(context, e))
    }

    @Test
    fun loginNetworkError_cannotConnect_copy() {
        val context = mockContextWithNetwork(mockPrefs())
        val e = CloudConnectionException(CloudConnectionException.Kind.CANNOT_CONNECT)
        assertEquals("无法连接服务器，请检查 Web 服务地址与网络",
            CloudAccountManager.loginNetworkErrorMessage(context, e))
    }

    @Test
    fun loginNetworkError_noNetwork_copy() {
        // mock 无 ConnectivityManager（activeNetwork=null）→ 纯网络不可用文案
        val context = mockContext(mockPrefs())
        val e = java.io.IOException("no network")
        assertEquals("网络不可用，家长身份验证需要联网",
            CloudAccountManager.loginNetworkErrorMessage(context, e))
    }

    @Test
    fun loginNetworkError_genericException_fallsBackToCannotConnect() {
        val context = mockContextWithNetwork(mockPrefs())
        val e = RuntimeException("unexpected")
        assertEquals("无法连接服务器，请检查 Web 服务地址与网络",
            CloudAccountManager.loginNetworkErrorMessage(context, e))
    }
}
