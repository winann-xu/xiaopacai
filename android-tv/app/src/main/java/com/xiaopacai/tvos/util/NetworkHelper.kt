// [android-tv] 工具类 — 网络辅助
// 小趴菜 TVOS 版 — OkHttp 封装

package com.xiaopacai.tvos.util

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * 网络请求工具类
 * 对接 CloudSyncService 心跳协议
 */
object NetworkHelper {

    private const val TAG = "NetworkHelperTV"

    // 全局 OkHttp 客户端（复用连接池）
    // 注意：Android 5.1 系统信任库缺 ISRG 根证书（实测 162 个根中无 X1/X2），
    // 必须用 TlsTrustHelper 合并内置根，否则所有 HTTPS 请求都会报
    // "Trust anchor for certification path not found"
    private val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        val trustManager = TlsTrustHelper.mergedTrustManager()
        if (trustManager != null) {
            try {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            } catch (e: Exception) {
                Log.w(TAG, "配置 TLS 信任失败，回退系统默认", e)
            }
        }
        return builder.build()
    }

    private const val DEFAULT_CONTENT_TYPE = "application/json; charset=utf-8"

    /**
     * 构建云端服务器基础 URL
     */
    fun buildBaseUrl(host: String, port: Int, useHttps: Boolean = true): String {
        val scheme = if (useHttps || port == 443) "https" else "http"
        return "$scheme://$host:$port"
    }

    /**
     * GET 请求
     */
    fun get(url: String, callback: (ResponseResult) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", DEFAULT_CONTENT_TYPE)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(ResponseResult.Failure(e.message ?: "网络连接失败"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val body = it.body?.string() ?: ""
                        callback(ResponseResult.Success(body))
                    } else {
                        callback(ResponseResult.Failure("HTTP ${it.code}"))
                    }
                }
            }
        })
    }

    /**
     * POST JSON 请求
     */
    fun postJson(url: String, json: String, callback: (ResponseResult) -> Unit) {
        val mediaType = "application/json".toMediaType()
        val body = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", DEFAULT_CONTENT_TYPE)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(ResponseResult.Failure(e.message ?: "网络连接失败"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val body = it.body?.string() ?: ""
                        callback(ResponseResult.Success(body))
                    } else {
                        callback(ResponseResult.Failure("HTTP ${it.code}"))
                    }
                }
            }
        })
    }

    /**
     * POST JSON 请求（返回原始状态码与响应体）
     * 用于需要区分业务错误（401/429）与网络故障的调用方，如登录
     */
    fun postJsonDetailed(url: String, json: String, callback: (code: Int, body: String) -> Unit) {
        val mediaType = "application/json".toMediaType()
        val requestBody = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", DEFAULT_CONTENT_TYPE)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "POST 失败: ${call.request().url} -> ${e.javaClass.simpleName}: ${e.message}", e)
                callback(-1, e.message ?: "网络连接失败")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    callback(it.code, it.body?.string() ?: "")
                }
            }
        })
    }

    /**
     * 通用请求（支持任意方法 + Bearer 令牌）
     * 用于家长侧接口（公告 CRUD 等）
     */
    fun request(
        method: String,
        url: String,
        json: String? = null,
        bearerToken: String? = null,
        extraHeaders: Map<String, String>? = null,
        callback: (code: Int, body: String) -> Unit
    ) {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", DEFAULT_CONTENT_TYPE)
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        // 额外请求头（如解绑所需的 X-Action-Token）
        extraHeaders?.forEach { (k, v) -> builder.header(k, v) }
        val requestBody = json?.toRequestBody("application/json".toMediaType())
        builder.method(method, requestBody)

        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "$method 失败: $url -> ${e.javaClass.simpleName}: ${e.message}")
                callback(-1, e.message ?: "网络连接失败")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    callback(it.code, it.body?.string() ?: "")
                }
            }
        })
    }

    /**
     * 检查网络可用性
     */
    fun isNetworkAvailable(): Boolean {
        // TODO: 实际应使用 ConnectivityManager
        return true
    }

    /**
     * HTTP 响应结果
     */
    sealed class ResponseResult {
        data class Success(val data: String) : ResponseResult()
        data class Failure(val error: String) : ResponseResult()
    }
}
