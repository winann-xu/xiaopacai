package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import com.xiaopacai.child.data.database.ParentDao
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.service.GuardianForegroundService
import org.json.JSONObject

/**
 * [TASK-PRELAUNCH-PARENT-RESET] 家长端换账号清理
 *
 * 设置页「清除账号绑定与本地数据」入口的后台实现（117 信新增需求，
 * [TASK-ACCOUNT-V1] 改为云端验证）：
 * 1. 必须通过云端账号验证（邮箱+密码 → POST /api/auth/login；离线拒绝清除）；
 * 2. 清除：Web 登录凭据（JWT + 账号邮箱）、Web 中继绑定（会话令牌/配对码/宿主配置）、
 *    家长端四张业务表（设备注册/策略/公告/使用汇总）；
 * 3. 回到「未绑定账号」状态（下次进入家长端走云端登录）；
 * 4. 写审计日志（parent_audit_log，不含敏感明文）；
 * 5. 仅影响家长端数据：device_id 设备身份与儿童端表（usage_records 等）保留。
 */
object ParentAccountReset {

    private const val TAG = "ParentAccountReset"

    const val PREFS_GUARDIAN = "guardian_prefs"        // 连接/绑定配置（与儿童端共用，仅清中继字段）

    /** 中继绑定相关键（清除后回到未绑定状态；device_id/device_name 设备身份保留） */
    private val RELAY_BINDING_KEYS = arrayOf(
        "relay_host", "relay_port", "relay_mode",
        "relay_fingerprint", "relay_session_token", "relay_pairing_code"
    )

    sealed class ResetResult {
        data class Success(val clearedSteps: List<String>) : ResetResult()
        data class Failed(val reason: String) : ResetResult()
    }

    /**
     * 执行换账号清理。任何一步失败都返回 Failed 且不落审计（未完成即不算清理成功）。
     *
     * @param email    账号邮箱（云端验证用）
     * @param password 登录密码（不落盘，验证后即弃）
     */
    fun resetAccount(context: Context, email: String, password: String): ResetResult {
        // 1. 云端账号验证（离线拒绝清除：无网络无法确认操作者身份）
        when (val r = CloudAccountManager.login(context, email, password)) {
            is CloudAccountManager.LoginResult.Failed -> {
                Log.w(TAG, "云端验证失败，拒绝清除: ${r.reason}")
                return ResetResult.Failed("${r.reason}，已取消清除")
            }
            is CloudAccountManager.LoginResult.Success -> { /* 验证通过 */ }
        }

        val steps = mutableListOf<String>()
        try {
            // 2. 清除 Web 账号绑定（JWT token + 账号邮箱；保留服务器地址配置）
            CloudAccountManager.clearAccount(context)
            steps += "web_account"

            // 3. 清除 Web 中继绑定（会话令牌/配对码/宿主配置）
            val prefs = context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            RELAY_BINDING_KEYS.forEach { editor.remove(it) }
            editor.apply()
            steps += "relay_binding"

            // 4. 断开现有 P2P 连接与监听（绑定已失效，避免旧会话继续收发）
            runCatching { GuardianForegroundService.getP2PConnection()?.disconnect() }
            runCatching { ParentP2PListenerService.stop(context) }
            steps += "p2p_disconnect"

            // 5. 清除家长端业务数据（四张表；儿童端表保留）
            ParentDao.clearAllParentData(context)
            steps += "parent_data"

            // 6. 审计日志（不含密码/令牌等敏感明文）
            ParentDao.insertAuditLog(context, "account_reset", JSONObject().apply {
                put("cleared", steps.joinToString(","))
                put("timestamp", System.currentTimeMillis() / 1000)
            }.toString())

            Log.i(TAG, "换账号清理完成: ${steps.joinToString(", ")}")

            return ResetResult.Success(steps)
        } catch (e: Exception) {
            Log.e(TAG, "换账号清理失败: ${e.message}", e)
            return ResetResult.Failed("清除失败: ${e.message}")
        }
    }
}
