// [android-tv] 工具类 — 解绑本机（家长主动把电视从账号上摘下来）
// 小趴菜 TVOS 版
//
// 对标手机版 android-v3 util/ParentAccountReset.tryUnbindServerDevice()，请求契约完全一致：
//   1. POST /api/auth/login            {Username,Password} → {accessToken}  （家长 JWT）
//   2. GET  /api/devices                                  → {devices:[{id,deviceId}]} 定位本机
//   3. POST /api/auth/verify-password  {password}         → {actionToken}   5 分钟单次有效
//   4. DELETE /api/devices/{id}  带 X-Action-Token        → 服务端硬删设备+策略+使用记录
//   5. 本地清绑定（含重置本机身份，见服务端"重绑走全新设备身份"需求）
//
// 约定：密码只在本函数内存里过一遍，绝不写盘（用户明确要求）。

package com.xiaopacai.tvos.util

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

object DeviceUnbindTV {

    private const val TAG = "DeviceUnbindTV"

    sealed class Result {
        /** 服务端已删除本机设备，管控彻底解除 */
        object Success : Result()
        /** 服务端本来就没有这台设备（未绑定/已被删）—— 本地已清，等效成功 */
        data class NotBound(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * 一次家长密码完成解绑：登录 → 定位本机 → 二次验证 → 删除 → 清本地。
     */
    suspend fun unbind(context: Context, account: String, password: String): Result {
        if (account.isBlank()) return Result.Failed("请先输入家长账号")
        if (password.isBlank()) return Result.Failed("请输入家长密码")

        // 1) 登录拿家长 JWT
        val loginBody = JSONObject().apply {
            put("Username", account.trim())
            put("Password", password)
        }.toString()
        val (loginCode, loginResp) = request(context, "POST", "/api/auth/login", loginBody, null)
        if (loginCode !in 200..299) {
            AppLogTV.w(TAG, "解绑前登录失败 HTTP $loginCode")
            return when (loginCode) {
                401 -> Result.Failed("账号或密码错误")
                429 -> Result.Failed("失败次数过多，请 1 小时后重试")
                else -> Result.Failed("登录失败（HTTP $loginCode）")
            }
        }
        val jwt = runCatching { JSONObject(loginResp).optString("accessToken", "") }.getOrDefault("")
        if (jwt.isBlank()) return Result.Failed("登录未返回令牌")

        // 2) 在家长设备列表里定位本机（deviceId 匹配）
        val deviceId = SharedPreferenceHelperTV.getDeviceId(context).first()
        val (listCode, listResp) = request(context, "GET", "/api/devices", null, jwt)
        if (listCode !in 200..299) {
            AppLogTV.w(TAG, "获取设备列表失败 HTTP $listCode")
            return Result.Failed("获取设备列表失败（HTTP $listCode）")
        }
        val serverId = runCatching {
            val arr = JSONObject(listResp).optJSONArray("devices")
            if (arr == null) -1L else {
                var found = -1L
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    if (d.optString("deviceId", "") == deviceId) {
                        found = d.optLong("id", -1L)
                        break
                    }
                }
                found
            }
        }.getOrDefault(-1L)

        if (serverId <= 0) {
            clearLocalBinding(context)
            AppLogTV.i(TAG, "服务端无本机设备记录，仅清本地 deviceId=$deviceId")
            return Result.NotBound("服务端没有这台设备的绑定记录（本地已清理）")
        }

        // 3) 密码二次验证 → 一次性操作令牌
        val verifyBody = JSONObject().apply { put("password", password) }.toString()
        val (vCode, vResp) = request(context, "POST", "/api/auth/verify-password", verifyBody, jwt)
        if (vCode !in 200..299) {
            AppLogTV.w(TAG, "二次验证失败 HTTP $vCode")
            return Result.Failed("密码二次验证失败（HTTP $vCode）")
        }
        val actionToken = runCatching { JSONObject(vResp).optString("actionToken", "") }.getOrDefault("")
        if (actionToken.isBlank()) return Result.Failed("服务端未返回操作令牌")

        // 4) 解绑（服务端硬删除设备行 + 策略 + 公告送达 + 使用记录）
        val (dCode, dErr) = request(
            context, "DELETE", "/api/devices/$serverId", null, jwt,
            mapOf("X-Action-Token" to actionToken)
        )
        if (dCode !in 200..299) {
            AppLogTV.w(TAG, "服务端解绑失败 HTTP $dCode ${dErr.take(80)}")
            return Result.Failed("解绑失败（HTTP $dCode）")
        }

        // 5) 本地清绑定 + 重置设备身份（服务端要求重绑走全新设备身份）
        clearLocalBinding(context)
        AppLogTV.i(TAG, "本机已解绑 deviceId=$deviceId serverId=$serverId，管控已解除")
        return Result.Success
    }

    /** 清本地绑定痕迹（设备令牌+绑定位；并按服务端要求重置设备身份） */
    private suspend fun clearLocalBinding(context: Context) {
        SharedPreferenceHelperTV.saveDeviceBound(context, false)
        runCatching { SharedPreferenceHelperTV.saveDeviceToken(context, "") }
        runCatching { SharedPreferenceHelperTV.generateDeviceId(context) }
    }

    private suspend fun request(
        context: Context,
        method: String,
        path: String,
        json: String?,
        token: String?,
        extraHeaders: Map<String, String>? = null
    ): Pair<Int, String> {
        val host = SharedPreferenceHelperTV.getCloudHost(context).first()
        val port = SharedPreferenceHelperTV.getCloudPort(context).first()
        val url = NetworkHelper.buildBaseUrl(host, port) + path
        return suspendCancellableCoroutine { cont ->
            NetworkHelper.request(method, url, json, token, extraHeaders) { code, body ->
                if (cont.isActive) cont.resume(code to body)
            }
        }
    }
}
