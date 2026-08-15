package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * [TASK-ACCOUNT-V1] 云端账号管理器（替代本地家长密码体系）
 *
 * 账号体系迁移（ADR 0009）：本地 PBKDF2 密码已退役，所有家长身份验证改为
 * 云端邮箱 + 密码验证（POST /api/auth/login）：
 * - 密码永不落盘：验证成功后仅保存 JWT（KeyStore 加密）与账号邮箱；
 * - 家长模式每次进入 / 切回 / 重启都必须云端验证（本进程会话内保持登录态）；
 * - 儿童端系统级门禁（守护设置/权限/应用分类/解除保护）统一走 SystemGateDialog，
 *   每次输入邮箱 + 密码云端验证；
 * - 离线语义：云端验证需要网络，离线时验证失败并明确提示（缓存上报不受影响，
 *   恢复联网后增量补报——见 SyncManager/DiagnosticsCollector 既有机制）。
 *
 * 服务器地址持久化于 web prefs（家长端登录页填写后保存）。
 */
object CloudAccountManager {

    private const val TAG = "CloudAccountManager"

    const val PREFS_WEB = "xiaopacai_web_prefs"
    const val KEY_WEB_TOKEN = "web_token"
    const val KEY_ACCOUNT_EMAIL = "account_email"
    const val KEY_WEB_HOST = "web_host"
    const val KEY_WEB_PORT = "web_port"
    const val KEY_ALLOW_HTTP = "allow_http"

    /** 网络登录客户端（可注入替换，便于单元测试网络失败路径） */
    var loginClient: CloudLoginClient = HttpCloudLoginClient

    /** 云端登录客户端抽象 */
    interface CloudLoginClient {
        /**
         * @return Triple(HTTP 状态码, 响应体, 错误体)
         * @throws Exception 网络不可用等连接异常
         */
        fun postLogin(host: String, port: Int, email: String, password: String): Triple<Int, String, String>
    }

    /** 默认实现：HTTPS 优先的 /api/auth/login POST（username 字段即邮箱） */
    private object HttpCloudLoginClient : CloudLoginClient {
        override fun postLogin(host: String, port: Int, email: String, password: String): Triple<Int, String, String> {
            val body = JSONObject().apply {
                put("username", email)
                put("password", password)
            }
            return httpPostJson(host, port, "/api/auth/login", body.toString(), null)
        }
    }

    sealed class LoginResult {
        /** 验证成功（已刷新本地 JWT 并记录账号邮箱） */
        data class Success(val email: String) : LoginResult()

        /** 验证失败（网络不可用 / 凭据错误 / 服务端异常） */
        data class Failed(val reason: String) : LoginResult()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_WEB, Context.MODE_PRIVATE)

    /**
     * 保存家长端服务器地址（登录成功后由调用方持久化，供后续门禁验证使用）
     */
    fun saveServerBase(context: Context, host: String, port: Int) {
        prefs(context).edit()
            .putString(KEY_WEB_HOST, host)
            .putInt(KEY_WEB_PORT, port)
            .apply()
    }

    fun getServerHost(context: Context): String? =
        prefs(context).getString(KEY_WEB_HOST, null)?.takeIf { it.isNotBlank() }

    fun getServerPort(context: Context): Int =
        prefs(context).getInt(KEY_WEB_PORT, 5000)

    /**
     * [TASK-ACCOUNT-V1-HOTFIX] 测试期允许公网 HTTP 开关（服务器尚未启用 HTTPS 时使用）。
     * 默认关闭；开启后进程内所有云端 HTTP 请求对公网地址也允许 http 回退。
     */
    fun saveAllowHttp(context: Context, allow: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_HTTP, allow).apply()
        com.xiaopacai.child.util.allowHttpOverride = allow
        Log.i(TAG, "测试期允许 HTTP: $allow")
    }

    fun getAllowHttp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_HTTP, false)

    /**
     * 已绑定账号邮箱（仅保存邮箱，密码永不落盘）
     */
    fun getBoundEmail(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun isBound(context: Context): Boolean = getBoundEmail(context) != null

    /**
     * 读取已保存的 JWT（KeyStore 加密存储，读取时解密；无则 null）
     */
    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_WEB_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { KeyStoreManager.decryptPrefsValue(it) }
            ?.takeIf { it.isNotBlank() }

    /**
     * 清除账号绑定（JWT + 邮箱；保留服务器地址配置）
     */
    fun clearAccount(context: Context) {
        prefs(context).edit()
            .remove(KEY_WEB_TOKEN)
            .remove(KEY_ACCOUNT_EMAIL)
            .apply()
        Log.i(TAG, "云端账号绑定已清除")
    }

    /**
     * 云端验证（登录即验证）：邮箱 + 密码 → POST /api/auth/login。
     *
     * 成功：刷新本地 JWT（KeyStore 加密落盘）并记录账号邮箱，密码不落盘；
     * 失败：返回可展示的中文原因（离线时明确提示「需要联网」）。
     */
    fun login(context: Context, email: String, password: String): LoginResult {
        val host = getServerHost(context)
        if (host == null) {
            return LoginResult.Failed("尚未配置家长端服务器地址，请先在家长端登录页填写服务器地址")
        }
        val port = getServerPort(context)
        // [TASK-ACCOUNT-V1-HOTFIX] 进程内同步测试期 HTTP 开关（App 重启后仍生效）
        com.xiaopacai.child.util.allowHttpOverride = getAllowHttp(context)

        val (code, respBody, errBody) = try {
            loginClient.postLogin(host, port, email.trim(), password)
        } catch (e: Exception) {
            Log.w(TAG, "云端验证网络异常: ${e.message}")
            return LoginResult.Failed("网络不可用，家长身份验证需要联网")
        }

        return when {
            code in 200..299 -> {
                val token = try {
                    JSONObject(respBody).optString("accessToken", "")
                } catch (e: Exception) { "" }
                if (token.isBlank()) {
                    return LoginResult.Failed("服务端响应缺少登录凭据（服务端版本过旧？）")
                }
                // [SEC-K5] JWT 仅用于数据同步接口鉴权；密码不落盘
                val normalized = email.trim().lowercase()
                prefs(context).edit()
                    .putString(KEY_WEB_TOKEN, KeyStoreManager.encryptPrefsValue(token))
                    .putString(KEY_ACCOUNT_EMAIL, normalized)
                    .apply()
                Log.i(TAG, "云端验证成功: $normalized")
                LoginResult.Success(normalized)
            }
            code == 401 -> LoginResult.Failed("邮箱或密码错误")
            else -> LoginResult.Failed("登录失败: HTTP $code ${errBody.take(80)}")
        }
    }
}
