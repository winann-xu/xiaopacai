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
 * 设置页「清除账号绑定与本地数据」入口的后台实现（117 信新增需求）：
 * 1. 必须通过家长密码验证（失败不可清除，且计入密码失败锁定计数）；
 * 2. 清除：Web 登录凭据（JWT token）、Web 中继绑定（会话令牌/配对码/宿主配置）、
 *    家长端四张业务表（设备注册/策略/公告/使用汇总）；
 * 3. 清除家长密码 → 回到「新账号绑定」状态（下次进入家长端走首次设置密码流程）；
 * 4. 写审计日志（parent_audit_log，不含敏感明文）；
 * 5. 仅影响家长端数据：device_id 设备身份与儿童端表（usage_records 等）保留。
 */
object ParentAccountReset {

    private const val TAG = "ParentAccountReset"

    const val PREFS_WEB = "xiaopacai_web_prefs"        // Web 登录 token（JWT）
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
     */
    fun resetAccount(context: Context, password: String): ResetResult {
        // 1. 家长密码验证（失败不可清除）
        if (!ParentPasswordManager.isPasswordSet(context)) {
            return ResetResult.Failed("家长密码尚未设置，无法清除账号")
        }
        if (!ParentPasswordManager.verifyPassword(context, password)) {
            // verifyPassword 内部已记录失败计数（连续 5 次锁定 5 分钟）
            return ResetResult.Failed("家长密码验证失败，已取消清除")
        }

        val steps = mutableListOf<String>()
        try {
            // 2. 清除 Web 账号登录凭据（加密存储的 JWT token）
            context.getSharedPreferences(PREFS_WEB, Context.MODE_PRIVATE)
                .edit().clear().apply()
            steps += "web_token"

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

            // 7. 清除家长密码 → 回到「新账号绑定」状态
            ParentPasswordManager.clearPassword(context)
            Log.i(TAG, "换账号清理完成: ${steps.joinToString(", ")}")

            return ResetResult.Success(steps)
        } catch (e: Exception) {
            Log.e(TAG, "换账号清理失败: ${e.message}", e)
            return ResetResult.Failed("清除失败: ${e.message}")
        }
    }
}
