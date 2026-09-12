// [android-tv] 工具类 — 设备绑定（把本机电视绑定到家长账号）
// 小趴菜 TVOS 版
//
// 背景（用户反馈：web 端看不到电视，导致无法下发策略/公告）：
//   电视此前只调用了 /api/v1/device/register 做匿名注册，**没有走配对绑定**，
//   因此设备不出现在 GET /api/devices，策略/公告都下发不到。
//
// 本类复刻手机版 android-v3 CloudSyncService.generatePairCode()/verifyPairCode() 的流程
// （两端都用「家长 JWT」调用，服务端校验 pairing.OwnerUserId == 当前用户）：
//   1. POST /api/pairing/generate-code  { method: "manual" }            → { pairCode }
//   2. POST /api/pairing/verify         { pairCode, deviceId, ... }     → 绑定成功（并在服务端创建默认策略）
//
// 电视没有摄像头、也没有软键盘输入配对码的场景，因此用「家长在本机 TV 登录验证」这一步
// 直接完成自助绑定：登录成功 → 自动绑定到该家长账号。

package com.xiaopacai.tvos.util

import android.content.Context
import android.util.Log
import com.xiaopacai.tvos.p2p.TvP2pIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

object DeviceBinding {

    private const val TAG = "DeviceBindingTV"
    private const val PLATFORM = "android-tv"

    sealed class Result {
        object Success : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * 用家长 JWT 把本机绑定到该家长账号（生成配对码 → 立即校验绑定）。
     * 建议在「家长验证成功」后调用（主界面 / 家长设置入口都会经过验证）。
     */
    suspend fun bindWithParentToken(context: Context, parentJwt: String): Result {
        if (parentJwt.isBlank()) return Result.Failed("缺少登录令牌")

        val deviceId = ensureDeviceId(context)
        val deviceName = runCatching {
            SharedPreferenceHelperTV.getDeviceName(context).first()
        }.getOrDefault("小趴菜TV")

        // 1) 生成配对码
        val genBody = JSONObject().apply { put("method", "manual") }.toString()
        val (genCode, genResp) = request(context, "POST", "/api/pairing/generate-code", genBody, parentJwt)
        if (genCode !in 200..299) {
            Log.w(TAG, "生成配对码失败 HTTP $genCode")
            AppLogTV.w(TAG, "生成配对码失败 HTTP $genCode")
            return when (genCode) {
                401 -> Result.Failed("登录已失效，请重新验证")
                429 -> Result.Failed("操作过于频繁，请稍后再试")
                else -> Result.Failed("生成配对码失败（HTTP $genCode）")
            }
        }
        val pairCode = runCatching { JSONObject(genResp).optString("pairCode", "") }.getOrDefault("")
        if (pairCode.isBlank()) return Result.Failed("配对码为空")

        // 2) 校验并绑定设备
        // 【方案B】随绑定上报本机 P2P 身份证书指纹：服务端 P2P 握手要求
        // devices.cert_fingerprint 与 TLS 对端证书一致，否则拒绝（error_code=unpaired）。
        // 该字段已存在于 VerifyPairCodeRequest（服务端 /api/pairing/verify 支持），
        // 且走的是「家长 JWT 鉴权」路径 —— 服务端只信任这条路径写入的指纹。
        val fingerprint = runCatching { TvP2pIdentity.fingerprint(context) }.getOrDefault("")
        val verifyBody = JSONObject().apply {
            put("pairCode", pairCode)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("platform", PLATFORM)
            if (fingerprint.isNotBlank()) put("certFingerprint", fingerprint)
        }.toString()
        val (code, resp) = request(context, "POST", "/api/pairing/verify", verifyBody, parentJwt)
        return when (code) {
            in 200..299 -> {
                SharedPreferenceHelperTV.saveDeviceBound(context, true)
                Log.i(TAG, "设备绑定成功 deviceId=$deviceId name=$deviceName")
                AppLogTV.i(TAG, "设备绑定成功 name=$deviceName")
                Result.Success
            }
            403 -> {
                SharedPreferenceHelperTV.saveDeviceBound(context, false)
                Log.w(TAG, "设备已被其他账号绑定")
                AppLogTV.w(TAG, "绑定失败：该设备已被其他家长账号绑定")
                Result.Failed("该电视已被其他家长账号绑定，请先在 Web 端解绑")
            }
            401 -> Result.Failed("登录已失效，请重新验证")
            400 -> Result.Failed("配对码无效或已过期")
            else -> {
                Log.w(TAG, "绑定失败 HTTP $code: ${resp.take(200)}")
                AppLogTV.w(TAG, "绑定失败 HTTP $code")
                Result.Failed("绑定失败（HTTP $code）")
            }
        }
    }

    private suspend fun ensureDeviceId(context: Context): String {
        var id = SharedPreferenceHelperTV.getDeviceId(context).first()
        if (id.isBlank()) {
            SharedPreferenceHelperTV.generateDeviceId(context)
            id = SharedPreferenceHelperTV.getDeviceId(context).first()
        }
        return id
    }

    private suspend fun request(
        context: Context,
        method: String,
        path: String,
        json: String?,
        token: String
    ): Pair<Int, String> {
        val host = SharedPreferenceHelperTV.getCloudHost(context).first()
        val port = SharedPreferenceHelperTV.getCloudPort(context).first()
        val url = NetworkHelper.buildBaseUrl(host, port) + path
        return suspendCancellableCoroutine { cont ->
            NetworkHelper.request(method, url, json, token) { code, body ->
                if (cont.isActive) cont.resume(code to body)
            }
        }
    }
}
