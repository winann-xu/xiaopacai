package com.xiaopacai.child.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TASK-PRELAUNCH-FIX-SCAN] 握手确定性拒绝解析单元测试
 *
 * 覆盖：
 * - Web 服务端 handshake_rejected 帧（顶层 error / error_code 并入 payload）
 * - Windows 家长端 error 帧（payload.message 即错误码）
 * - 非拒绝帧（policy_update / heartbeat_ack 等）不误判
 * - 确定性错误码判定与用户文案映射
 */
class P2PHandshakeRejectionTest {

    @Test
    fun fromJson_handshakeRejected_topLevelFieldsMergedIntoPayload() {
        val msg = P2PMessage.fromJson(
            """{"type":"handshake_rejected","error":"设备已被其他账号绑定，请先解绑","error_code":"device_owned_by_other"}"""
        )
        assertEquals("handshake_rejected", msg.type)
        assertEquals("device_owned_by_other", msg.payload["error_code"])
        assertEquals("设备已被其他账号绑定，请先解绑", msg.payload["error"])
    }

    @Test
    fun parseHandshakeRejection_webFrame_returnsCodeAndReason() {
        val msg = P2PMessage.fromJson(
            """{"type":"handshake_rejected","error":"配对码无效或已过期，请刷新二维码","error_code":"invalid_pairing_code"}"""
        )
        val rejection = parseHandshakeRejection(msg)
        assertEquals("invalid_pairing_code", rejection?.code)
        assertEquals("配对码无效或已过期，请刷新二维码", rejection?.reason)
    }

    @Test
    fun parseHandshakeRejection_windowsErrorFrame_messageIsCode() {
        val msg = P2PMessage.fromJson("""{"type":"error","payload":{"message":"fingerprint_mismatch"}}""")
        val rejection = parseHandshakeRejection(msg)
        assertEquals("fingerprint_mismatch", rejection?.code)
    }

    @Test
    fun parseHandshakeRejection_windowsMissingDeviceId() {
        val msg = P2PMessage.fromJson("""{"type":"error","payload":{"message":"missing_device_id"}}""")
        val rejection = parseHandshakeRejection(msg)
        assertEquals("missing_device_id", rejection?.code)
    }

    @Test
    fun parseHandshakeRejection_nonRejectionFrames_returnNull() {
        assertNull(parseHandshakeRejection(
            P2PMessage.fromJson("""{"type":"policy_update","payload":{"dailyLimitMinutes":60}}""")))
        assertNull(parseHandshakeRejection(
            P2PMessage.fromJson("""{"type":"heartbeat_ack","payload":{"timestamp":1}}""")))
        assertNull(parseHandshakeRejection(
            P2PMessage.fromJson("""{"type":"announcement_push","payload":{"text":"hi"}}""")))
    }

    @Test
    fun isDeterministicRejectionCode_knownCodes() {
        listOf("unpaired", "revoked", "device_owned_by_other", "fingerprint_mismatch",
            "invalid_pairing_code", "missing_device_id").forEach {
            assertTrue(it, isDeterministicRejectionCode(it))
        }
    }

    @Test
    fun isDeterministicRejectionCode_unknownCodes_false() {
        assertFalse(isDeterministicRejectionCode(""))
        assertFalse(isDeterministicRejectionCode("rate_limited"))
        assertFalse(isDeterministicRejectionCode("unknown_code"))
    }

    @Test
    fun rejectionHintText_mapsKnownCodes() {
        assertEquals("设备尚未配对，请用家长端生成配对二维码后重新扫码", rejectionHintText("unpaired", null))
        assertEquals("设备已被家长端解绑，请重新扫码配对", rejectionHintText("revoked", null))
        assertEquals("设备已被其他账号绑定，请联系原账号解绑后重试",
            rejectionHintText("device_owned_by_other", "设备已被其他账号绑定，请先解绑"))
        assertEquals("证书指纹不匹配，请重新扫码配对", rejectionHintText("fingerprint_mismatch", null))
        assertEquals("配对码无效或已过期，请在家长端刷新二维码后重新扫码",
            rejectionHintText("invalid_pairing_code", null))
        assertEquals("设备信息缺失，请重新扫码配对", rejectionHintText("missing_device_id", null))
    }

    @Test
    fun rejectionHintText_unknownCode_fallsBackToServerReason() {
        assertEquals("服务器自定义原因", rejectionHintText("unknown_code", "服务器自定义原因"))
        assertEquals("连接被拒绝，请重新配对", rejectionHintText("unknown_code", null))
        assertEquals("连接被拒绝，请重新配对", rejectionHintText("", ""))
    }
}
