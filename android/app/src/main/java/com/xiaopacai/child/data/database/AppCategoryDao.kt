package com.xiaopacai.child.data.database

import android.content.ContentValues
import net.sqlcipher.database.SQLiteDatabase

/**
 * [TASK-OPT-12-P2] 应用分类数据访问对象
 *
 * 管理 app_category 表的读写：
 * - 列出全部已入库应用的分类设置
 * - 查询单个应用的分类（供采集器/拦截引擎使用）
 * - 更新分类（manual 覆盖 default）
 *
 * 分类口径（V3 统一）：game/social/video/learning/other。
 * source 字段：default=关键词规则生成，manual=家长手工设置。
 */
class AppCategoryDao(private val dbHelper: AppDatabase) {

    /**
     * 查询全部应用分类记录
     *
     * @return 每项含 packageName/appName/category/source/updatedAt
     */
    fun getAll(passphrase: ByteArray): List<Map<String, Any?>> {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val cursor = db.rawQuery(
                """SELECT package_name, app_name, category, source, updated_at
                   FROM app_category ORDER BY app_name COLLATE NOCASE""",
                null
            )
            val results = mutableListOf<Map<String, Any?>>()
            cursor.use {
                while (it.moveToNext()) {
                    results.add(mapOf(
                        "packageName" to it.getString(0),
                        "appName" to it.getString(1),
                        "category" to it.getString(2),
                        "source" to it.getString(3),
                        "updatedAt" to it.getLong(4)
                    ))
                }
            }
            results
        } finally {
            db.close()
        }
    }

    /**
     * 查询单个应用的分类
     *
     * @return 分类值（game/social/video/learning/other），未记录时返回 null
     */
    fun getCategory(packageName: String, passphrase: ByteArray): String? {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val cursor = db.rawQuery(
                "SELECT category FROM app_category WHERE package_name = ?",
                arrayOf(packageName)
            )
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } finally {
            db.close()
        }
    }

    /**
     * 批量查询分类（用于拦截引擎的高频判断）
     *
     * @return packageName → category 映射
     */
    fun getCategories(packageNames: List<String>, passphrase: ByteArray): Map<String, String> {
        if (packageNames.isEmpty()) return emptyMap()
        val db = dbHelper.getReadable(passphrase)
        return try {
            val placeholders = packageNames.joinToString(",") { "?" }
            val cursor = db.rawQuery(
                "SELECT package_name, category FROM app_category WHERE package_name IN ($placeholders)",
                packageNames.toTypedArray()
            )
            val map = mutableMapOf<String, String>()
            cursor.use {
                while (it.moveToNext()) {
                    map[it.getString(0)] = it.getString(1)
                }
            }
            map
        } finally {
            db.close()
        }
    }

    /**
     * 插入或更新应用分类（default 来源）
     *
     * 仅当记录不存在时写入，已存在（含 manual）不覆盖，
     * 保证家长手工设置不会被关键词规则重建冲掉。
     */
    fun insertIfAbsent(
        packageName: String,
        appName: String,
        category: String,
        passphrase: ByteArray
    ) {
        val db = dbHelper.getWritable(passphrase)
        try {
            db.execSQL(
                """INSERT OR IGNORE INTO app_category
                   (package_name, app_name, category, source, updated_at)
                   VALUES (?, ?, ?, 'default', ?)""",
                arrayOf(
                    packageName,
                    appName,
                    category,
                    (System.currentTimeMillis() / 1000).toString()
                )
            )
        } finally {
            db.close()
        }
    }

    /**
     * 插入或替换应用分类（manual 来源，用于家长端下发合并）
     */
    fun upsertManual(
        packageName: String,
        appName: String,
        category: String,
        passphrase: ByteArray
    ) {
        val db = dbHelper.getWritable(passphrase)
        try {
            val values = ContentValues().apply {
                put("package_name", packageName)
                put("app_name", appName)
                put("category", category)
                put("source", "manual")
                put("updated_at", System.currentTimeMillis() / 1000)
            }
            db.insertWithOnConflict(
                "app_category",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } finally {
            db.close()
        }
    }

    /**
     * 单条修改分类（source 置为 manual，家长设置覆盖默认）
     *
     * @return 是否更新成功
     */
    fun updateCategory(packageName: String, category: String, passphrase: ByteArray): Boolean {
        val db = dbHelper.getWritable(passphrase)
        return try {
            val values = ContentValues().apply {
                put("category", category)
                put("source", "manual")
                put("updated_at", System.currentTimeMillis() / 1000)
            }
            db.update(
                "app_category",
                values,
                "package_name = ?",
                arrayOf(packageName)
            ) > 0
        } finally {
            db.close()
        }
    }
}
