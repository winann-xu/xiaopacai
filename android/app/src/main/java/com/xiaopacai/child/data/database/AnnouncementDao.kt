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
     *
     * [TASK-OPT-12-P1] 新增 requiresAck / acknowledgedAt 参数（公告协议扩展），默认值保持旧行为兼容。
     */
    fun upsert(
        announcementId: String,
        title: String,
        content: String,
        priority: Int = 0,
        expiresAt: Long = 0,
        requiresAck: Boolean = false,
        acknowledgedAt: Long = 0,
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
                put("requires_ack", if (requiresAck) 1 else 0)
                put("acknowledged_at", acknowledgedAt)
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
     * [TASK-OPT-12-P1] 结果新增 requiresAck / acknowledgedAt 字段（公告协议扩展）。
     *
     * @param passphrase 数据库密码
     * @return 公告列表
     */
    fun getAllActive(passphrase: ByteArray): List<Map<String, Any?>> {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val nowTimestamp = System.currentTimeMillis() / 1000
            val cursor = db.rawQuery(
                """SELECT announcement_id, title, content, priority, is_read,
                          requires_ack, acknowledged_at, created_at, expires_at
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
                        "requiresAck" to (it.getInt(5) == 1),
                        "acknowledgedAt" to it.getLong(6),
                        "createdAt" to it.getLong(7),
                        "expiresAt" to it.getLong(8)
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
     * [TASK-OPT-12-P1] 标记公告为家长已确认（紧急公告回执）
     *
     * 记录确认回执时间戳（Unix 秒），同时置为已读。
     * 供 announcement_ack 消息流程使用。
     */
    fun markAcknowledged(announcementId: String, passphrase: ByteArray) {
        val db = dbHelper.getWritable(passphrase)
        try {
            val values = ContentValues().apply {
                put("is_read", 1)
                put("acknowledged_at", System.currentTimeMillis() / 1000)
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
