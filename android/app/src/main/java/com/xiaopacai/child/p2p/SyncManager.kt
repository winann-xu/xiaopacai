package com.xiaopacai.child.p2p

import android.content.Context
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.data.database.AnnouncementDao
import com.xiaopacai.child.util.DbPassphraseProvider
import com.xiaopacai.child.data.database.UsageRecordDao
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * [TASK-D2-05] P2P 数据同步管理器（儿童端）
 *
 * 负责与家长端的双向数据同步：
 * 1. 上行（儿童→家长）：使用时长报告、心跳/状态
 * 2. 下行（家长→儿童）：策略配置、公告、指令
 *
 * 同步策略：
 * - 自动同步：每次连接建立后 + 每 5 分钟定时
 * - 增量同步：仅同步未标记的数据（sync_status = 0）
 * - 断网缓存：所有数据本地加密存储，恢复连接后补发
 */
class SyncManager(
    private val context: Context,
    private val connectionService: P2PConnectionService,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "SyncManager"
        /** 定时同步间隔：5 分钟 */
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
        /** 最大单次同步记录数 */
        private const val MAX_SYNC_BATCH = 200
    }

    private val usageDao = UsageRecordDao(XiaopacaiApp.instance.database)
    private val announcementDao = AnnouncementDao(XiaopacaiApp.instance.database)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var syncJob: Job? = null

    /**
     * 启动定时同步循环
     */
    fun start() {
        stop()
        syncJob = scope.launch {
            delay(10_000L)  // 初始延迟 10 秒
            while (isActive) {
                try {
                    syncUsageReports()
                    delay(SYNC_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "同步失败: ${e.message}", e)
                    delay(30_000L)  // 失败后退避 30s
                }
            }
        }
        Log.i(TAG, "同步管理器已启动")
    }

    /**
     * 停止同步
     */
    fun stop() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * 处理收到的 P2P 消息
     * 由 P2PConnectionService 收到消息时回调
     */
    fun handleReceivedMessage(message: P2PMessage) {
        when (message.type) {
            "policy_update" -> handlePolicyUpdate(message)
            "announcement_push" -> handleAnnouncementPush(message)
            "sync_ack" -> handleSyncAck(message)
            "heartbeat_ack" -> { /* 心跳 ACK 由连接层处理 */ }
            else -> Log.d(TAG, "未处理的消息类型: ${message.type}")
        }
    }

    // ==================== 上行同步（儿童→家长） ====================

    /**
     * 同步使用时长报告到家长端
     * P2P-FIX: 仅在连接已建立时发送，仅在发送成功后标记已同步
     */
    suspend fun syncUsageReports() {
        // P2P-FIX: 连接门卫 — 未连接时静默跳过，不标记已同步
        if (connectionService.connectionState.value != P2PConnectionState.CONNECTED) {
            Log.d(TAG, "未连接到家长端，跳过同步")
            return
        }

        val passphrase = getPassphrase()

        // 1. 获取未同步记录
        val unsyncedRecords = usageDao.getUnsyncedRecords(MAX_SYNC_BATCH, passphrase)
        if (unsyncedRecords.isEmpty()) {
            Log.d(TAG, "无待同步记录")
            return
        }

        // 2. 构建同步消息
        val recordsArray = JSONArray()
        val ids = mutableListOf<Long>()

        unsyncedRecords.forEach { record ->
            val obj = JSONObject()
            obj.put("packageName", record["packageName"]?.toString() ?: "")
            obj.put("appName", record["appName"]?.toString() ?: "")
            obj.put("date", record["date"]?.toString() ?: "")
            obj.put("totalMinutes", (record["totalMinutes"] as? Long) ?: 0L)
            obj.put("category", record["category"]?.toString() ?: "other")
            recordsArray.put(obj)
            (record["id"] as? Long)?.let { ids.add(it) }
        }

        // 3. 发送消息
        val message = P2PMessage(
            type = "usage_report",
            payload = mapOf(
                "deviceId" to getDeviceId(),
                "records" to recordsArray.toString(),
                "timestamp" to (System.currentTimeMillis() / 1000)
            )
        )

        // P2P-FIX: 仅发送成功后才标记已同步，防止静默丢数据
        val sent = connectionService.sendMessage(message)
        if (sent) {
            usageDao.markAsSynced(ids, passphrase)
            Log.i(TAG, "已同步 ${unsyncedRecords.size} 条使用记录")
        } else {
            Log.w(TAG, "同步发送失败，${unsyncedRecords.size} 条记录将在下次重试")
        }
    }

    // ==================== 下行同步（家长→儿童） ====================

    /**
     * 处理策略更新消息
     */
    private fun handlePolicyUpdate(message: P2PMessage) {
        try {
            val passphrase = getPassphrase()
            val policiesArray = message.payload["policies"]?.toString()
                ?.let { JSONArray(it) } ?: return

            val db = XiaopacaiApp.instance.database.getWritable(passphrase)
            try {
                for (i in 0 until policiesArray.length()) {
                    val policyJson = policiesArray.getString(i)
                    val policyObj = JSONObject(policyJson)
                    val policyType = policyObj.optString("policyType", "")

                    // 更新策略缓存表
                    db.execSQL(
                        """INSERT OR REPLACE INTO policy_cache
                           (policy_type, policy_data, version, applied_at)
                           VALUES (?, ?, ?, ?)""",
                        arrayOf(
                            policyType,
                            policyJson,
                            policyObj.optInt("version", 1).toString(),
                            (System.currentTimeMillis() / 1000).toString()
                        )
                    )
                }
                Log.i(TAG, "已接收 ${policiesArray.length()} 条策略")
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "策略更新处理失败: ${e.message}", e)
        }
    }

    /**
     * 处理公告推送消息
     *
     * [TASK-OPT-12-P1] 扩展解析 requires_ack（紧急公告需确认）与 acknowledged_at（确认回执时间）。
     */
    private fun handleAnnouncementPush(message: P2PMessage) {
        try {
            val passphrase = getPassphrase()
            val announcementsArray = message.payload["announcements"]?.toString()
                ?.let { JSONArray(it) } ?: return

            var count = 0
            for (i in 0 until announcementsArray.length()) {
                val obj = announcementsArray.getJSONObject(i)
                announcementDao.upsert(
                    announcementId = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title", ""),
                    content = obj.optString("content", ""),
                    priority = obj.optInt("priority", 0),
                    requiresAck = obj.optBoolean("requires_ack", false),
                    acknowledgedAt = obj.optLong("acknowledged_at", 0),
                    expiresAt = obj.optLong("expires_at", 0),
                    passphrase = passphrase
                )
                // [TASK-OPT-4] 公告到达直接展示：紧急公告全屏置顶，其余系统通知直达
                showAnnouncementImmediately(
                    id = obj.optString("id", ""),
                    title = obj.optString("title", "家长公告"),
                    content = obj.optString("content", ""),
                    priority = obj.optInt("priority", 0)
                )
                count++
            }
            Log.i(TAG, "已接收 $count 条公告")
        } catch (e: Exception) {
            Log.e(TAG, "公告推送处理失败: ${e.message}", e)
        }
    }

    /**
     * [TASK-OPT-4] 公告立即展示：
     * - priority >= 2（紧急）：全屏置顶 Activity，必须点击确认
     * - 其余：系统高优先级通知（内容直接可见，无需主动进入 APP 查看）
     */
    private fun showAnnouncementImmediately(
        id: String,
        title: String,
        content: String,
        priority: Int
    ) {
        try {
            if (priority >= 2) {
                // [TASK-OPT-4] 紧急公告：优先经无障碍服务（BAL 特权）直接启动全屏界面，
                // 无障碍不可用时回退 full-screen intent 通知
                val started = com.xiaopacai.child.service.GuardianAccessibilityService
                    .showAnnouncementOverlay(id, title, content)
                if (started) {
                    Log.i(TAG, "紧急公告已全屏置顶（无障碍通道）: $title")
                } else {
                    val intent = android.content.Intent(context, com.xiaopacai.child.ui.overlay.AnnouncementOverlayActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("announcement_id", id)
                        putExtra("title", title)
                        putExtra("content", content)
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        context,
                        id.hashCode(),
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val notificationManager = context.getSystemService(
                        android.content.Context.NOTIFICATION_SERVICE
                    ) as android.app.NotificationManager
                    val notification = androidx.core.app.NotificationCompat.Builder(
                        context, com.xiaopacai.child.XiaopacaiApp.CHANNEL_ANNOUNCEMENT
                    )
                        .setContentTitle(title)
                        .setContentText(content)
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(content))
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                        .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                        .setFullScreenIntent(pendingIntent, true)
                        .setAutoCancel(false)
                        .build()
                    notificationManager.notify(id.hashCode(), notification)
                    Log.i(TAG, "紧急公告已全屏置顶（full-screen intent 回退）: $title")
                }
            } else {
                val notificationManager = context.getSystemService(
                    android.content.Context.NOTIFICATION_SERVICE
                ) as android.app.NotificationManager
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context,
                    id.hashCode(),
                    android.content.Intent(context, com.xiaopacai.child.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val notification = androidx.core.app.NotificationCompat.Builder(
                    context, com.xiaopacai.child.XiaopacaiApp.CHANNEL_ANNOUNCEMENT
                )
                    .setContentTitle(title)
                    .setContentText(content)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(content))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
                notificationManager.notify(id.hashCode(), notification)
                Log.i(TAG, "公告通知已直达: $title")
            }
        } catch (e: Exception) {
            Log.e(TAG, "公告立即展示失败: ${e.message}")
        }
    }

    /**
     * 处理同步确认消息
     */
    private fun handleSyncAck(message: P2PMessage) {
        val syncedCount = (message.payload["syncedCount"] as? Number)?.toInt() ?: 0
        Log.d(TAG, "家长端确认同步: $syncedCount 条")
    }

    // ==================== 工具方法 ====================

    /**
     * 获取设备 ID
     */
    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    /**
     * 获取数据库密码 [TASK-D3-05]
     */
    private fun getPassphrase(): ByteArray {
        return DbPassphraseProvider.getPassphrase(context)
    }
}
