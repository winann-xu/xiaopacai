package com.xiaopacai.child.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.data.database.AppCategoryDao
import com.xiaopacai.child.service.UsageStatsCollector

/**
 * [TASK-OPT-12-P2] 应用分类初始化助手
 *
 * 首次使用时扫描已安装应用，按 UsageStatsCollector.CATEGORY_RULES 关键词规则
 * 生成默认分类并落库（无匹配归 other），后续仅在应用新增时补录。
 *
 * 分类口径：库内统一存储 V3 标准值 game/social/video/learning/other；
 * 采集器旧 study 口径在入库时映射为 learning。
 */
object AppCategoryHelper {

    private const val TAG = "AppCategoryHelper"

    /** 需要跳过的包名（本应用自身） */
    private val SKIP_PACKAGES = setOf("com.xiaopacai.child")

    /**
     * 初始化应用分类表（幂等，可重复调用）
     *
     * 扫描已安装应用，缺失的分类记录按关键词规则补录 default 分类。
     *
     * @return 本次新增的记录数
     */
    fun ensureInitialized(context: Context, passphrase: ByteArray): Int {
        return try {
            val dao = AppCategoryDao(XiaopacaiApp.instance.database)
            val installed = getInstalledPackages(context)
            // [FIX] 一次性读取已入库包名，避免每个应用单独开库查询
            val existing = dao.getAllPackageNames(passphrase)
            val newEntries = mutableListOf<Triple<String, String, String>>()
            installed.forEach { (packageName, appName) ->
                // 已存在（含 manual）跳过，不覆盖家长设置
                if (packageName !in existing) {
                    newEntries.add(Triple(packageName, appName, classifyByRules(packageName, appName)))
                }
            }
            if (newEntries.isNotEmpty()) {
                // 单事务批量落库
                dao.insertCategoriesIfAbsentBatch(newEntries, passphrase)
                Log.i(TAG, "应用分类初始化完成，新增 ${newEntries.size} 条（共 ${installed.size} 个应用）")
            }
            newEntries.size
        } catch (e: Exception) {
            Log.e(TAG, "应用分类初始化失败: ${e.message}")
            0
        }
    }

    /**
     * [REQ] 一键自动分类：对所有已安装应用按新规则重新分类。
     * 家长手工设置（source=manual）的项跳过，不覆盖。
     *
     * @return 本次自动分类的应用数量
     */
    fun autoClassify(context: Context, passphrase: ByteArray): Int {
        return try {
            val dao = AppCategoryDao(XiaopacaiApp.instance.database)
            val installed = getInstalledPackages(context)
            val manual = dao.getManualPackageNames(passphrase)
            val entries = mutableListOf<Triple<String, String, String>>()
            installed.forEach { (pkg, name) ->
                if (pkg !in manual) {
                    entries.add(Triple(pkg, name, CategoryTaxonomy.classify(pkg, name)))
                }
            }
            dao.insertCategoriesOrReplaceBatch(entries, passphrase)
            entries.size
        } catch (e: Exception) {
            Log.e(TAG, "一键自动分类失败: ${e.message}")
            0
        }
    }

    /**
     * 获取已安装应用列表（包名 → 应用名）
     */
    fun getInstalledPackages(context: Context): Map<String, String> {
        val pm = context.packageManager
        val result = mutableMapOf<String, String>()
        val apps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        apps.forEach { info ->
            if (info.packageName in SKIP_PACKAGES) return@forEach
            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                info.packageName
            }
            result[info.packageName] = label
        }
        return result
    }

    /**
     * 按关键词规则分类（细粒度，规则见 CategoryTaxonomy）
     */
    fun classifyByRules(packageName: String, appName: String): String {
        return CategoryTaxonomy.classify(packageName, appName)
    }

    /**
     * 将细粒度分类映射到引擎粗粒度口径
     * （game/social/video/study/other），供拦截/限额/汇总使用。
     */
    fun toInternalCategory(category: String): String {
        return CategoryTaxonomy.toEngineCategory(category)
    }
}
