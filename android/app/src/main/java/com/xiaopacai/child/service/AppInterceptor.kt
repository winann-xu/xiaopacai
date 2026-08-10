package com.xiaopacai.child.service

import android.content.Context
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import java.text.SimpleDateFormat
import java.util.*

/**
 * [TASK-D2-03] 应用拦截判断引擎
 *
 * 核心拦截逻辑：
 * 1. 检查是否处于超时停用状态
 * 2. 检查当前应用是否在白名单中
 * 3. 检查当前应用是否在黑名单中
 * 4. 检查分类限额是否已用尽
 *
 * 拦截优先级：黑名单 > 超时停用 > 分类限额 > 白名单豁免
 */
class AppInterceptor(private val context: Context) {

    companion object {
        private const val TAG = "AppInterceptor"

        /** 系统应用包名（不可拦截的） */
        private val SYSTEM_PACKAGES = setOf(
            "com.android.phone",
            "com.android.contacts",
            "com.android.mms",
            "com.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.android.settings",
            "com.android.systemui",
            "android",
            "com.google.android.gms"  // Google Play Services
        )
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 判断指定应用是否应被拦截
     *
     * @param packageName 目标应用包名
     * @return InterceptResult 拦截结果（是否拦截 + 原因描述）
     */
    fun shouldIntercept(packageName: String): InterceptResult {
        // 1. 系统应用永不拦截
        if (packageName in SYSTEM_PACKAGES) {
            return InterceptResult(intercept = false, reason = "系统应用")
        }

        // 2. 获取当前状态
        val passphrase = getPassphrase()
        val collector = GuardianForegroundService.getCollector()
        val isTimeout = collector?.isTimeoutActive ?: false
        val stopMode = collector?.stopMode ?: "none"

        // 3. 黑名单检查（优先级最高，始终拦截）
        if (isInBlacklist(packageName, passphrase)) {
            return InterceptResult(intercept = true, reason = "黑名单应用")
        }

        // 4. 白名单检查（超时后仍可使用）
        if (isTimeout && isInWhitelist(packageName, passphrase)) {
            return InterceptResult(intercept = false, reason = "白名单豁免")
        }

        // 5. 超时停用检查
        if (isTimeout && stopMode == "full") {
            return InterceptResult(intercept = true, reason = "超时停用（整机锁定）")
        }

        // 6. 分类限额检查（仅超时 partial 模式）
        if (isTimeout && stopMode == "partial") {
            val category = getAppCategory(packageName)
            if (isCategoryExceeded(category, passphrase)) {
                return InterceptResult(
                    intercept = true,
                    reason = "分类限额已用尽（${category}）"
                )
            }
            // 非受限分类允许继续使用
            if (category == "study") {
                return InterceptResult(intercept = false, reason = "学习应用（不受限）")
            }
            if (category == "other") {
                // partial 模式下，非指定分类的应用也允许
                return InterceptResult(intercept = false, reason = "非受限分类")
            }
        }

        return InterceptResult(intercept = false, reason = "正常使用")
    }

    /**
     * 检查是否在黑名单中
     */
    private fun isInBlacklist(packageName: String, passphrase: ByteArray): Boolean {
        return try {
            val db = XiaopacaiApp.instance.database.getReadable(passphrase)
            try {
                val cursor = db.rawQuery(
                    "SELECT policy_data FROM policy_cache WHERE policy_type = ?",
                    arrayOf("blacklist")
                )
                cursor.use {
                    if (it.moveToFirst()) {
                        val json = it.getString(0)
                        json.contains("\"$packageName\"")
                    } else false
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "黑名单查询失败: ${e.message}")
            false
        }
    }

    /**
     * 检查是否在白名单中
     */
    private fun isInWhitelist(packageName: String, passphrase: ByteArray): Boolean {
        return try {
            val db = XiaopacaiApp.instance.database.getReadable(passphrase)
            try {
                val cursor = db.rawQuery(
                    "SELECT policy_data FROM policy_cache WHERE policy_type = ?",
                    arrayOf("whitelist")
                )
                cursor.use {
                    if (it.moveToFirst()) {
                        val json = it.getString(0)
                        json.contains("\"$packageName\"")
                    } else false
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "白名单查询失败: ${e.message}")
            false
        }
    }

    /**
     * 获取应用分类（简化版，从包名推断）
     */
    private fun getAppCategory(packageName: String): String {
        val lower = packageName.lowercase()
        return when {
            lower.contains("game") || lower.contains("minecraft") || lower.contains("roblox") -> "game"
            lower.contains("wechat") || lower.contains("tencent") || lower.contains("douyin") ||
            lower.contains("tiktok") || lower.contains("facebook") || lower.contains("instagram") -> "social"
            lower.contains("video") || lower.contains("bilibili") || lower.contains("youtube") ||
            lower.contains("iqiyi") || lower.contains("netflix") -> "video"
            lower.contains("edu") || lower.contains("study") || lower.contains("learn") ||
            lower.contains("note") || lower.contains("calculator") -> "study"
            else -> "other"
        }
    }

    /**
     * 检查分类限额是否已用尽
     */
    private fun isCategoryExceeded(category: String, passphrase: ByteArray): Boolean {
        return try {
            val db = XiaopacaiApp.instance.database.getReadable(passphrase)
            try {
                // 查询该分类的限额
                val cursor = db.rawQuery(
                    "SELECT policy_data FROM policy_cache WHERE policy_type = ? AND policy_data LIKE ?",
                    arrayOf("category_limit", "%$category%")
                )
                val limit: Long = cursor.use {
                    if (it.moveToFirst()) {
                        val json = it.getString(0)
                        val limitPattern = Regex(""""categoryLimitMinutes"\s*:\s*(\d+)""")
                        limitPattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    } else 0L
                }

                if (limit <= 0) return false

                // 查询该分类今日使用量
                val today = dateFormat.format(Date())
                val usedCursor = db.rawQuery(
                    "SELECT COALESCE(SUM(total_minutes), 0) FROM usage_records WHERE date = ? AND category = ?",
                    arrayOf(today, category)
                )
                val used: Long = usedCursor.use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }

                used >= limit
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "分类限额检查失败: ${e.message}")
            false
        }
    }

    /**
     * 获取数据库密码
     */
    private fun getPassphrase(): ByteArray {
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val key = prefs.getString("db_key_seed", "xiaopacai_default_key")!!
        return key.toByteArray(Charsets.UTF_8)
    }
}

/**
 * [TASK-D2-03] 拦截结果
 *
 * @param intercept 是否需要拦截
 * @param reason 拦截/放行原因
 */
data class InterceptResult(
    val intercept: Boolean,
    val reason: String
)
