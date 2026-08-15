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
 * [TASK-MILESTONE-V3] 132 信需求 2：云端连接异常分类（供 UI 细分错误文案）
 * - NO_NETWORK：设备无网络/未知主机（DNS 解析失败等）
 * - CANNOT_CONNECT：连接超时/被拒绝（服务器不可达或地址有误）
 * - HTTPS_REQUIRED：SSL 握手失败（对端未启用 HTTPS），公网仅 HTTPS 红线
 */
internal class CloudConnectionException(
    val kind: Kind,
    cause: Throwable? = null
) : Exception(cause) {
    enum class Kind { NO_NETWORK, CANNOT_CONNECT, HTTPS_REQUIRED }
}

/**
 * [SEC-P1] HTTPS 优先执行 HTTP 请求：
 * - 先尝试 https；
 * - 仅当主机是局域网地址且失败原因为 SSL 握手失败（对端为明文 HTTP 服务）时，
 *   回退到 http 重试一次（其他异常不回退，避免 POST 重复提交）；
 * - 公网主机仅 https（红线 R6.x，无例外开关）。
 * [TASK-MILESTONE-V3] 已移除 allowHttpOverride 测试期开关（HTTPS 已上线）。
 */
internal fun <T> httpWithHttpsFirst(host: String, port: Int, block: (base: String) -> T): T {
    val candidates = if (isLanHost(host))
        listOf("https://$host:$port", "http://$host:$port")
    else
        listOf("https://$host:$port")
    var sslFailed = false
    var lastSslError: Exception? = null
    for (base in candidates) {
        try {
            return block(base)
        } catch (e: javax.net.ssl.SSLException) {
            sslFailed = true
            lastSslError = e
            android.util.Log.w("CloudHttp", "HTTPS 请求失败(SSL)，尝试下一候选: ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            // DNS 解析失败：域名错误或无网络
            throw CloudConnectionException(CloudConnectionException.Kind.NO_NETWORK, e)
        } catch (e: java.net.SocketTimeoutException) {
            throw CloudConnectionException(CloudConnectionException.Kind.CANNOT_CONNECT, e)
        } catch (e: java.net.ConnectException) {
            throw CloudConnectionException(CloudConnectionException.Kind.CANNOT_CONNECT, e)
        } catch (e: java.io.IOException) {
            // 其余网络层异常（无路由、网络不可用等）
            throw CloudConnectionException(CloudConnectionException.Kind.NO_NETWORK, e)
        }
    }
    // 所有候选均失败且最后为 SSL 握手失败：对端未启用 HTTPS（或地址端口有误）
    throw CloudConnectionException(
        if (sslFailed) CloudConnectionException.Kind.HTTPS_REQUIRED
        else CloudConnectionException.Kind.CANNOT_CONNECT,
        lastSslError
    )
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
 * [TASK-MILESTONE-V3] 需求 4：解绑/换绑服务端清理用 DELETE。
 * 可携带额外请求头（X-Action-Token：POST /api/auth/verify-password 签发的一次性操作令牌）。
 * @return Triple(状态码, 响应体, 错误体)
 */
internal fun httpDeleteJson(
    host: String, port: Int, path: String, token: String?,
    extraHeaders: Map<String, String> = emptyMap()
): Triple<Int, String, String> {
    return httpWithHttpsFirst(host, port) { base ->
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val code = conn.responseCode
        val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
        val err = if (code in 200..299) ""
            else try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        Triple(code, resp, err)
    }
}

/**
 * [TASK-MILESTONE-V3] 需求 10：策略保存用 PUT（带 expectedVersion 乐观并发）。
 * [SEC-P1] HTTPS 优先 + 局域网回退。
 * @return Triple(状态码, 响应体, 错误体)；非 2xx 时错误体承载服务端返回（如 409 的最新策略）
 */
internal fun httpPutJson(
    host: String, port: Int, path: String, body: String, token: String?
): Triple<Int, String, String> {
    return httpWithHttpsFirst(host, port) { base ->
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
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
