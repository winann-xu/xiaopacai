package com.xiaopacai.child.p2p

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.MainActivity
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.data.database.AnnouncementDao
import com.xiaopacai.child.data.database.AppCategoryDao
import com.xiaopacai.child.data.database.UsageRecordDao
import com.xiaopacai.child.service.DiagnosticsCollector
import com.xiaopacai.child.ui.overlay.AnnouncementOverlayActivity
import com.xiaopacai.child.util.DbPassphraseProvider
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
     *
     * [TASK-OPT-12-P2] 每轮同步同时补传缓存的诊断报告（需求5：重连补传）。
     */
    fun start() {
        stop()
        syncJob = scope.launch {
            delay(10_000L)  // 初始延迟 10 秒
            while (isActive) {
                try {
                    // [TASK-OPT-12-P2] 补传未上报的诊断报告（未连接时自动跳过）
                    DiagnosticsCollector.flushPending(context)
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

            // [TASK-OPT-12-P2] 应用分类下发合并（需求1）：manual 覆盖本地默认分类
            val categoriesArray = message.payload["app_categories"]?.toString()
                ?.let { JSONArray(it) }
            if (categoriesArray != null) {
                val appCategoryDao = AppCategoryDao(XiaopacaiApp.instance.database)
                var categoryCount = 0
                for (i in 0 until categoriesArray.length()) {
                    val obj = categoriesArray.getJSONObject(i)
                    val packageName = obj.optString("packageName", "")
                    if (packageName.isBlank()) continue
                    appCategoryDao.upsertManual(
                        packageName = packageName,
                        appName = obj.optString("appName", packageName),
                        category = obj.optString("category", "other"),
                        passphrase = passphrase
                    )
                    categoryCount++
                }
                Log.i(TAG, "已合并 $categoryCount 条应用分类（manual）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "策略更新处理失败: ${e.message}", e)
        }
    }

    /**
     * 处理公告推送消息
     *
     * [TASK-OPT-12-P1] 扩展解析 requires_ack（紧急公告需确认）与 acknowledged_at（确认回执时间）。
     * [TASK-OPT-12-P2] 公告即时展示（需求4）：
     * - 紧急公告（priority>=2 且 requires_ack 且未确认）→ 全屏置顶 AnnouncementOverlayActivity
     * - 普通公告 → 立即发系统通知（不再仅靠角标/主动查看）
     */
    private fun handleAnnouncementPush(message: P2PMessage) {
        try {
            val passphrase = getPassphrase()
            val announcementsArray = message.payload["announcements"]?.toString()
                ?.let { JSONArray(it) } ?: return

            var count = 0
            for (i in 0 until announcementsArray.length()) {
                val obj = announcementsArray.getJSONObject(i)
                val announcementId = obj.optString("id", UUID.randomUUID().toString())
                val title = obj.optString("title", "")
                val content = obj.optString("content", "")
                val priority = obj.optInt("priority", 0)
                val requiresAck = obj.optBoolean("requires_ack", false)

                // 重推场景保留既有确认状态（CONFLICT_REPLACE 会覆盖，先查后写）
                var acknowledgedAt = obj.optLong("acknowledged_at", 0)
                if (acknowledgedAt <= 0 && announcementDao.isAcknowledged(announcementId, passphrase)) {
                    acknowledgedAt = announcementDao.getAcknowledgedAt(announcementId, passphrase)
                }

                announcementDao.upsert(
                    announcementId = announcementId,
                    title = title,
                    content = content,
                    priority = priority,
                    requiresAck = requiresAck,
                    acknowledgedAt = acknowledgedAt,
                    expiresAt = obj.optLong("expires_at", 0),
                    passphrase = passphrase
                )
                count++

                // [TASK-OPT-12-P2] 即时展示逻辑
                if (priority >= 2 && requiresAck && !announcementDao.isAcknowledged(announcementId, passphrase)) {
                    // 紧急公告：全屏置顶覆盖层（锁屏亮屏 + 需确认 + 防绕过）
                    AnnouncementOverlayActivity.launch(context, announcementId, title, content)
                } else {
                    // 普通公告：立即发系统通知
                    sendAnnouncementNotification(title, content)
                }
            }
            Log.i(TAG, "已接收 $count 条公告")
        } catch (e: Exception) {
            Log.e(TAG, "公告推送处理失败: ${e.message}", e)
        }
    }

    /**
     * [TASK-OPT-12-P2] 发送普通公告系统通知（点击打开应用）
     */
    private fun sendAnnouncementNotification(title: String, content: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, XiaopacaiApp.CHANNEL_ANNOUNCEMENT)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "发送公告通知失败: ${e.message}")
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
