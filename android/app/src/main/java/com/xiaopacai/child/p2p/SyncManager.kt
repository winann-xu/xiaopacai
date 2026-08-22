package com.xiaopacai.child.p2p

import android.content.Context
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.data.database.AnnouncementDao
import com.xiaopacai.child.util.DbPassphraseProvider
import com.xiaopacai.child.data.database.UsageRecordDao
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.service.UsageStatsCollector
import com.xiaopacai.child.util.UpdateManager
import com.xiaopacai.child.util.UpdateNotifier
import com.xiaopacai.child.util.UsageStatsHelper
import kotlinx.coroutines.*
import org.json.JSONArray
import com.xiaopacai.child.R
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
                    // [TASK-HARDENING-V1.1.1] Bug1-D：补传守护失守事件与健康度（离线缓存）
                    com.xiaopacai.child.service.GuardDownMonitor.sendPending(context)
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
            // [TASK-MILESTONE-V3] B5 服务端删除公告后的本地清除指令
            "announcement_clear" -> handleAnnouncementClear(message)
            "limit_reset" -> handleLimitReset(message)
            "sync_ack" -> handleSyncAck(message)
            "heartbeat_ack" -> { /* 心跳 ACK 由连接层处理 */ }
            // [TASK-APP-UPDATE-V1] D2：服务端发布新版本后的实时推送
            "update_available" -> handleUpdateAvailable(message)
            else -> Log.d(TAG, "未处理的消息类型: ${message.type}")
        }
    }

    /**
     * [TASK-PRELAUNCH-P4] 处理家长端“重置当日限额”指令（limit_reset）
     * 语义：以收到指令时刻的本地累计时长为偏移，之后“已用”从 0 重新计时；
     * 原始使用记录/报告不受影响（服务端以原始累计 + 偏移显示调整后口径）
     */
    private fun handleLimitReset(message: P2PMessage) {
        try {
            val resetAt = (message.payload["resetAt"] as? Number)?.toLong() ?: 0L
            val passphrase = getPassphrase()
            val today = dateFormat.format(Date())
            // 偏移 = 收到重置指令时本地已累计的当日分钟数
            // [FIX] 用 UsageStatsHelper 实时累计，避免 SQLCipher 连接状态下 DAO 查询
            // 偶发 SQLiteMisuseException（bad parameter）导致重置链路中断
            val offset = UsageStatsHelper.getTodayTotalMinutes(context)

            // 持久化偏移（跨进程/重启保留；日期不匹配时失效）
            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("daily_reset_offset_minutes", offset)
                .putString("daily_reset_offset_date", today)
                .apply()

            // 通知采集器立即按新口径重算（超时锁定同步解除）
            com.xiaopacai.child.service.UsageStatsCollector.applyLimitReset(offset, today)

            Log.i(TAG, "已处理限额重置指令: resetAt=$resetAt, 偏移=${offset}分钟（$today）")
        } catch (e: Exception) {
            Log.e(TAG, "限额重置处理失败: ${e.message}", e)
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
        // [TASK-PRELAUNCH-P4] 携带今日重置偏移（分钟），服务端据此计算调整后“今日已用”
        // [FIX-100] 同时携带儿童端自算的调整后已用，Web 展示/ack 优先采用（最准确口径）
        val message = P2PMessage(
            type = "usage_report",
            payload = mapOf(
                "deviceId" to getDeviceId(),
                "records" to recordsArray.toString(),
                "dailyResetOffsetMinutes" to getDailyResetOffsetMinutes(),
                "todayAdjustedMinutes" to getTodayAdjustedMinutes(),
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
     * [TASK-MILESTONE-V3] A2 客户端版本防线：同 policyType 旧版本不覆盖新版本
     * （服务端版本单调递增，正常链路不会触发；防御重复帧/乱序帧回退策略）
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
                    val version = policyObj.optInt("version", 1)

                    // [TASK-MILESTONE-V3] A2：版本比对，旧帧不覆盖新缓存
                    if (cachedPolicyVersion(db, policyType) >= version) {
                        Log.d(TAG, "策略 $policyType 版本 $version 不高于本地缓存，跳过")
                        continue
                    }

                    // 更新策略缓存表
                    db.execSQL(
                        """INSERT OR REPLACE INTO policy_cache
                           (policy_type, policy_data, version, applied_at)
                           VALUES (?, ?, ?, ?)""",
                        arrayOf(
                            policyType,
                            policyJson,
                            version.toString(),
                            (System.currentTimeMillis() / 1000).toString()
                        )
                    )
                }
                Log.i(TAG, "已接收 ${policiesArray.length()} 条策略")
            } finally {
            }
        } catch (e: Exception) {
            Log.e(TAG, "策略更新处理失败: ${e.message}", e)
        }
    }

    /**
     * [TASK-MILESTONE-V3] A2 读取本地缓存的策略版本（无缓存返回 0）
     */
    private fun cachedPolicyVersion(db: net.sqlcipher.database.SQLiteDatabase, policyType: String): Int {
        db.rawQuery(
            "SELECT version FROM policy_cache WHERE policy_type = ?",
            arrayOf(policyType)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0)?.toIntOrNull() ?: 0 else 0
        }
    }

    /**
     * 处理公告推送消息
     *
     * [TASK-PRELAUNCH-P3] 去重展示（见 docs/adr/0004）：
     * - 撤回：本地置过期、不展示，保留记录用于重新发布去重
     * - 已显示且内容哈希未变 → 不弹窗、不通知、不置顶（upsert 内已保留状态）
     * - 紧急公告已确认 → 不再全屏
     * - 新公告/内容变化 → 展示并写 displayed_at，上报 announcement_displayed
     *
     * [TASK-MILESTONE-V3] B8：紧急未确认公告即使内容未变（upsert=unchanged）也必须重新全屏
     * （重连补推场景，否则确认入口丢失、未确认紧急公告形同失效）
     * [TASK-MILESTONE-V3] 133 修复：单条异常不中断整批（此前一条入库异常导致后续公告
     * 全部跳过且 displayed 回执不上报，服务端 60s 补偿也会继续错过）
     * [TASK-MILESTONE-V3] B5：同步帧可携带 cleared_ids（服务端删除墓碑），清除本地残留
     */
    private fun handleAnnouncementPush(message: P2PMessage) {
        try {
            val passphrase = getPassphrase()
            val action = message.payload["action"]?.toString() ?: ""

            // [TASK-MILESTONE-V3] B5：删除墓碑随同步下发（离线路径；在线路径走 announcement_clear）
            val clearedIdsArray = message.payload["cleared_ids"]?.toString()
                ?.let { JSONArray(it) }
            if (clearedIdsArray != null && clearedIdsArray.length() > 0) {
                val clearedIds = mutableListOf<String>()
                for (i in 0 until clearedIdsArray.length()) {
                    val id = clearedIdsArray.optString(i)
                    if (id.isNotBlank()) clearedIds.add(id)
                }
                val cleared = announcementDao.deleteByIds(clearedIds, passphrase)
                if (cleared > 0)
                    Log.i(TAG, "已按服务端墓碑清除 $cleared 条本地公告")
            }

            val announcementsArray = message.payload["announcements"]?.toString()
                ?.let { JSONArray(it) } ?: return

            var count = 0
            var shownCount = 0
            for (i in 0 until announcementsArray.length()) {
                // [TASK-MILESTONE-V3] 133 修复：单条异常仅跳过该条
                try {
                    val obj = announcementsArray.getJSONObject(i)
                    val id = obj.optString("id", UUID.randomUUID().toString())
                    val priority = obj.optInt("priority", 0)

                    // [TASK-PRELAUNCH-P3] 撤回：仅置过期，不展示；行记录保留（重新发布同内容不重复打扰）
                    if (action == "revoke") {
                        announcementDao.revokeLocally(id, passphrase)
                        count++
                        continue
                    }

                    val result = announcementDao.upsert(
                        announcementId = id,
                        title = obj.optString("title", ""),
                        content = obj.optString("content", ""),
                        priority = priority,
                        requiresAck = obj.optBoolean("requires_ack", false),
                        contentHash = obj.optString("content_hash", ""),
                        expiresAt = obj.optLong("expires_at", 0),
                        passphrase = passphrase
                    )

                    // 去重判定：内容未变（unchanged）→ 不打扰；紧急已确认 → 不再全屏
                    // （内容变化时 upsert 已重置 acknowledged_at，紧急会重新全屏）
                    // [TASK-MILESTONE-V3] B8：紧急公告只要未确认就必须重新全屏，无视 upsert 去重
                    val urgentAcked = priority >= 2 && announcementDao.isAcknowledged(id, passphrase)
                    val shouldShow = if (priority >= 2) !urgentAcked else result != "unchanged"
                    if (shouldShow) {
                        // [TASK-OPT-4] 公告到达直接展示：紧急公告全屏置顶，其余系统通知直达
                        showAnnouncementImmediately(
                            id = id,
                            title = obj.optString("title", "家长公告"),
                            content = obj.optString("content", ""),
                            priority = priority
                        )
                        announcementDao.markDisplayed(id, passphrase)
                        sendAnnouncementDisplayed(id)
                        shownCount++
                    } else {
                        Log.d(TAG, "公告去重跳过: $id（内容未变=${result == "unchanged"}, 已确认=$urgentAcked）")
                    }
                    count++
                } catch (e: Exception) {
                    Log.e(TAG, "公告条目处理失败（跳过该条）: ${e.message}")
                }
            }
            Log.i(TAG, "已接收 $count 条公告（展示 $shownCount 条，去重/已确认跳过 ${count - shownCount} 条）")
        } catch (e: Exception) {
            Log.e(TAG, "公告推送处理失败: ${e.message}", e)
        }
    }

    /**
     * [TASK-MILESTONE-V3] B5 处理“清除本地公告”指令（announcement_clear）：
     * 服务端删除公告后实时下发，客户端按 id 删除本地记录（多端一致）
     */
    private fun handleAnnouncementClear(message: P2PMessage) {
        try {
            val passphrase = getPassphrase()
            val idsArray = message.payload["announcementIds"]?.toString()
                ?.let { JSONArray(it) } ?: return
            val ids = mutableListOf<String>()
            for (i in 0 until idsArray.length()) {
                val id = idsArray.optString(i)
                if (id.isNotBlank()) ids.add(id)
            }
            if (ids.isEmpty()) return
            val deleted = announcementDao.deleteByIds(ids, passphrase)
            Log.i(TAG, "已按清除指令删除 $deleted 条本地公告")
        } catch (e: Exception) {
            Log.e(TAG, "公告清除指令处理失败: ${e.message}", e)
        }
    }

    /**
     * [TASK-PRELAUNCH-P3] 上报公告已显示事件（announcement_displayed）
     * Web 侧据此落库 displayed_at；未连接时静默丢弃（送达记录以服务端推送计数为准）
     * [TASK-PRELAUNCH-P3-FIX] 096 同类风险：网络发送统一移入 IO 线程，避免主线程网络异常
     */
    private fun sendAnnouncementDisplayed(announcementId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val message = P2PMessage(
                    type = "announcement_displayed",
                    payload = mapOf(
                        "announcementId" to announcementId,
                        "displayedAt" to (System.currentTimeMillis() / 1000),
                        "deviceId" to getDeviceId()
                    )
                )
                connectionService.sendMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "上报公告显示事件失败: ${e.message}")
            }
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
                        .setSmallIcon(R.drawable.ic_notification)
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
                    .setSmallIcon(R.drawable.ic_notification)
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
     * [TASK-APP-UPDATE-V1] D2/D6：处理服务端「update_available」推送。
     * 载荷仅作触发信号（含版本码），权威清单仍以 /api/update/check 为准（防伪造/防降级）。
     * - 儿童端守护不被打断：只发普通通知，绝不弹窗/全屏；
     * - 频控与弹窗同源（版本+每日一次）；强制更新每次推送都通知；
     * - 已开启自动下载（C6）时后台静默下载，SHA-256 校验通过后通知点击安装。
     */
    private fun handleUpdateAvailable(message: P2PMessage) {
        scope.launch {
            try {
                val result = UpdateManager.check(context, manual = false)
                if (result !is UpdateManager.CheckResult.Update) return@launch
                val info = result.info
                if (!info.force && !UpdateManager.shouldPrompt(context, info)) return@launch
                UpdateManager.markPrompted(context, info)
                UpdateNotifier.notifyAvailable(context, info)
                if (!UpdateManager.isAutoDownloadEnabled(context)) return@launch

                val file = UpdateManager.downloadApk(context, info) { done, total ->
                    val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                    UpdateNotifier.notifyDownloadProgress(context, info.versionName, percent)
                }
                if (file != null) {
                    UpdateNotifier.notifyDownloadComplete(context, info)
                } else {
                    UpdateNotifier.notifyDownloadFailed(context, info.versionName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新推送处理失败: ${e.message}")
            }
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
     * [TASK-PRELAUNCH-P4] 读取今日限额重置偏移（分钟）；偏移日期非今日（或未重置）时返回 0
     */
    private fun getDailyResetOffsetMinutes(): Long {
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val offsetDate = prefs.getString("daily_reset_offset_date", null) ?: return 0L
        val today = dateFormat.format(Date())
        return if (offsetDate == today) prefs.getLong("daily_reset_offset_minutes", 0L) else 0L
    }

    /**
     * [FIX-100] 儿童端自算的调整后今日已用（分钟）：
     * 优先取采集器实例值（与主页/超时判定同口径）；实例未就绪时用实时累计 − 偏移兜底
     */
    private fun getTodayAdjustedMinutes(): Long {
        UsageStatsCollector.todayAdjustedMinutesOrNull()?.let { return it }
        val offset = getDailyResetOffsetMinutes()
        return (UsageStatsHelper.getTodayTotalMinutes(context) - offset).coerceAtLeast(0L)
    }

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
