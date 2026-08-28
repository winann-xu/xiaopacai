package com.xiaopacai.child.service

import android.content.Context
import android.content.SharedPreferences
import com.xiaopacai.child.util.CloudAccountManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * [TASK-V2.0.6-UNBIND-SYNC] CloudSyncService 解绑同步单元测试（JUnit + Mockito）
 *
 * 覆盖：Web 端解绑（服务端硬删除设备行 → 心跳 404）后，APP 端清除本地
 * 账号绑定与设备注册，驱动首页卡片回到「未绑定」并可重新绑定。
 *
 * 说明：不用 Robolectric——XiaopacaiApp.onCreate 会初始化 SQLCipher 原生库，
 * JVM 单测环境无 sqlcipher.so（UnsatisfiedLinkError）。此处只验证偏好清理逻辑。
 */
class CloudSyncServiceTest {

    private lateinit var webPrefs: SharedPreferences
    private lateinit var syncPrefs: SharedPreferences
    private lateinit var guardianPrefs: SharedPreferences
    private lateinit var context: Context

    private fun mockPrefs(): SharedPreferences {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getString(anyString(), org.mockito.ArgumentMatchers.isNull())).thenReturn(null)
        `when`(prefs.getBoolean(anyString(), anyBoolean())).thenReturn(false)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.putInt(anyString(), anyInt())).thenReturn(editor)
        `when`(editor.putLong(anyString(), anyLong())).thenReturn(editor)
        return prefs
    }

    @Before
    fun setUp() {
        webPrefs = mockPrefs()
        syncPrefs = mockPrefs()
        guardianPrefs = mockPrefs()
        context = mock(Context::class.java)
        `when`(context.getSharedPreferences(CloudAccountManager.PREFS_WEB, Context.MODE_PRIVATE))
            .thenReturn(webPrefs)
        `when`(context.getSharedPreferences("cloud_sync_prefs", Context.MODE_PRIVATE))
            .thenReturn(syncPrefs)
        `when`(context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE))
            .thenReturn(guardianPrefs)
    }

    @Test
    fun handleDeviceUnbound_clearsAccountAndDeviceRegistration() {
        // 模拟 Web 解绑前的本地状态：账号已绑定 + 设备已注册（令牌/绑定码残留）
        `when`(webPrefs.getString("account_email", null)).thenReturn("parent@example.com")
        `when`(syncPrefs.getString("bind_code", null)).thenReturn("123456")
        `when`(syncPrefs.getString("device_token_encrypted", null)).thenReturn("encrypted-token")
        `when`(syncPrefs.getBoolean("registered", false)).thenReturn(true)

        CloudSyncService.handleDeviceUnbound(context)

        // 账号绑定被清除（clearAccount 移除邮箱/令牌/角色）→ UI 显示未绑定
        verify(webPrefs.edit()).remove("account_email")
        verify(webPrefs.edit()).remove("web_token")
        verify(webPrefs.edit()).remove("account_role")
        // 设备注册/令牌/绑定码被清除 → 重新绑定前会自动重新注册
        verify(syncPrefs.edit()).remove("bind_code")
        verify(syncPrefs.edit()).remove("device_token_encrypted")
        verify(syncPrefs.edit()).putBoolean("registered", false)
        // [TASK-V208-UNBIND-FIX] 解绑后置位“等待重绑”，禁止后台匿名重注册（防策略重新下发）
        verify(syncPrefs.edit()).putBoolean("wait_rebind", true)
        // 云端连接状态回到未连接
        assertEquals(CloudSyncService.CloudSyncState.DISCONNECTED, CloudSyncService.connectionState.value)
    }

    @Test
    fun handleDeviceUnbound_isIdempotent() {
        CloudSyncService.handleDeviceUnbound(context)
        CloudSyncService.handleDeviceUnbound(context)
        assertEquals(CloudSyncService.CloudSyncState.DISCONNECTED, CloudSyncService.connectionState.value)
    }

    @Test
    fun shouldWaitRebind_reflectsFlag() {
        assertFalse(CloudSyncService.shouldWaitRebind(context))
        `when`(syncPrefs.getBoolean("wait_rebind", false)).thenReturn(true)
        assertTrue(CloudSyncService.shouldWaitRebind(context))
    }

    @Test
    fun mapAnnouncementPriority_mapsThreeLevelsToInt() {
        assertEquals(2, CloudSyncService.mapAnnouncementPriority("urgent"))
        assertEquals(1, CloudSyncService.mapAnnouncementPriority("important"))
        assertEquals(0, CloudSyncService.mapAnnouncementPriority("normal"))
        assertEquals(0, CloudSyncService.mapAnnouncementPriority(""))
        assertEquals(0, CloudSyncService.mapAnnouncementPriority("unknown"))
    }
}
