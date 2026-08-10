package com.xiaopacai.child.data.database

import android.content.ContentValues
import net.sqlcipher.database.SQLiteDatabase

/**
 * [TASK-D2-04] 公告数据访问对象
 *
 * 管理从家长端同步的公告的本地存储和查询。
 * 支持按优先级、已读/未读、过期时间过滤。
 */
class AnnouncementDao(private val dbHelper: AppDatabase) {

    /**
     * 插入或更新公告（UPSERT by announcement_id）
     */
    fun upsert(
        announcementId: String,
        title: String,
        content: String,
        priority: Int = 0,
        expiresAt: Long = 0,
        passphrase: ByteArray
    ): Long {
        val db = dbHelper.getWritable(passphrase)
        return try {
            val values = ContentValues().apply {
                put("announcement_id", announcementId)
                put("title", title)
                put("content", content)
                put("priority", priority)
                put("is_read", 0)  // 新公告默认未读
                put("expires_at", expiresAt)
            }
            db.insertWithOnConflict(
                "announcements",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } finally {
            db.close()
        }
    }

    /**
     * 获取所有有效公告（未过期，按优先级和创建时间排序）
     *
     * @param passphrase 数据库密码
     * @return 公告列表
     */
    fun getAllActive(passphrase: ByteArray): List<Map<String, Any?>> {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val nowTimestamp = System.currentTimeMillis() / 1000
            val cursor = db.rawQuery(
                """SELECT announcement_id, title, content, priority, is_read, created_at, expires_at
                   FROM announcements
                   WHERE expires_at = 0 OR expires_at > ?
                   ORDER BY priority DESC, created_at DESC
                   LIMIT 20""",
                arrayOf(nowTimestamp.toString())
            )
            val results = mutableListOf<Map<String, Any?>>()
            cursor.use {
                while (it.moveToNext()) {
                    results.add(mapOf(
                        "id" to it.getString(0),
                        "title" to it.getString(1),
                        "content" to it.getString(2),
                        "priority" to it.getInt(3),
                        "isRead" to (it.getInt(4) == 1),
                        "createdAt" to it.getLong(5),
                        "expiresAt" to it.getLong(6)
                    ))
                }
            }
            results
        } finally {
            db.close()
        }
    }

    /**
     * 获取未读公告数量
     */
    fun getUnreadCount(passphrase: ByteArray): Int {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val nowTimestamp = System.currentTimeMillis() / 1000
            val cursor = db.rawQuery(
                """SELECT COUNT(*) FROM announcements
                   WHERE is_read = 0 AND (expires_at = 0 OR expires_at > ?)""",
                arrayOf(nowTimestamp.toString())
            )
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } finally {
            db.close()
        }
    }

    /**
     * 标记公告为已读
     */
    fun markAsRead(announcementId: String, passphrase: ByteArray) {
        val db = dbHelper.getWritable(passphrase)
        try {
            val values = ContentValues().apply {
                put("is_read", 1)
            }
            db.update("announcements", values, "announcement_id = ?", arrayOf(announcementId))
        } finally {
            db.close()
        }
    }

    /**
     * 清空过期公告
     */
    fun cleanExpired(passphrase: ByteArray): Int {
        val db = dbHelper.getWritable(passphrase)
        return try {
            val nowTimestamp = System.currentTimeMillis() / 1000
            db.delete("announcements", "expires_at > 0 AND expires_at < ?", arrayOf(nowTimestamp.toString()))
        } finally {
            db.close()
        }
    }
}
