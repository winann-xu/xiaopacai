package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.data.database.ParentDao
import com.xiaopacai.child.service.CloudSyncService
import com.xiaopacai.child.service.GuardianForegroundService
import org.json.JSONObject

/**
 * [TASK-MILESTONE-V3] 需求 3/4：旧账号残留检测与本地全清（D2 决策：device_id 一并重置）
 *
 * 清除范围（需求 4 原文）：公告、策略、分类、使用记录/报告缓存、
 * 家长端凭据（JWT/邮箱）、中继配置、本地缓存、设备身份。
 *
 * 安全前提（调用方保证其一）：
 * - 家长端换账号/解绑路径：已通过旧账号云端邮箱+密码验证（ParentAccountReset）；
 * - 儿童端换绑路径：新家长配对码（服务端已授权）+ 设备持有人界面确认。
 *
 * parent_audit_log 保留（审计链不断）；服务器地址配置保留（基础设施配置，非账号数据）。
 */
object LocalDataWipe {

    private const val TAG = "LocalDataWipe"

    /** 儿童端业务表 */
    private val CHILD_TABLES = arrayOf(
        "announcements", "policy_cache", "usage_records",
        "daily_summary", "pairing_info", "app_category"
    )

    /** 家长端业务表（parent_audit_log 保留） */
    private val PARENT_TABLES = arrayOf(
        "device_registry", "parent_policies",
        "parent_announcements", "parent_usage_summary"
    )

    const val PREFS_GUARDIAN = "guardian_prefs"

    /** 中继绑定 + 设备身份键（清除后重绑生成全新设备身份，D2） */
    private val GUARDIAN_CLEAR_KEYS = arrayOf(
        "relay_host", "relay_port", "relay_mode",
        "relay_fingerprint", "relay_session_token", "relay_pairing_code",
        "device_id", "device_name"
    )

    sealed class WipeResult {
        /** @param cleared 已执行的清除步骤；@param verified 三处核对结果（数据库/配置文件，UI 由调用方呈现） */
        data class Success(val cleared: List<String>, val verified: List<String>) : WipeResult()
        data class Failed(val reason: String) : WipeResult()
    }

    /** 儿童端旧绑定残留：本地业务数据或配对指纹尚存（决定换绑前是否弹确认） */
    fun hasChildResidue(context: Context): Boolean {
        try {
            val db = XiaopacaiApp.instance.database
                .getReadable(DbPassphraseProvider.getPassphrase(context))
            try {
                for (t in arrayOf("announcements", "policy_cache", "usage_records", "pairing_info")) {
                    db.rawQuery("SELECT 1 FROM $t LIMIT 1", emptyArray()).use {
                        if (it.moveToFirst()) return true
                    }
                }
                return false
            } finally {
            }
        } catch (e: Exception) {
            Log.e(TAG, "残留检测失败: ${e.message}")
            return false
        }
    }

    /** 本机设备身份（device_id，供服务端定位解绑本机旧记录） */
    fun getLocalDeviceId(context: Context): String? =
        context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
            .getString("device_id", null)?.takeIf { it.isNotBlank() }

    /** 无残留时的静默设备身份重置（避免旧 device_id 撞号/越权认领） */
    fun resetDeviceIdentitySilently(context: Context) {
        context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
            .edit().remove("device_id").remove("device_name").apply()
        Log.i(TAG, "设备身份已静默重置（无残留数据）")
    }

    /**
     * 本地全清 + 三处核对。
     * 顺序：先断云端同步（避免旧会话继续收发）→ 数据库业务表 → Web 凭据 → 中继配置/设备身份 → 核对。
     */
    fun wipeAll(context: Context): WipeResult {
        val cleared = mutableListOf<String>()
        try {
            // 1. 断开云端同步（绑定已失效，避免旧会话继续收发）
            runCatching { CloudSyncService.stopPolling() }
            cleared += "cloud_disconnect"

            // 2. 数据库业务表全清（audit 表保留）
            val db = XiaopacaiApp.instance.database
                .getWritable(DbPassphraseProvider.getPassphrase(context))
            try {
                (CHILD_TABLES + PARENT_TABLES).forEach { db.execSQL("DELETE FROM $it") }
            } finally {
            }
            cleared += "business_tables"

            // 3. 清除 Web 账号凭据（JWT + 邮箱 + 角色；保留服务器地址配置）
            CloudAccountManager.clearAccount(context)
            cleared += "web_credentials"

            // 4. 清除中继绑定与设备身份（重绑生成全新设备身份，D2）
            val editor = context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE).edit()
            GUARDIAN_CLEAR_KEYS.forEach { editor.remove(it) }
            editor.apply()
            cleared += "relay_and_identity"

            // 5. 三处核对：数据库行数 / 配置文件键（UI 层由调用方回到未绑定状态呈现）
            val verified = verifyClean(context)
            val failed = verified.filter { it.startsWith("✗") }
            if (failed.isNotEmpty()) {
                Log.e(TAG, "清除校验未通过: ${failed.joinToString("；")}")
                return WipeResult.Failed("清除校验未通过：${failed.joinToString("；")}")
            }

            // 6. 审计日志（不含密码/令牌等敏感明文）
            ParentDao.insertAuditLog(context, "account_reset", JSONObject().apply {
                put("cleared", cleared.joinToString(","))
                put("verified", verified.joinToString(" | "))
                put("timestamp", System.currentTimeMillis() / 1000)
            }.toString())

            Log.i(TAG, "本地全清完成: ${cleared.joinToString(", ")}")
            return WipeResult.Success(cleared, verified)
        } catch (e: Exception) {
            Log.e(TAG, "本地全清失败: ${e.message}", e)
            return WipeResult.Failed("清除失败: ${e.message}")
        }
    }

    /** 清除后核对：数据库业务表行数 = 0；凭据/中继/身份配置键不存在 */
    fun verifyClean(context: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val db = XiaopacaiApp.instance.database
                .getReadable(DbPassphraseProvider.getPassphrase(context))
            try {
                for (t in CHILD_TABLES + PARENT_TABLES) {
                    db.rawQuery("SELECT COUNT(*) FROM $t", emptyArray()).use {
                        val n = if (it.moveToFirst()) it.getLong(0) else -1L
                        out += if (n == 0L) "✓ 数据库表 $t 已清空"
                        else "✗ 数据库表 $t 仍有 $n 行"
                    }
                }
            } finally {
            }
        } catch (e: Exception) {
            out += "✗ 数据库核对失败: ${e.message}"
        }
        val webPrefs = context.getSharedPreferences(CloudAccountManager.PREFS_WEB, Context.MODE_PRIVATE)
        val webLeft = listOf(
            CloudAccountManager.KEY_WEB_TOKEN,
            CloudAccountManager.KEY_ACCOUNT_EMAIL,
            CloudAccountManager.KEY_ACCOUNT_ROLE
        ).filter { webPrefs.contains(it) }
        out += if (webLeft.isEmpty()) "✓ Web 凭据已清除（服务器地址保留）"
        else "✗ Web 凭据残留: ${webLeft.joinToString()}"

        val gPrefs = context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
        val gLeft = GUARDIAN_CLEAR_KEYS.filter { gPrefs.contains(it) }
        out += if (gLeft.isEmpty()) "✓ 中继配置与设备身份已清除"
        else "✗ 中继/身份键残留: ${gLeft.joinToString()}"
        return out
    }
}
