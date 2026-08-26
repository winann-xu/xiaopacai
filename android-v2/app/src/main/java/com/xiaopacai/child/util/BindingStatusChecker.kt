package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import java.net.URLEncoder

/**
 * [TASK-REBIND-GATE] 儿童端换绑前置检查：查询服务端当前设备绑定状态。
 *
 * 背景：旧版换绑仅检查本地残留（hasChildResidue），确认「清空并继续绑定」后直接重置
 * device_id 并以全新身份绑定到新家长，导致已绑定设备无需解绑即可换绑、旧绑定悬挂。
 * 本检查器在换绑清空前以「当前 device_id」询问服务端 GET /api/pairing/status：
 * - bound=true → 禁止换绑，必须先由原家长端解绑（解绑为硬删除，行消失后 bound=false）；
 * - bound=false → 允许走本地清空 + 重建身份的重绑流程；
 * - 查询失败/未登录 → 按「无法确认」拦截，避免绕过归属纪律。
 */
object BindingStatusChecker {

    private const val TAG = "BindingStatusChecker"

    /** 云端查询客户端（可注入，便于单元测试模拟网络/响应） */
    interface BindingStatusClient {
        /**
         * @return Triple(HTTP 状态码, 响应体, 错误体)
         * @throws Exception 网络不可用等连接异常
         */
        fun getStatus(host: String, port: Int, deviceId: String, token: String): Triple<Int, String, String>
    }

    /** 默认实现：HTTPS 优先 + 局域网回退（与登录/同步同通道） */
    private object HttpBindingStatusClient : BindingStatusClient {
        override fun getStatus(host: String, port: Int, deviceId: String, token: String): Triple<Int, String, String> {
            val encoded = URLEncoder.encode(deviceId, "UTF-8")
            return httpGetJson(host, port, "/api/pairing/status?deviceId=$encoded", token)
        }
    }

    /** 可注入客户端（测试替换；默认走真实 HTTP） */
    var client: BindingStatusClient = HttpBindingStatusClient

    sealed class CheckResult {
        /** 设备当前处于绑定状态（PairStatus=paired），需先解绑 */
        data class Bound(val ownerAccount: String?) : CheckResult()

        /** 设备不存在/已解绑，允许换绑 */
        object NotBound : CheckResult()

        /** 无法确认（未登录/网络异常/服务端异常），按拦截处理 */
        data class Failed(val reason: String) : CheckResult()
    }

    /**
     * 以本机已保存的账号服务器地址 + JWT 查询绑定状态。
     * 未配置服务器地址或未登录（无 JWT）时返回 Failed，禁止静默放行。
     */
    fun check(context: Context, deviceId: String): CheckResult {
        val host = CloudAccountManager.getServerHost(context)
        if (host.isNullOrBlank()) {
            return CheckResult.Failed("尚未配置家长端服务器地址")
        }
        val token = CloudAccountManager.getToken(context)
        if (token.isNullOrBlank()) {
            return CheckResult.Failed("未登录家长账号，无法确认设备绑定状态")
        }
        return checkWith(host, CloudAccountManager.getServerPort(context), deviceId, token)
    }

    /** 纯逻辑入口（便于单元测试）：解析 GET /api/pairing/status 响应 */
    fun checkWith(host: String, port: Int, deviceId: String, token: String): CheckResult {
        val (code, resp, err) = try {
            client.getStatus(host, port, deviceId, token)
        } catch (e: Exception) {
            Log.w(TAG, "绑定状态查询网络异常: ${e.message}")
            return CheckResult.Failed("网络异常，无法确认设备绑定状态")
        }

        return when {
            code in 200..299 -> {
                try {
                    val obj = org.json.JSONObject(resp)
                    if (obj.optBoolean("bound", false)) {
                        val owner = obj.optString("ownerAccount", "").takeIf { it.isNotBlank() }
                        CheckResult.Bound(owner)
                    } else {
                        CheckResult.NotBound
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "绑定状态响应解析失败: ${e.message}")
                    CheckResult.Failed("服务端响应异常，无法确认设备绑定状态")
                }
            }
            else -> {
                Log.w(TAG, "绑定状态查询失败: HTTP $code $err")
                CheckResult.Failed("查询绑定状态失败（HTTP $code），请稍后重试")
            }
        }
    }
}
