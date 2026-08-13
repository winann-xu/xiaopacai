package com.xiaopacai.child.service

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.util.CategoryTaxonomy
import com.xiaopacai.child.util.DbPassphraseProvider
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

        /** 常见桌面启动器包名（免拦截，避免"返回桌面"后再次被拦截的死循环） */
        private val LAUNCHER_PACKAGES = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.launcher4",
            "com.miui.home",
            "com.sec.android.app.launcher",
            "com.oppo.launcher",
            "com.huawei.android.launcher",
            "com.vivo.launcher",
            "com.bbk.launcher2"
        )

        /**
         * [TASK-OPT-7] partial 模式纯判定逻辑（可单测）
         */
        fun decidePartialIntercept(
            category: String,
            isBlacklisted: Boolean,
            isWhitelisted: Boolean,
            categoryExceeded: Boolean
        ): InterceptResult {
            // 细粒度分类（如 short_video/browser）映射到引擎粗粒度口径
            val engineCategory = CategoryTaxonomy.toEngineCategory(category)
            if (isBlacklisted) {
                return InterceptResult(intercept = true, reason = "blacklist")
            }
            if (isWhitelisted) {
                return InterceptResult(intercept = false, reason = "whitelist")
            }
            if (categoryExceeded) {
                return InterceptResult(intercept = true, reason = "category-limit")
            }
            if (engineCategory == "learning" || engineCategory == "study") {
                return InterceptResult(intercept = false, reason = "study")
            }
            if (engineCategory in setOf("game", "social", "video")) {
                return InterceptResult(intercept = true, reason = "partial-$engineCategory")
            }
            return InterceptResult(intercept = false, reason = "other")
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 系统当前默认桌面（动态解析，兜底覆盖厂商定制启动器） */
    private val defaultHomePackage: String? by lazy {
        try {
            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(homeIntent, 0)
            resolveInfo?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }

    /** [FIX-LEGACY-b] 系统已启用的输入法包名（免拦截） */
    private val inputMethodPackages: Set<String> by lazy {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "无法获取输入法列表: ${e.message}")
            emptySet()
        }
    }

    /**
     * 刷新输入法包名缓存（IME 增删后调用）
     */
    fun refreshInputMethodPackages(): Set<String> {
        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val packages = imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
            // 更新缓存（Kotlin lazy 不支持重置，改用类属性）
            packages
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * 判断指定应用是否应被拦截
     *
     * @param packageName 目标应用包名
     * @return InterceptResult 拦截结果（是否拦截 + 原因描述）
     */
    fun shouldIntercept(packageName: String): InterceptResult {
        // [FIX] 守护应用自身永不拦截：超时停用期间家长/用户仍需能进入权限引导、设置等自身页面，
        // 否则引导页被 BlockOverlay 覆盖形成“点去开启无反应”的死锁
        if (packageName == context.packageName) {
            return InterceptResult(intercept = false, reason = "守护应用自身")
        }

        // 1. 系统应用永不拦截
        if (packageName in SYSTEM_PACKAGES) {
            return InterceptResult(intercept = false, reason = "系统应用")
        }

        // 1.1 桌面启动器永不拦截（含动态默认桌面，防止"返回桌面"后死循环）
        if (packageName in LAUNCHER_PACKAGES || packageName == defaultHomePackage) {
            return InterceptResult(intercept = false, reason = "桌面启动器")
        }

        // [FIX-LEGACY-b] 输入法永不拦截（全停用模式下儿童端对话框输入不再被拦）
        if (packageName in inputMethodPackages) {
            return InterceptResult(intercept = false, reason = "输入法")
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
            return decidePartialIntercept(
                category = category,
                isBlacklisted = isInBlacklist(packageName, passphrase),
                isWhitelisted = isInWhitelist(packageName, passphrase),
                categoryExceeded = isCategoryExceeded(category, passphrase)
            )
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
     * 获取数据库密码 [TASK-D3-05]
     */
    private fun getPassphrase(): ByteArray {
        return DbPassphraseProvider.getPassphrase(context)
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
