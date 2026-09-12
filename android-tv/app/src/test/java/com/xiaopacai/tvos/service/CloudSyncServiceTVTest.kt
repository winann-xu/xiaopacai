// [android-tv] 测试 — CloudSyncServiceTV Robolectric 测试
// [TASK-TV-01] 修正 Robolectric ServiceController API + 反射访问 private 常量

package com.xiaopacai.tvos.service

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CloudSyncServiceTV Robolectric 单元测试
 *
 * 验证：
 * 1. Service 生命周期
 * 2. 心跳接口路径（通过反射访问 private 常量）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSyncServiceTVTest {

    @Test
    fun testServiceCreation() {
        val service = CloudSyncServiceTV()
        assertNotNull(service)
    }

    @Test
    fun testServiceStartCommandReturnsSticky() {
        val service = Robolectric.buildService(CloudSyncServiceTV::class.java).create().get()
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
    }

    private fun getCompanionConstant(name: String): Any? {
        val companionClass = CloudSyncServiceTV::class.java.declaredFields
            .find { it.name == "Companion" }
            ?.type ?: return null
        return companionClass.getDeclaredField(name).get(null)
    }

    @Test
    fun testHeartbeatInterval() {
        val interval = getCompanionConstant("HEARTBEAT_INTERVAL_MS") as? Long
        assertEquals(60_000L, interval)
    }

    @Test
    fun testHeartbeatRetryDelay() {
        val delay = getCompanionConstant("HEARTBEAT_RETRY_DELAY_MS") as? Long
        assertEquals(5_000L, delay)
    }

    @Test
    fun testApiEndpoints() {
        assertEquals("/api/v3/heartbeat", getCompanionConstant("API_HEARTBEAT") as? String)
        assertEquals("/api/v3/bind", getCompanionConstant("API_BIND") as? String)
        assertEquals("/api/v3/unbind", getCompanionConstant("API_UNBIND") as? String)
        assertEquals("/api/v3/report", getCompanionConstant("API_REPORT") as? String)
    }
}
