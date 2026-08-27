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
import org.mockito.Mockito.`when`

/**
 * [TASK-REBIND-GATE] 儿童端换绑前置检查器单元测试
 *
 * 覆盖：
 * - 服务端 bound=true → Bound（含/不含 ownerAccount）；
 * - bound=false → NotBound（允许换绑）；
 * - HTTP 非 2xx / 网络异常 / 响应解析失败 → Failed（拦截）；
 * - 未登录（无 JWT）→ Failed（禁止静默放行；V2.0.4 起未配置服务器地址
 *   会回退默认生产地址，不再有"未配置服务器"分支）。
 */
class BindingStatusCheckerTest {

    private val fakeClient = object : BindingStatusChecker.BindingStatusClient {
        var response: Triple<Int, String, String> = Triple(200, """{"found":true,"bound":false}""", "")
        var throwNetwork = false

        override fun getStatus(host: String, port: Int, deviceId: String, token: String): Triple<Int, String, String> {
            if (throwNetwork) throw java.io.IOException("no network")
            return response
        }
    }

    @Before
    fun setUp() {
        BindingStatusChecker.client = fakeClient
        fakeClient.response = Triple(200, """{"found":true,"bound":false}""", "")
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

    // ==================== checkWith：纯逻辑解析 ====================

    @Test
    fun checkWith_boundTrueWithOwner_returnsBoundOwner() {
        fakeClient.response = Triple(200, """{"found":true,"bound":true,"pairStatus":"paired","ownerAccount":"a@b.com"}""", "")

        val result = BindingStatusChecker.checkWith("1.2.3.4", 5000, "dev-1", "token")

        assertTrue(result is BindingStatusChecker.CheckResult.Bound)
        assertEquals("a@b.com", (result as BindingStatusChecker.CheckResult.Bound).ownerAccount)
    }

    @Test
    fun checkWith_boundTrueWithoutOwner_returnsBoundNull() {
        fakeClient.response = Triple(200, """{"found":true,"bound":true,"pairStatus":"paired"}""", "")

        val result = BindingStatusChecker.checkWith("1.2.3.4", 5000, "dev-1", "token")

        assertTrue(result is BindingStatusChecker.CheckResult.Bound)
        assertEquals(null, (result as BindingStatusChecker.CheckResult.Bound).ownerAccount)
    }

    @Test
    fun checkWith_boundFalse_returnsNotBound() {
        fakeClient.response = Triple(200, """{"found":true,"bound":false,"pairStatus":"unpaired"}""", "")

        val result = BindingStatusChecker.checkWith("1.2.3.4", 5000, "dev-1", "token")

        assertEquals(BindingStatusChecker.CheckResult.NotBound, result)
    }

    @Test
    fun checkWith_deviceMissing_returnsNotBound() {
        fakeClient.response = Triple(200, """{"found":false,"bound":false}""", "")

        val result = BindingStatusChecker.checkWith("1.2.3.4", 5000, "dev-1", "token")

        assertEquals(BindingStatusChecker.CheckResult.NotBound, result)
    }

    @Test
    fun checkWith_http401_returnsFailed() {
        fakeClient.response = Triple(401, "", """{"error":"unauthorized"}""")

        val result = BindingStatusChecker.checkWith("1.2.3.4", 5000, "dev-1", "token")

        assertTrue(result is BindingStatusChecker.CheckResult.Failed)
        assertTrue((result as BindingStatusChecker.CheckResult.Failed).reason.contains("401"))
    }

    @Test
    fun checkWith_networkException_returnsFailed() {
        fakeClient.throwNetwork = true

        val result = BindingStatusChecker.checkWith("1.2.3.4", 5000, "dev-1", "token")

        assertTrue(result is BindingStatusChecker.CheckResult.Failed)
    }

    @Test
    fun checkWith_badJson_returnsFailed() {
        fakeClient.response = Triple(200, "not-json", "")

        val result = BindingStatusChecker.checkWith("1.2.3.4", 5000, "dev-1", "token")

        assertTrue(result is BindingStatusChecker.CheckResult.Failed)
    }

    // ==================== check：上下文装配（服务器地址/登录态） ====================

    @Test
    fun check_notLoggedIn_returnsFailed() {
        val prefs = mockPrefs()
        `when`(prefs.getString("web_host", null)).thenReturn("1.2.3.4")
        val context = mockContext(prefs)

        val result = BindingStatusChecker.check(context, "dev-1")

        assertTrue(result is BindingStatusChecker.CheckResult.Failed)
        assertTrue((result as BindingStatusChecker.CheckResult.Failed).reason.contains("未登录"))
    }
}
