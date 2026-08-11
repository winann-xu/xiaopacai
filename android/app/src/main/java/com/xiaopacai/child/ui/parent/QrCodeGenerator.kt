package com.xiaopacai.child.ui.parent

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.util.EnumMap

/**
 * [TASK-OPT-12-P3] 二维码生成工具
 *
 * 基于 ZXing 生成配对二维码和扫码登录二维码。
 * 二维码内容为 JSON 格式，兼容儿童端扫码解析。
 */
object QrCodeGenerator {

    private const val QR_SIZE = 512  // 生成尺寸（px）

    /**
     * 生成家长端配对二维码
     *
     * @param deviceId 家长端设备 ID
     * @param port P2P 监听端口（默认 9527）
     * @param fingerprint 证书 SHA-256 指纹
     * @param pairingCode 6 位配对码
     * @param hostIps 本机局域网 IP 地址列表
     * @return 二维码 Bitmap，480x480 便于 UI 展示
     */
    fun generatePairingQrCode(
        deviceId: String,
        port: Int,
        fingerprint: String,
        pairingCode: String,
        hostIps: List<String>,
        version: String = "2.2",
        displaySize: Int = 480
    ): Bitmap {
        val json = JSONObject().apply {
            put("type", "pairing")
            put("version", version)
            put("deviceId", deviceId)
            put("port", port)
            put("fingerprint", fingerprint)
            put("pairingCode", pairingCode)
            put("ips", org.json.JSONArray(hostIps))
            put("timestamp", System.currentTimeMillis() / 1000)
        }

        return generate(json.toString(), displaySize)
    }

    /**
     * 生成 Web 中继连接二维码
     *
     * @param host Web 服务地址（IP 或域名）
     * @param port Web 服务端口
     * @param pairingCode 配对码
     * @param fingerprint 证书指纹
     * @return 二维码 Bitmap
     */
    fun generateWebRelayQrCode(
        host: String,
        port: Int,
        pairingCode: String,
        fingerprint: String,
        displaySize: Int = 480
    ): Bitmap {
        val json = JSONObject().apply {
            put("type", "web_relay")
            put("host", host)
            put("port", port)
            put("pairingCode", pairingCode)
            put("fingerprint", fingerprint)
            put("timestamp", System.currentTimeMillis() / 1000)
        }

        return generate(json.toString(), displaySize)
    }

    /**
     * 生成扫码登录 Web 授权二维码
     *
     * @param ticketUrl 登录 ticket URL（如 https://host/api/auth/login-ticket/xxx）
     * @param expiresAt 过期时间戳（秒）
     * @return 二维码 Bitmap
     */
    fun generateLoginTicketQrCode(
        ticketUrl: String,
        expiresAt: Long,
        displaySize: Int = 480
    ): Bitmap {
        val json = JSONObject().apply {
            put("type", "login_ticket")
            put("ticketUrl", ticketUrl)
            put("expiresAt", expiresAt)
            put("action", "scan_to_login")
        }

        return generate(json.toString(), displaySize)
    }

    /**
     * 生成重置密码授权二维码
     */
    fun generateResetTicketQrCode(
        ticketUrl: String,
        username: String,
        expiresAt: Long,
        displaySize: Int = 480
    ): Bitmap {
        val json = JSONObject().apply {
            put("type", "reset_ticket")
            put("ticketUrl", ticketUrl)
            put("username", username)
            put("expiresAt", expiresAt)
            put("action", "confirm_reset")
        }

        return generate(json.toString(), displaySize)
    }

    /**
     * 核心生成逻辑
     */
    private fun generate(content: String, displaySize: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)  // M 级纠错（15%）
            put(EncodeHintType.MARGIN, 2)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)

        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return Bitmap.createScaledBitmap(bitmap, displaySize, displaySize, true)
    }
}
