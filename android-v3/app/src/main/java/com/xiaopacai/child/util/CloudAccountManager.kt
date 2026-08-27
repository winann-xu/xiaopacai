package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
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
    // [V2.0.4] 未配置服务器地址时的默认值：生产服务器（HTTPS 优先，公网强制 HTTPS）
    const val DEFAULT_WEB_HOST = "xpc.winann.com"
    const val DEFAULT_WEB_PORT = 443
    // [TASK-MILESTONE-V3] 需求 13：账号角色（登录响应 user.role），用于中继设置等 admin 功能门控
    const val KEY_ACCOUNT_ROLE = "account_role"

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
            ?: DEFAULT_WEB_HOST

    fun getServerPort(context: Context): Int =
        prefs(context).getInt(KEY_WEB_PORT, DEFAULT_WEB_PORT)

    // [TASK-MILESTONE-V3] 132 信需求 1：已移除「测试期允许 HTTP」开关与 allow_http 持久化
    // （HTTPS 已上线；局域网 HTTP 回退由 CloudHttp.isLanHost 自动处理，无需用户配置）。

    /**
     * 已绑定账号邮箱（仅保存邮箱，密码永不落盘）
     */
    fun getBoundEmail(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun isBound(context: Context): Boolean = getBoundEmail(context) != null

    /**
     * [TASK-MILESTONE-V3] 需求 13：当前账号角色（登录时保存；未登录/旧数据返回 null，按普通家长处理）
     */
    fun getAccountRole(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT_ROLE, null)?.takeIf { it.isNotBlank() }

    fun isAdmin(context: Context): Boolean = getAccountRole(context) == "admin"

    /**
     * 读取已保存的 JWT（KeyStore 加密存储，读取时解密；无则 null）
     */
    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_WEB_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { KeyStoreManager.decryptPrefsValue(it) }
            ?.takeIf { it.isNotBlank() }

    /**
     * 清除账号绑定（JWT + 邮箱 + 角色；保留服务器地址配置）
     */
    fun clearAccount(context: Context) {
        prefs(context).edit()
            .remove(KEY_WEB_TOKEN)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_ROLE)
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

        val (code, respBody, errBody) = try {
            loginClient.postLogin(host, port, email.trim(), password)
        } catch (e: Exception) {
            Log.w(TAG, "云端验证网络异常: ${e.message}")
            // [TASK-MILESTONE-V3] 需求 14：登录过程进运行日志（脱敏，不含密码明文）
            AppLog.w("Account", "云端验证网络异常: ${e.message}")
            // [TASK-MILESTONE-V3] 132 信需求 2：失败文案细分
            return LoginResult.Failed(loginNetworkErrorMessage(context, e))
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
                // [TASK-MILESTONE-V3] 需求 13：保存账号角色（user.role），用于 admin 功能门控
                val role = try {
                    JSONObject(respBody).optJSONObject("user")?.optString("role", "")
                } catch (e: Exception) { "" }
                prefs(context).edit()
                    .putString(KEY_WEB_TOKEN, KeyStoreManager.encryptPrefsValue(token))
                    .putString(KEY_ACCOUNT_EMAIL, normalized)
                    .putString(KEY_ACCOUNT_ROLE, role)
                    .apply()
                Log.i(TAG, "云端验证成功: $normalized (role=$role)")
                AppLog.i("Account", "云端登录成功 $normalized (role=$role)")
                // [TASK-HARDENING-V1.1.1] Bug3-B：登录/绑定成功后立即上传日志
                // （登录日志即刻入库 Web，失败自动进入 5/15/60 分钟指数退避）
                try {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        runCatching { LogUploader.uploadBlocking(context) }
                    }
                } catch (e: Exception) {
                    AppLog.w("Account", "登录后触发日志上传失败: ${e.message}")
                }
                LoginResult.Success(normalized)
            }
            code == 401 -> {
                AppLog.w("Account", "云端登录失败: 邮箱或密码错误")
                LoginResult.Failed("邮箱或密码错误")
            }
            else -> {
                AppLog.w("Account", "云端登录失败: HTTP $code")
                LoginResult.Failed("登录失败: HTTP $code ${errBody.take(80)}")
            }
        }
    }

    /**
     * [TASK-MILESTONE-V3] 132 信需求 2：登录失败文案细分
     * - 设备无网络 → 保留「网络不可用，家长身份验证需要联网」
     * - DNS/连接超时/被拒绝 → 「无法连接服务器，请检查 Web 服务地址与网络」
     * - HTTPS 握手失败（对端非 HTTPS）→ 「服务器未启用 HTTPS 或地址有误」
     */
    fun loginNetworkErrorMessage(context: Context, e: Exception): String {
        // 设备当前无任何可用网络（WiFi/移动数据均未连接）
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val noNetwork = cm?.activeNetwork == null ||
            cm.getNetworkCapabilities(cm.activeNetwork) == null
        if (noNetwork) return "网络不可用，家长身份验证需要联网"
        return when (e) {
            is CloudConnectionException -> when (e.kind) {
                CloudConnectionException.Kind.HTTPS_REQUIRED -> "服务器未启用 HTTPS 或地址有误"
                CloudConnectionException.Kind.CANNOT_CONNECT -> "无法连接服务器，请检查 Web 服务地址与网络"
                CloudConnectionException.Kind.NO_NETWORK -> "网络不可用，家长身份验证需要联网"
            }
            else -> "无法连接服务器，请检查 Web 服务地址与网络"
        }
    }
}
