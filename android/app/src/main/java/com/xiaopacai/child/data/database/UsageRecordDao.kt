package com.xiaopacai.child.data.database

import android.content.ContentValues
import net.sqlcipher.database.SQLiteDatabase

/**
 * [TASK-D2-01] 使用时长记录数据访问对象
 *
 * 封装 usage_records 和 daily_summary 表的 CRUD 操作。
 * 所有数据通过 SQLCipher 加密落盘。
 */
class UsageRecordDao(private val dbHelper: AppDatabase) {

    /**
     * 写入或更新应用使用时长记录
     *
     * @param packageName 应用包名
     * @param appName 应用名称
     * @param date 日期（yyyy-MM-dd）
     * @param totalMinutes 累计使用分钟数
     * @param category 应用分类
     * @return 插入行 ID 或更新行数
     */
    fun upsertUsageRecord(
        packageName: String,
        appName: String,
        date: String,
        totalMinutes: Long,
        category: String = "other",
        passphrase: ByteArray
    ): Long {
        val db = dbHelper.getWritable(passphrase)
        return try {
            val values = ContentValues().apply {
                put("package_name", packageName)
                put("app_name", appName)
                put("date", date)
                put("total_minutes", totalMinutes)
                put("category", category)
                put("last_used_at", System.currentTimeMillis() / 1000)
                put("sync_status", 0)  // 标记为未同步
                put("updated_at", System.currentTimeMillis() / 1000)
            }

            // 使用 INSERT OR REPLACE 实现 upsert
            db.insertWithOnConflict(
                "usage_records",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } finally {
            db.close()
        }
    }

    /**
     * 批量写入使用记录（事务中执行，提升性能）
     *
     * @param records 记录列表，每项为 (packageName, appName, date, totalMinutes, category)
     * @return 成功写入的记录数
     */
    fun batchUpsertUsageRecords(
        records: List<UsageRecordEntry>,
        passphrase: ByteArray
    ): Int {
        val db = dbHelper.getWritable(passphrase)
        var count = 0
        return try {
            db.beginTransaction()
            records.forEach { entry ->
                val values = ContentValues().apply {
                    put("package_name", entry.packageName)
                    put("app_name", entry.appName)
                    put("date", entry.date)
                    put("total_minutes", entry.totalMinutes)
                    put("category", entry.category)
                    put("last_used_at", System.currentTimeMillis() / 1000)
                    put("sync_status", 0)
                    put("updated_at", System.currentTimeMillis() / 1000)
                }
                db.insertWithOnConflict(
                    "usage_records",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                count++
            }
            db.setTransactionSuccessful()
            count
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /**
     * 查询指定日期的所有使用记录
     *
     * @param date 日期（yyyy-MM-dd），默认今天
     * @return 使用记录列表（Map 形式）
     */
    fun getUsageRecordsByDate(
        date: String,
        passphrase: ByteArray
    ): List<Map<String, Any?>> {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val cursor = db.rawQuery(
                "SELECT package_name, app_name, total_minutes, category, sync_status " +
                "FROM usage_records WHERE date = ? ORDER BY total_minutes DESC",
                arrayOf(date)
            )
            val results = mutableListOf<Map<String, Any?>>()
            cursor.use {
                while (it.moveToNext()) {
                    results.add(mapOf(
                        "packageName" to it.getString(0),
                        "appName" to it.getString(1),
                        "totalMinutes" to it.getLong(2),
                        "category" to it.getString(3),
                        "syncStatus" to it.getInt(4)
                    ))
                }
            }
            results
        } finally {
            db.close()
        }
    }

    /**
     * 获取当日总使用时长（分钟）
     *
     * @param date 日期（yyyy-MM-dd）
     * @return 总分钟数
     */
    fun getTodayTotalMinutes(date: String, passphrase: ByteArray): Long {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val cursor = db.rawQuery(
                "SELECT COALESCE(SUM(total_minutes), 0) FROM usage_records WHERE date = ?",
                arrayOf(date)
            )
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } finally {
            db.close()
        }
    }

    /**
     * 更新每日汇总表
     *
     * @param date 日期
     * @param totalMinutes 总分钟数
     * @param gameMinutes 游戏分钟数
     * @param studyMinutes 学习分钟数
     * @param limitMinutes 当日限额
     */
    fun updateDailySummary(
        date: String,
        totalMinutes: Long,
        gameMinutes: Long,
        studyMinutes: Long,
        limitMinutes: Long,
        passphrase: ByteArray
    ) {
        val db = dbHelper.getWritable(passphrase)
        try {
            val exceeded = if (totalMinutes >= limitMinutes && limitMinutes > 0) 1 else 0
            val stopMode = if (exceeded == 1) "full" else "none"

            val values = ContentValues().apply {
                put("date", date)
                put("total_minutes", totalMinutes)
                put("game_minutes", gameMinutes)
                put("study_minutes", studyMinutes)
                put("limit_minutes", limitMinutes)
                put("limit_exceeded", exceeded)
                put("stop_mode", stopMode)
            }
            db.insertWithOnConflict(
                "daily_summary",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } finally {
            db.close()
        }
    }

    /**
     * 获取每日汇总
     *
     * @param date 日期
     * @return 汇总数据 Map，或 null
     */
    fun getDailySummary(
        date: String,
        passphrase: ByteArray
    ): Map<String, Any?>? {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val cursor = db.rawQuery(
                "SELECT total_minutes, game_minutes, study_minutes, limit_minutes, " +
                "limit_exceeded, stop_mode FROM daily_summary WHERE date = ?",
                arrayOf(date)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    mapOf(
                        "totalMinutes" to it.getLong(0),
                        "gameMinutes" to it.getLong(1),
                        "studyMinutes" to it.getLong(2),
                        "limitMinutes" to it.getLong(3),
                        "limitExceeded" to (it.getInt(4) == 1),
                        "stopMode" to it.getString(5)
                    )
                } else null
            }
        } finally {
            db.close()
        }
    }

    /**
     * 查询未同步的记录（用于 P2P 同步上报）
     *
     * @param limit 最大返回条数
     * @return 未同步记录列表
     */
    fun getUnsyncedRecords(
        limit: Int = 500,
        passphrase: ByteArray
    ): List<Map<String, Any?>> {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val cursor = db.rawQuery(
                "SELECT id, package_name, app_name, date, total_minutes, category " +
                "FROM usage_records WHERE sync_status = 0 LIMIT ?",
                arrayOf(limit.toString())
            )
            val results = mutableListOf<Map<String, Any?>>()
            cursor.use {
                while (it.moveToNext()) {
                    results.add(mapOf(
                        "id" to it.getLong(0),
                        "packageName" to it.getString(1),
                        "appName" to it.getString(2),
                        "date" to it.getString(3),
                        "totalMinutes" to it.getLong(4),
                        "category" to it.getString(5)
                    ))
                }
            }
            results
        } finally {
            db.close()
        }
    }

    /**
     * 标记记录为已同步
     *
     * @param ids 记录 ID 列表
     */
    fun markAsSynced(ids: List<Long>, passphrase: ByteArray) {
        if (ids.isEmpty()) return
        val db = dbHelper.getWritable(passphrase)
        try {
            val placeholders = ids.joinToString(",") { "?" }
            val values = ContentValues().apply {
                put("sync_status", 1)
            }
            db.update(
                "usage_records",
                values,
                "id IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray()
            )
        } finally {
            db.close()
        }
    }
}

/**
 * [TASK-D2-01] 使用记录条目（用于批量操作）
 */
data class UsageRecordEntry(
    val packageName: String,
    val appName: String,
    val date: String,
    val totalMinutes: Long,
    val category: String = "other"
)
