package com.xiaopacai.child.util

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * [TASK-ACCOUNT-V1] 云端 HTTP 公共层
 *
 * 从 ParentSettingsScreen 抽出，供 CloudAccountManager（云端验证）与家长端各接口共用：
 * - HTTPS 优先；仅局域网地址且 SSL 握手失败时回退 HTTP（红线 R6.x）；
 * - 公网主机仅 HTTPS，杜绝明文传输凭据。
 */

/**
 * [SEC-P1] 判断主机是否为局域网/本机地址。
 * HTTP 明文仅限局域网；公网主机一律强制 HTTPS。
 */
internal fun isLanHost(host: String): Boolean {
    if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
    val parts = host.split(".")
    if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
    val a = parts[0].toInt()
    val b = parts[1].toInt()
    return a == 127 || a == 10 ||
        (a == 192 && b == 168) ||
        (a == 172 && b in 16..31) ||
        (a == 169 && b == 254)
}

/**
 * [SEC-P1] HTTPS 优先执行 HTTP 请求：
 * - 先尝试 https；
 * - 仅当主机是局域网地址且失败原因为 SSL 握手失败（对端为明文 HTTP 服务）时，
 *   回退到 http 重试一次（其他异常不回退，避免 POST 重复提交）；
 * - 公网主机仅 https；测试期家长端显式开启 allowHttpOverride 后允许回退 http
 *   （服务器尚未启用 HTTPS 时的过渡开关，生产应配置 HTTPS 后关闭）。
 */
/** [TASK-ACCOUNT-V1-HOTFIX] 测试期允许公网 HTTP（由家长端登录页开关写入，进程内生效） */
internal var allowHttpOverride: Boolean = false

internal fun <T> httpWithHttpsFirst(host: String, port: Int, block: (base: String) -> T): T {
    val candidates = if (isLanHost(host) || allowHttpOverride)
        listOf("https://$host:$port", "http://$host:$port")
    else
        listOf("https://$host:$port")
    var sslFailed = false
    for (base in candidates) {
        try {
            return block(base)
        } catch (e: javax.net.ssl.SSLException) {
            sslFailed = true
            android.util.Log.w("CloudHttp", "HTTPS 请求失败(SSL)，尝试下一候选: ${e.message}")
        }
    }
    throw IllegalStateException("HTTPS 连接失败" + if (sslFailed) "（服务端未启用 HTTPS）" else "")
}

/**
 * [SEC-P1] HTTPS 优先 + 局域网回退的 JSON POST。
 * @return Triple(状态码, 响应体, 错误体)
 */
internal fun httpPostJson(
    host: String, port: Int, path: String, body: String, token: String?
): Triple<Int, String, String> {
    return httpWithHttpsFirst(host, port) { base ->
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val code = conn.responseCode
        val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
        val err = if (code in 200..299) ""
            else try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        Triple(code, resp, err)
    }
}

/**
 * [SEC-P1] HTTPS 优先 + 局域网回退的 JSON GET。
 * @return Triple(状态码, 响应体, 错误体)
 */
internal fun httpGetJson(
    host: String, port: Int, path: String, token: String?
): Triple<Int, String, String> {
    return httpWithHttpsFirst(host, port) { base ->
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val code = conn.responseCode
        val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
        val err = if (code in 200..299) ""
            else try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        Triple(code, resp, err)
    }
}
