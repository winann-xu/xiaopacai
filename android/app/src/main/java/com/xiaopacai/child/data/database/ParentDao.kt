package com.xiaopacai.child.data.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.p2p.ChildDeviceInfo
import com.xiaopacai.child.util.DbPassphraseProvider
import net.sqlcipher.database.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * [TASK-ROLE-P2] 家长端数据访问对象
 *
 * 封装家长端四张表（device_registry / parent_policies / parent_announcements / parent_usage_summary）的 CRUD 操作。
 * 所有数据库操作均通过 SQLCipher 加密连接。
 */
object ParentDao {

    private const val TAG = "ParentDao"

    // ==================== 数据库获取 ====================

    private fun getDb(context: Context): SQLiteDatabase {
        val passphrase = DbPassphraseProvider.getPassphrase(context)
        return XiaopacaiApp.instance.database.getWritable(passphrase)
    }

    // ==================== 设备管理 ====================

    /**
     * 获取所有已注册设备列表
     */
    fun getDevices(context: Context): List<ChildDeviceInfo> {
        val list = mutableListOf<ChildDeviceInfo>()
        try {
            val db = getDb(context)
            val cursor = db.rawQuery(
                """SELECT device_id, device_name, cert_fingerprint, last_ip, last_connected_at, is_active
                   FROM device_registry ORDER BY last_connected_at DESC""",
                emptyArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(ChildDeviceInfo(
                        deviceId = it.getString(0),
                        deviceName = it.getString(1),
                        ip = it.getString(3),
                        certFingerprint = it.getString(2),
                        lastSeen = it.getLong(4) * 1000  // 秒转毫秒
                    ))
                }
            }
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "获取设备列表失败: ${e.message}")
        }
        return list
    }

    /**
     * 解绑（标记设备为非活跃）
     */
    fun unbindDevice(context: Context, deviceId: String): Boolean {
        return try {
            val db = getDb(context)
            db.execSQL(
                "UPDATE device_registry SET is_active = 0 WHERE device_id = ?",
                arrayOf(deviceId)
            )
            db.close()
            Log.i(TAG, "设备已解绑: $deviceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "解绑设备失败: ${e.message}")
            false
        }
    }

    // ==================== 策略管理 ====================

    /**
     * 获取所有策略（按类型分组）
     */
    fun getPolicies(context: Context, deviceId: String = ""): JSONArray {
        val arr = JSONArray()
        try {
            val db = getDb(context)
            val whereClause = if (deviceId.isNotEmpty())
                "WHERE target_device_id = ? OR target_device_id = ''" else ""
            val whereArgs = if (deviceId.isNotEmpty()) arrayOf(deviceId) else emptyArray()

            val cursor = db.rawQuery(
                """SELECT policy_id, policy_type, policy_name, policy_data, target_device_id, is_active, version
                   FROM parent_policies $whereClause ORDER BY policy_type, created_at DESC""",
                whereArgs
            )
            cursor.use {
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("policyId", it.getString(0))
                    obj.put("policyType", it.getString(1))
                    obj.put("policyName", it.getString(2))
                    obj.put("policyData", JSONObject(it.getString(3)))
                    obj.put("targetDeviceId", it.getString(4))
                    obj.put("isActive", it.getInt(5) == 1)
                    obj.put("version", it.getInt(6))
                    arr.put(obj)
                }
            }
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "获取策略列表失败: ${e.message}")
        }
        return arr
    }

    /**
     * 保存或更新策略
     */
    fun savePolicy(
        context: Context,
        policyId: String?,
        policyType: String,
        policyName: String,
        policyData: JSONObject,
        targetDeviceId: String = "",
        isActive: Boolean = true
    ): String {
        val id = policyId ?: UUID.randomUUID().toString()
        try {
            val db = getDb(context)
            val now = System.currentTimeMillis() / 1000

            // 检查是否存在
            val cursor = db.rawQuery(
                "SELECT id FROM parent_policies WHERE policy_id = ?",
                arrayOf(id)
            )
            val exists = cursor.use { it.count > 0 }
            cursor.close()

            if (exists) {
                db.execSQL("""
                    UPDATE parent_policies
                    SET policy_name = ?, policy_data = ?, target_device_id = ?,
                        is_active = ?, version = version + 1, updated_at = ?
                    WHERE policy_id = ?
                """.trimIndent(), arrayOf(
                    policyName, policyData.toString(), targetDeviceId,
                    if (isActive) 1 else 0, now, id
                ))
            } else {
                db.execSQL("""
                    INSERT INTO parent_policies
                    (policy_id, policy_type, policy_name, policy_data, target_device_id, is_active, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                """.trimIndent(), arrayOf(
                    id, policyType, policyName, policyData.toString(),
                    targetDeviceId, if (isActive) 1 else 0, now, now
                ))
            }
            db.close()
            Log.i(TAG, "策略已保存: $id ($policyName)")
        } catch (e: Exception) {
            Log.e(TAG, "保存策略失败: ${e.message}")
        }
        return id
    }

    /**
     * 删除策略
     */
    fun deletePolicy(context: Context, policyId: String): Boolean {
        return try {
            val db = getDb(context)
            db.execSQL("DELETE FROM parent_policies WHERE policy_id = ?", arrayOf(policyId))
            db.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除策略失败: ${e.message}")
            false
        }
    }

    // ==================== 公告管理 ====================

    /**
     * 获取所有公告
     */
    fun getAnnouncements(context: Context): JSONArray {
        val arr = JSONArray()
        try {
            val db = getDb(context)
            val cursor = db.rawQuery(
                """SELECT announcement_id, title, content, priority, status, target_device_id,
                          valid_from, valid_until, created_at, updated_at
                   FROM parent_announcements ORDER BY created_at DESC""",
                emptyArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("announcementId", it.getString(0))
                    obj.put("title", it.getString(1))
                    obj.put("content", it.getString(2))
                    obj.put("priority", it.getInt(3))
                    obj.put("status", it.getString(4))
                    obj.put("targetDeviceId", it.getString(5))
                    obj.put("validFrom", it.getLong(6))
                    obj.put("validUntil", it.getLong(7))
                    obj.put("createdAt", it.getLong(8))
                    obj.put("updatedAt", it.getLong(9))
                    arr.put(obj)
                }
            }
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "获取公告列表失败: ${e.message}")
        }
        return arr
    }

    /**
     * 保存或更新公告
     */
    fun saveAnnouncement(
        context: Context,
        announcementId: String?,
        title: String,
        content: String,
        priority: Int,
        status: String,
        targetDeviceId: String = "",
        validFrom: Long = 0,
        validUntil: Long = 0
    ): String {
        val id = announcementId ?: UUID.randomUUID().toString()
        try {
            val db = getDb(context)
            val now = System.currentTimeMillis() / 1000

            val cursor = db.rawQuery(
                "SELECT id FROM parent_announcements WHERE announcement_id = ?",
                arrayOf(id)
            )
            val exists = cursor.use { it.count > 0 }
            cursor.close()

            if (exists) {
                db.execSQL("""
                    UPDATE parent_announcements
                    SET title = ?, content = ?, priority = ?, status = ?,
                        target_device_id = ?, valid_from = ?, valid_until = ?, updated_at = ?
                    WHERE announcement_id = ?
                """.trimIndent(), arrayOf(
                    title, content, priority, status,
                    targetDeviceId, validFrom, validUntil, now, id
                ))
            } else {
                db.execSQL("""
                    INSERT INTO parent_announcements
                    (announcement_id, title, content, priority, status, target_device_id,
                     valid_from, valid_until, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), arrayOf(
                    id, title, content, priority, status,
                    targetDeviceId, validFrom, validUntil, now, now
                ))
            }
            db.close()
            Log.i(TAG, "公告已保存: $id ($title)")
        } catch (e: Exception) {
            Log.e(TAG, "保存公告失败: ${e.message}")
        }
        return id
    }

    /**
     * 发布公告（状态改为 published）
     */
    fun publishAnnouncement(context: Context, announcementId: String): Boolean {
        return try {
            val db = getDb(context)
            val now = System.currentTimeMillis() / 1000
            db.execSQL(
                "UPDATE parent_announcements SET status = 'published', updated_at = ? WHERE announcement_id = ?",
                arrayOf(now, announcementId)
            )
            db.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "发布公告失败: ${e.message}")
            false
        }
    }

    /**
     * 撤回公告（状态改为 revoked）
     */
    fun revokeAnnouncement(context: Context, announcementId: String): Boolean {
        return try {
            val db = getDb(context)
            val now = System.currentTimeMillis() / 1000
            db.execSQL(
                "UPDATE parent_announcements SET status = 'revoked', updated_at = ? WHERE announcement_id = ?",
                arrayOf(now, announcementId)
            )
            db.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "撤回公告失败: ${e.message}")
            false
        }
    }

    /**
     * 删除公告
     */
    fun deleteAnnouncement(context: Context, announcementId: String): Boolean {
        return try {
            val db = getDb(context)
            db.execSQL("DELETE FROM parent_announcements WHERE announcement_id = ?", arrayOf(announcementId))
            db.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除公告失败: ${e.message}")
            false
        }
    }

    // ==================== 使用报告 ====================

    /**
     * 获取指定设备的使用汇总（按日期范围）
     */
    fun getUsageSummary(
        context: Context,
        deviceId: String = "",
        fromDate: String = "",
        toDate: String = ""
    ): JSONArray {
        val arr = JSONArray()
        try {
            val db = getDb(context)
            val conditions = mutableListOf<String>()
            val args = mutableListOf<String>()

            if (deviceId.isNotEmpty()) {
                conditions.add("device_id = ?")
                args.add(deviceId)
            }
            if (fromDate.isNotEmpty()) {
                conditions.add("date >= ?")
                args.add(fromDate)
            }
            if (toDate.isNotEmpty()) {
                conditions.add("date <= ?")
                args.add(toDate)
            }

            val whereClause = if (conditions.isNotEmpty())
                "WHERE ${conditions.joinToString(" AND ")}" else ""

            val cursor = db.rawQuery(
                """SELECT device_id, package_name, app_name, date, total_minutes, category
                   FROM parent_usage_summary $whereClause
                   ORDER BY date DESC, total_minutes DESC""",
                args.toTypedArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("deviceId", it.getString(0))
                    obj.put("packageName", it.getString(1))
                    obj.put("appName", it.getString(2))
                    obj.put("date", it.getString(3))
                    obj.put("totalMinutes", it.getLong(4))
                    obj.put("category", it.getString(5))
                    arr.put(obj)
                }
            }
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "获取使用汇总失败: ${e.message}")
        }
        return arr
    }

    /**
     * 获取每日总时长（按日期汇总）
     */
    fun getDailyTotals(
        context: Context,
        deviceId: String = "",
        fromDate: String = "",
        toDate: String = ""
    ): JSONArray {
        val arr = JSONArray()
        try {
            val db = getDb(context)
            val conditions = mutableListOf<String>()
            val args = mutableListOf<String>()

            if (deviceId.isNotEmpty()) {
                conditions.add("device_id = ?")
                args.add(deviceId)
            }
            if (fromDate.isNotEmpty()) {
                conditions.add("date >= ?")
                args.add(fromDate)
            }
            if (toDate.isNotEmpty()) {
                conditions.add("date <= ?")
                args.add(toDate)
            }

            val whereClause = if (conditions.isNotEmpty())
                "WHERE ${conditions.joinToString(" AND ")}" else ""

            val cursor = db.rawQuery(
                """SELECT date, SUM(total_minutes) as daily_total,
                          SUM(CASE WHEN category='game' THEN total_minutes ELSE 0 END) as game_minutes,
                          SUM(CASE WHEN category='study' THEN total_minutes ELSE 0 END) as study_minutes
                   FROM parent_usage_summary $whereClause
                   GROUP BY date ORDER BY date DESC LIMIT 30""",
                args.toTypedArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("date", it.getString(0))
                    obj.put("totalMinutes", it.getLong(1))
                    obj.put("gameMinutes", it.getLong(2))
                    obj.put("studyMinutes", it.getLong(3))
                    arr.put(obj)
                }
            }
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "获取每日汇总失败: ${e.message}")
        }
        return arr
    }

    /**
     * 获取分类使用占比（用于饼图）
     */
    fun getCategoryBreakdown(
        context: Context,
        deviceId: String = "",
        fromDate: String = ""
    ): JSONArray {
        val arr = JSONArray()
        try {
            val db = getDb(context)
            val conditions = mutableListOf<String>()
            val args = mutableListOf<String>()

            if (deviceId.isNotEmpty()) {
                conditions.add("device_id = ?")
                args.add(deviceId)
            }
            if (fromDate.isNotEmpty()) {
                conditions.add("date >= ?")
                args.add(fromDate)
            }

            val whereClause = if (conditions.isNotEmpty())
                "WHERE ${conditions.joinToString(" AND ")}" else ""

            val cursor = db.rawQuery(
                """SELECT category, SUM(total_minutes) as cat_total
                   FROM parent_usage_summary $whereClause
                   GROUP BY category ORDER BY cat_total DESC""",
                args.toTypedArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("category", it.getString(0))
                    obj.put("totalMinutes", it.getLong(1))
                    arr.put(obj)
                }
            }
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "获取分类统计失败: ${e.message}")
        }
        return arr
    }
}
