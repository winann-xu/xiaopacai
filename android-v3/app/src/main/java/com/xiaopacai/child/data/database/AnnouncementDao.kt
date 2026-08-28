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
     * [TASK-PRELAUNCH-P3] 改为合并式更新（见 docs/adr/0004，Web 侧对应实现）：
     * - 内容哈希不变 → 保留 is_read/acknowledged_at/displayed_at，仅更新有效期与送达次数（不重复打扰）
     * - 内容哈希变化 → 更新正文并重置显示/确认状态（允许重新提示）
     * - 旧库无哈希记录（last_push_hash=''）：已读/已确认视为内容未变；未读视为首次送达
     *
     * @param contentHash 服务端下发的内容哈希（sha256 前 16 hex，可能为空）
     * @return "new"（新公告，应展示）/ "changed"（内容变化，应重新提示）/ "unchanged"（去重命中，不展示）
     */
    fun upsert(
        announcementId: String,
        title: String,
        content: String,
        priority: Int = 0,
        expiresAt: Long = 0,
        requiresAck: Boolean = false,
        contentHash: String = "",
        passphrase: ByteArray
    ): String {
        val db = dbHelper.getWritable(passphrase)
        return try {
            // 服务端未带哈希时本地兜底计算（title|content|priority）
            val hash = contentHash.ifBlank {
                computeLocalHash(title, content, priority)
            }

            // 读取既有行：is_read/acknowledged_at/displayed_at/last_push_hash
            var existed = false
            var existingRead = 0
            var existingAcked = 0L
            var existingDisplayed = 0L
            var existingHash = ""
            db.rawQuery(
                """SELECT is_read, acknowledged_at, displayed_at, last_push_hash
                   FROM announcements WHERE announcement_id = ?""",
                arrayOf(announcementId)
            ).use { c ->
                if (c.moveToFirst()) {
                    existed = true
                    existingRead = c.getInt(0)
                    existingAcked = c.getLong(1)
                    existingDisplayed = c.getLong(2)
                    existingHash = c.getString(3)
                }
            }

            if (!existed) {
                // 新公告：完整插入，默认未读未显示
                val values = ContentValues().apply {
                    put("announcement_id", announcementId)
                    put("title", title)
                    put("content", content)
                    put("priority", priority)
                    put("is_read", 0)
                    put("requires_ack", if (requiresAck) 1 else 0)
                    put("acknowledged_at", 0)
                    put("displayed_at", 0)
                    put("last_push_hash", hash)
                    put("delivered_count", 1)
                    put("expires_at", expiresAt)
                }
                db.insertWithOnConflict(
                    "announcements",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                return "new"
            }

            // 旧库迁移边界：无哈希记录的行，以已读/已确认状态推断内容未变
            val hashChanged = existingHash.isNotEmpty() && existingHash != hash
            val legacyUnseen = existingHash.isEmpty() &&
                    existingRead == 0 && existingAcked == 0L

            if (hashChanged || legacyUnseen) {
                // 内容变化（或旧库未读视为首次送达）：更新正文并重置状态，允许重新提示
                val values = ContentValues().apply {
                    put("title", title)
                    put("content", content)
                    put("priority", priority)
                    put("is_read", 0)
                    put("requires_ack", if (requiresAck) 1 else 0)
                    put("acknowledged_at", 0)   // 旧内容的确认对新内容不生效
                    put("displayed_at", 0)      // 重置显示标记，允许重新弹窗/置顶
                    put("last_push_hash", hash)
                    put("delivered_count", existingCount(db, announcementId) + 1)
                    put("expires_at", expiresAt)
                }
                db.update("announcements", values, "announcement_id = ?", arrayOf(announcementId))
                return "changed"
            }

            // 去重命中：内容未变，仅更新有效期与送达次数，保留全部状态
            val values = ContentValues().apply {
                put("last_push_hash", hash)     // 旧库行补记哈希
                put("priority", priority)       // 服务端优先级始终权威（修复旧库 priority 错位）
                put("requires_ack", if (requiresAck) 1 else 0)
                put("delivered_count", existingCount(db, announcementId) + 1)
                put("expires_at", expiresAt)
            }
            db.update("announcements", values, "announcement_id = ?", arrayOf(announcementId))
            return "unchanged"
        } finally {
        }
    }

    /**
     * [TASK-PRELAUNCH-P3] 查询送达次数（合并更新时自增用）
     */
    private fun existingCount(db: SQLiteDatabase, announcementId: String): Int {
        db.rawQuery(
            "SELECT delivered_count FROM announcements WHERE announcement_id = ?",
            arrayOf(announcementId)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * [TASK-PRELAUNCH-P3] 本地兜底内容哈希（与 Web ComputeContentHash 同口径：title\ncontent\npriority）
     */
    private fun computeLocalHash(title: String, content: String, priority: Int): String {
        val raw = "$title\n$content\n$priority"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * [TASK-PRELAUNCH-P3] 标记公告已显示（仅首次写入，重复推送不覆盖首次显示时间）
     */
    fun markDisplayed(announcementId: String, passphrase: ByteArray) {
        val db = dbHelper.getWritable(passphrase)
        try {
            val now = System.currentTimeMillis() / 1000
            db.execSQL(
                """UPDATE announcements SET displayed_at =
                   CASE WHEN displayed_at = 0 THEN ? ELSE displayed_at END
                   WHERE announcement_id = ?""",
                arrayOf(now.toString(), announcementId)
            )
        } finally {
        }
    }

    /**
     * [TASK-PRELAUNCH-P3] 撤回公告：置过期（从活动列表消失），保留行记录用于去重
     * 重新发布同一内容时哈希不变 → 不再重复打扰；记录仍保留已读/已确认状态
     */
    fun revokeLocally(announcementId: String, passphrase: ByteArray) {
        val db = dbHelper.getWritable(passphrase)
        try {
            val now = System.currentTimeMillis() / 1000
            val values = ContentValues().apply { put("expires_at", now) }
            db.update("announcements", values, "announcement_id = ?", arrayOf(announcementId))
        } finally {
        }
    }

    /**
     * [TASK-MILESTONE-V3] B5 批量删除本地公告：服务端删除公告后下发清除指令/墓碑，
     * 本地行一并删除（多端一致；撤回只置过期、删除则彻底移除）
     *
     * @return 实际删除行数
     */
    fun deleteByIds(announcementIds: List<String>, passphrase: ByteArray): Int {
        if (announcementIds.isEmpty()) return 0
        val db = dbHelper.getWritable(passphrase)
        return try {
            val placeholders = announcementIds.joinToString(",") { "?" }
            db.delete(
                "announcements",
                "announcement_id IN ($placeholders)",
                announcementIds.toTypedArray()
            )
        } finally {
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
        }
    }

    /**
     * [TASK-OPT-12-P2] 查询公告是否已被家长确认（紧急公告回执状态）
     */
    fun isAcknowledged(announcementId: String, passphrase: ByteArray): Boolean {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val cursor = db.rawQuery(
                "SELECT acknowledged_at FROM announcements WHERE announcement_id = ?",
                arrayOf(announcementId)
            )
            cursor.use { it.moveToFirst() && it.getLong(0) > 0 }
        } finally {
        }
    }

    /**
     * [TASK-OPT-12-P2] 查询公告既有确认回执时间戳（重推时保留，防止覆盖已确认状态）
     */
    fun getAcknowledgedAt(announcementId: String, passphrase: ByteArray): Long {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val cursor = db.rawQuery(
                "SELECT acknowledged_at FROM announcements WHERE announcement_id = ?",
                arrayOf(announcementId)
            )
            cursor.use { if (it.moveToFirst()) it.getLong(0) else 0L }
        } finally {
        }
    }

    /**
     * [TASK-OPT-12-P2] 查询一条未确认的紧急公告（优先级>=2 且需确认且未回执）
     *
     * @return mapOf("id"/"title"/"content")，无则 null
     */
    fun getFirstUnacknowledgedUrgent(passphrase: ByteArray): Map<String, String>? {
        val db = dbHelper.getReadable(passphrase)
        return try {
            val nowTimestamp = System.currentTimeMillis() / 1000
            val cursor = db.rawQuery(
                """SELECT announcement_id, title, content FROM announcements
                   WHERE priority >= 2 AND requires_ack = 1 AND acknowledged_at = 0
                   AND (expires_at = 0 OR expires_at > ?)
                   ORDER BY priority DESC, created_at DESC LIMIT 1""",
                arrayOf(nowTimestamp.toString())
            )
            cursor.use {
                if (it.moveToFirst()) {
                    mapOf(
                        "id" to it.getString(0),
                        "title" to it.getString(1),
                        "content" to it.getString(2)
                    )
                } else null
            }
        } finally {
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
        }
    }
}
