// [android-tv] 测试 — TamperAlertService Robolectric 测试
// [TASK-TV-01] 修正 Robolectric ServiceController API

package com.xiaopacai.tvos.service

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TamperAlertService Robolectric 单元测试
 *
 * 验证：
 * 1. Service 生命周期（onCreate, onStartCommand）
 * 2. 守护服务包名常量
 * 3. 常量定义
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TamperAlertServiceTest {

    @Test
    fun testServiceCreation() {
        val service = TamperAlertService()
        assertNotNull(service)
    }

    @Test
    fun testServiceStartCommandReturnsSticky() {
        val service = Robolectric.buildService(TamperAlertService::class.java).create().get()
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
    }

    @Test
    fun testGuardianPackagesDefined() {
        // Verify the companion object has the correct guardian packages
        val packages = TamperAlertService.GUARDIAN_PACKAGES
        assertEquals(2, packages.size)
        assertTrue(packages.contains("com.xiaopacai.tvos"))
        assertTrue(packages.contains("com.xiaopacai.tvos.debug"))
    }

    @Test
    fun testGuardianServicesDefined() {
        val services = TamperAlertService.GUARDIAN_SERVICES
        assertEquals(3, services.size)
        assertTrue(services.contains("com.xiaopacai.tvos.service.CloudSyncServiceTV"))
        assertTrue(services.contains("com.xiaopacai.tvos.service.AntiBypassServiceTV"))
        assertTrue(services.contains("com.xiaopacai.tvos.service.TVGuardianForegroundService"))
    }

    @Test
    fun testCheckIntervalConstant() {
        // CHECK_INTERVAL_MS should be 30000 (30 seconds)
        assertEquals(30_000L, TamperAlertService.CHECK_INTERVAL_MS)
    }

    @Test
    fun testNotificationIdConstant() {
        assertEquals(9999, TamperAlertService.NOTIFICATION_ID)
    }
}
