package com.xiaopacai.child.adbshell

import com.xiaopacai.child.adbshell.AdbPairingDiscovery.DiscoveredService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TASK-STRICT-PROVISION-V1] 无线调试 mDNS 服务解析测试（ADR 0018 v1.3.1）
 *
 * 覆盖 v1.3.1 服务名修正：Android 11+ 使用 `_adb-tls-connect._tcp`，
 * Android 10 及以下回退 `_adb._tcp`；配对服务缺失/连接服务缺失/无地址均返回 null。
 */
class AdbPairingDiscoveryTest {

    private val pairing = DiscoveredService(
        type = AdbPairingDiscovery.PAIRING_SERVICE,
        port = 37000,
        host = "192.168.50.5"
    )

    private val tlsConnect = DiscoveredService(
        type = AdbPairingDiscovery.CONNECT_SERVICE_TLS,
        port = 42931,
        host = "192.168.50.5"
    )

    private val legacyConnect = DiscoveredService(
        type = AdbPairingDiscovery.CONNECT_SERVICE_LEGACY,
        port = 5555,
        host = "192.168.50.5"
    )

    @Test
    fun resolvesModernTlsConnectService() {
        val result = AdbPairingDiscovery.resolve(listOf(pairing, tlsConnect))
        assertNotNull(result)
        assertEquals("192.168.50.5", result?.host)
        assertEquals(37000, result?.pairingPort)
        assertEquals(42931, result?.adbPort)
    }

    @Test
    fun fallsBackToLegacyConnectService() {
        val result = AdbPairingDiscovery.resolve(listOf(pairing, legacyConnect))
        assertNotNull(result)
        assertEquals("192.168.50.5", result?.host)
        assertEquals(37000, result?.pairingPort)
        assertEquals(5555, result?.adbPort)
    }

    @Test
    fun prefersTlsConnectOverLegacy() {
        val result = AdbPairingDiscovery.resolve(listOf(pairing, legacyConnect, tlsConnect))
        assertNotNull(result)
        assertEquals(42931, result?.adbPort)
    }

    @Test
    fun nullWhenPairingServiceMissing() {
        assertNull(AdbPairingDiscovery.resolve(listOf(tlsConnect)))
    }

    @Test
    fun nullWhenConnectServiceMissing() {
        assertNull(AdbPairingDiscovery.resolve(listOf(pairing)))
    }

    @Test
    fun nullWhenNoHostAddress() {
        val noHostPairing = pairing.copy(host = null)
        val noHostConnect = tlsConnect.copy(host = null)
        assertNull(AdbPairingDiscovery.resolve(listOf(noHostPairing, noHostConnect)))
    }

    @Test
    fun ignoresUnrelatedServices() {
        val unrelated = DiscoveredService(type = "_http._tcp.local.", port = 80, host = "192.168.50.1")
        val result = AdbPairingDiscovery.resolve(listOf(unrelated, pairing, tlsConnect))
        assertNotNull(result)
        assertEquals(37000, result?.pairingPort)
    }
}
