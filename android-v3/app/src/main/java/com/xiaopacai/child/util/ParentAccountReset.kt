package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * [TASK-MILESTONE-V3] 需求 4 换账号/解绑全清（D2：device_id 一并重置，重绑全新设备身份）
 *
 * 设置页「清除账号绑定与本地数据」入口与登录页换新账号流程共用：
 * 1. 必须通过旧账号云端验证（邮箱+密码 → POST /api/auth/login；离线拒绝清除）；
 * 2. 服务端本机设备解绑（尽力而为）：旧 JWT → GET /api/devices 定位本机 device_id →
 *    POST /api/auth/verify-password 换操作令牌 → DELETE /api/devices/{id}
 *    （服务端硬删除全部关联数据，A12）；失败不阻断本地清除；
 * 3. 本地全清（见 LocalDataWipe）：儿童端+家长端业务表、Web 凭据（保留服务器地址）、
 *    中继配置、设备身份（device_id/device_name）；
 * 4. 三处核对（数据库/配置文件，UI 由调用方呈现）并写审计日志（不含敏感明文）。
 */
object ParentAccountReset {

    private const val TAG = "ParentAccountReset"

    sealed class ResetResult {
        /** @param clearedSteps 已执行步骤；@param verified 三处核对结果（展示用） */
        data class Success(val clearedSteps: List<String>, val verified: List<String> = emptyList()) : ResetResult()
        data class Failed(val reason: String) : ResetResult()
    }

    /**
     * 执行换账号/解绑全清。任何一步失败都返回 Failed 且不落审计（未完成即不算清理成功）。
     *
     * @param email    旧账号邮箱（云端验证用，验证后其凭据会被清除）
     * @param password 旧账号登录密码（不落盘，验证后即弃）
     */
    fun resetAccount(context: Context, email: String, password: String): ResetResult {
        // 1. 旧账号云端验证（离线拒绝清除：无网络无法确认操作者身份）
        when (val r = CloudAccountManager.login(context, email, password)) {
            is CloudAccountManager.LoginResult.Failed -> {
                Log.w(TAG, "云端验证失败，拒绝清除: ${r.reason}")
                return ResetResult.Failed("${r.reason}，已取消清除")
            }
            is CloudAccountManager.LoginResult.Success -> { /* 验证通过 */ }
        }

        val steps = mutableListOf<String>()

        // 2. 服务端本机设备解绑（尽力而为；只删本机 device_id 名下的记录，不碰其它设备）
        if (tryUnbindServerDevice(context, password)) steps += "server_unbind"

        // 3. 本地全清 + 三处核对
        return when (val wipe = LocalDataWipe.wipeAll(context)) {
            is LocalDataWipe.WipeResult.Failed -> ResetResult.Failed(wipe.reason)
            is LocalDataWipe.WipeResult.Success -> {
                steps.addAll(wipe.cleared)
                ResetResult.Success(steps, wipe.verified)
            }
        }
    }

    /**
     * 尽力而为的服务端解绑：本机 device_id 注册在旧账号名下时才删除。
     * @return true 表示已执行成功删除；false 表示跳过（未注册/失败不阻断本地清除）
     */
    private fun tryUnbindServerDevice(context: Context, password: String): Boolean {
        return try {
            val deviceId = LocalDataWipe.getLocalDeviceId(context)
            val host = CloudAccountManager.getServerHost(context)
            val port = CloudAccountManager.getServerPort(context)
            val token = CloudAccountManager.getToken(context)
            if (deviceId == null || host == null || token == null) {
                Log.i(TAG, "无本机设备身份或登录态，跳过服务端解绑")
                return false
            }

            // 2a. 定位本机设备（旧账号名下）
            val (code, resp, _) = httpGetJson(host, port, "/api/devices", token)
            if (code !in 200..299) {
                Log.w(TAG, "获取设备列表失败 HTTP $code，跳过服务端解绑")
                return false
            }
            val devices = JSONObject(resp).optJSONArray("devices") ?: return false
            var serverId = -1L
            for (i in 0 until devices.length()) {
                val d = devices.optJSONObject(i) ?: continue
                if (d.optString("deviceId", "") == deviceId) {
                    serverId = d.optLong("id", -1)
                    break
                }
            }
            if (serverId <= 0) {
                Log.i(TAG, "本机设备未注册在旧账号名下，跳过服务端解绑")
                return false
            }

            // 2b. 登录态密码二次验证 → 一次性操作令牌（5 分钟单次有效）
            val (vCode, vResp, _) = httpPostJson(host, port, "/api/auth/verify-password",
                JSONObject().put("password", password).toString(), token)
            if (vCode !in 200..299) {
                Log.w(TAG, "二次验证失败 HTTP $vCode，跳过服务端解绑")
                return false
            }
            val actionToken = JSONObject(vResp).optString("actionToken", "")
            if (actionToken.isBlank()) {
                Log.w(TAG, "服务端未返回操作令牌，跳过服务端解绑")
                return false
            }

            // 2c. 解绑（服务端硬删除全部关联数据，A12）
            val (dCode, _, dErr) = httpDeleteJson(host, port, "/api/devices/$serverId", token,
                mapOf("X-Action-Token" to actionToken))
            if (dCode !in 200..299) {
                Log.w(TAG, "服务端解绑失败 HTTP $dCode ${dErr.take(80)}，跳过")
                return false
            }
            Log.i(TAG, "服务端本机设备已解绑: $deviceId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "服务端解绑异常（跳过）: ${e.message}")
            false
        }
    }
}
