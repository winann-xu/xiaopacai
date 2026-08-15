package com.xiaopacai.child.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.p2p.P2PConnectionService
import com.xiaopacai.child.p2p.P2PConnectionState
import com.xiaopacai.child.R
import com.xiaopacai.child.p2p.SyncManager
import kotlinx.coroutines.*

/**
 * [TASK-D1-02][TASK-D2-01] 小趴菜守护前台服务
 *
 * 后台常驻服务，负责：
 * 1. 持续采集应用使用时长（UsageStatsCollector）
 * 2. 维持 P2P 长连接心跳
 * 3. 超时停用守护（前台识别 + 拦截触发）
 * 4. 定时数据同步到家长端
 *
 * 前台服务必须显示持久通知（Android 8.0+），
 * 此处使用 LOW 优先级通知，避免过多打扰儿童。
 *
 * 能力边界说明：
 * - 前台服务可显著降低被系统杀死的概率，但不能 100% 保证
 * - 部分 OEM（如华为/小米/OPPO）有额外的后台限制策略
 * - 详见 OEM_KEEPALIVE.md 文档
 */
class GuardianForegroundService : Service() {

    companion object {
        private const val TAG = "GuardianService"
        private const val NOTIFICATION_ID = 1001

        /** [TASK-MILESTONE-V3] 需求 5：心跳键与判定阈值（超过即视为进程曾被结束） */
        private const val PREFS_GUARDIAN = "guardian_prefs"
        private const val KEY_HEARTBEAT_MS = "guardian_heartbeat_ms"
        private const val KEY_BOOT_EPOCH = "guardian_boot_epoch"
        private const val KEY_SWIPE_PENDING = "swipe_recover_pending"
        const val KILL_GAP_MS = 5 * 60 * 1000L   // 5 分钟无心跳 = 曾被杀死
        private const val HEARTBEAT_INTERVAL_MS = 60 * 1000L

        /** 心跳间隔超阈值即判定进程曾被结束（纯函数，便于单元测试） */
        fun isKillRecovery(lastHeartbeatMs: Long, nowMs: Long): Boolean =
            lastHeartbeatMs > 0 && nowMs - lastHeartbeatMs > KILL_GAP_MS

        /** 管控是否曾生效（TimeoutExecutor 打标） */
        fun isEnforcementActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS_GUARDIAN, Context.MODE_PRIVATE)
                .getBoolean("enforcement_active", false)

        /** 采集器实例（静态，跨服务重启保持） */
        @Volatile
        private var collector: UsageStatsCollector? = null

        /** 同步管理器实例 */
        @Volatile
        private var syncManager: SyncManager? = null

        /** [FIX-LEGACY-a] 共享 P2P 连接实例（PairingManager 与 SyncManager 共用同一链路） */
        @Volatile
        private var sharedP2PConnection: P2PConnectionService? = null

        /**
         * 获取共享的 P2P 连接服务实例
         * UI 配对后 usage_report 走同一 TLS 链路
         */
        fun getP2PConnection(): P2PConnectionService {
            if (sharedP2PConnection == null) {
                synchronized(this) {
                    if (sharedP2PConnection == null) {
                        sharedP2PConnection = P2PConnectionService()
                    }
                }
            }
            return sharedP2PConnection!!
        }

        /**
         * 获取时长采集器实例
         */
        fun getCollector(): UsageStatsCollector? = collector

        /**
         * 启动守护前台服务
         * @param context 调用上下文
         */
        fun start(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止守护前台服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** [TASK-HARDENING-V1.1.1] Bug4-A：亮屏/解锁事件自检接收器（动态注册，随服务常驻） */
    private val eventReceiver = GuardianEventReceiver()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "守护前台服务创建")

        // [TASK-HARDENING-V1.1.1] Bug4-A：亮屏/解锁事件即时自检
        // （无障碍被关 → 立即高优通知 + 一键直达设置；30 秒节流防高频事件合并）
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this, eventReceiver, GuardianEventReceiver.dynamicFilter(),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "注册事件自检接收器失败: ${e.message}")
        }

        // [TASK-D3-03] 启动防绕过监控
        AntiBypassService.startMonitoring(this, serviceScope)

        // [TASK-OPT-12-P2] 双守护自检：WorkManager 15 分钟 + AlarmManager 30 分钟兜底（需求6）
        AntiBypassService.scheduleSelfCheck(this)

        // [TASK-OPT-12-P2] 诊断采集初始化：崩溃处理器 + 每日上报调度（需求5）
        DiagnosticsCollector.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "守护前台服务启动")

        // 1. 启动前台通知
        updateNotification("正在守护中...", 0)

        // 2. 启动时长采集器
        startUsageCollector()

        // 3. 启动同步管理器（P2P 数据同步）
        startSyncManager()

        // 4. 启动通知更新定时器（每 2 分钟刷新通知显示）
        startNotificationUpdater()

        // 5. [TASK-MILESTONE-V3] 需求 5：心跳打标 + 进程被杀检测
        startHeartbeat()
        detectKillRecovery()

        // 6. [TASK-MILESTONE-V3] 需求 5：管控曾生效时立即重放采集（快速恢复拦截，
        //    不等 30 秒初始延迟——上滑结束后被拦截应用恢复可用的窗口越短越好）
        if (isEnforcementActive(this)) {
            Log.i(TAG, "检测到管控曾生效，立即重放采集以快速恢复拦截")
            serviceScope.launch(Dispatchers.IO) {
                runCatching { collector?.collectAndPersist() }
                    .onFailure { Log.e(TAG, "重放采集失败: ${it.message}") }
            }
        }

        return START_STICKY  // 服务被杀后自动重启
    }

    /**
     * [TASK-MILESTONE-V3] 需求 5：每分钟心跳打标（供杀进程检测）
     */
    private fun startHeartbeat() {
        serviceScope.launch {
            while (isActive) {
                val prefs = getSharedPreferences(PREFS_GUARDIAN, MODE_PRIVATE)
                prefs.edit()
                    .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
                    .putLong(KEY_BOOT_EPOCH, currentBootEpoch())
                    .apply()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /** 本机开机时刻（epoch 毫秒）：用于区分「设备重启」与「进程被杀」 */
    private fun currentBootEpoch(): Long =
        System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    /**
     * [TASK-MILESTONE-V3] 需求 5：进程被杀检测（非上滑路径——上滑路径由
     * GuardianAlarmReceiver 通知，避免重复；此处覆盖 OEM 后台杀/异常杀）
     */
    private fun detectKillRecovery() {
        try {
            val prefs = getSharedPreferences(PREFS_GUARDIAN, MODE_PRIVATE)
            val pendingSwipe = prefs.getBoolean(KEY_SWIPE_PENDING, false)
            if (pendingSwipe) return  // 上滑恢复通知由 AlarmReceiver 负责
            val lastHeartbeat = prefs.getLong(KEY_HEARTBEAT_MS, 0L)
            // 设备重启导致的心跳间隔属正常（开机自启恢复），不误报为「被杀」
            val lastBootEpoch = prefs.getLong(KEY_BOOT_EPOCH, -1L)
            if (lastBootEpoch >= 0 && lastBootEpoch != currentBootEpoch()) {
                Log.i(TAG, "设备重启后恢复（开机自启），跳过被杀检测")
                return
            }
            if (!isKillRecovery(lastHeartbeat, System.currentTimeMillis())) return

            Log.w(TAG, "检测到守护进程曾被结束（心跳间隔超阈值），已恢复")
            val wasEnforcing = isEnforcementActive(this)
            // [TASK-HARDENING-V1.1.1] Bug1-D：失守事件结算（startTs 取最后心跳，近似失守起点）
            GuardDownMonitor.onGuardLost(this, "process_killed", startTs = lastHeartbeat)
            GuardDownMonitor.onGuardRestored(this, "auto_recovered")
            AntiBypassService.notifySecurityEvent(
                this,
                "守护已自动恢复" + if (wasEnforcing) "，管控重新生效" else "",
                "检测到小趴菜后台进程曾被系统结束" +
                    if (wasEnforcing) "（期间管控可能短暂失效）" else "" +
                    "，守护已自动恢复。建议在系统设置中允许自启动并关闭电池优化。"
            )
        } catch (e: Exception) {
            Log.e(TAG, "杀进程检测失败: ${e.message}")
        }
    }

    /**
     * [TASK-MILESTONE-V3] 需求 5：上滑结束进程时抢先注册系统侧恢复闹钟。
     * 说明：无法阻止进程被杀（Android 能力边界），但恢复闹钟在系统侧不随进程消亡，
     * 5 秒后拉起守护；管控曾生效时恢复后立即重新拦截并通知。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "检测到上滑结束小趴菜（onTaskRemoved），注册恢复闹钟")
        GuardianAlarmReceiver.scheduleSwipeRecovery(this)
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 启动时长采集器
     */
    private fun startUsageCollector() {
        if (collector == null) {
            collector = UsageStatsCollector(this, serviceScope)
        }
        collector?.start()
    }

    /**
     * 启动 P2P 数据同步管理器
     */
    private fun startSyncManager() {
        // [FIX-LEGACY-a] 使用共享 P2P 连接实例（与 UI 配对共用同一链路）
        val p2pConnection = getP2PConnection()
        // [REQ] 断线/服务重启后按持久化配置自动重连
        maybeAutoReconnect(p2pConnection)
        // [FIX-DOWNLINK] 下行消息路由：家长端下发的策略/公告等必须接入 SyncManager 处理，
        // 否则 policy_update/announcement_push 只进连接层缓存、不落库不生效。
        if (syncManager == null) {
            syncManager = SyncManager(this, p2pConnection, serviceScope)
            var processedCount = 0
            serviceScope.launch {
                p2pConnection.receivedMessages.collect { messages ->
                    while (processedCount < messages.size) {
                        syncManager?.handleReceivedMessage(messages[processedCount])
                        processedCount++
                    }
                }
            }
        }
        syncManager?.start()
    }

    /**
     * [REQ] 自动重连：应用/服务重启后，读取上次成功连接的宿主与配对信息，
     * 若当前未连接则重新建立 P2P（局域网直连或 Web 中继均支持）。
     */
    private fun maybeAutoReconnect(p2pConnection: P2PConnectionService) {
        val state = p2pConnection.connectionState.value
        if (state == P2PConnectionState.CONNECTED || state == P2PConnectionState.CONNECTING ||
            state == P2PConnectionState.HANDSHAKING) return

        val prefs = getSharedPreferences("guardian_prefs", MODE_PRIVATE)
        val host = prefs.getString("relay_host", null)?.takeIf { it.isNotBlank() } ?: return
        val port = prefs.getInt("relay_port", 9527)
        // [SEC-P1] 配对码/会话令牌以 KeyStore 加密存储（"enc:" 前缀），读取时解密；
        // 历史明文无前缀，decryptPrefsValue 直接透传（读取侧迁移兼容）
        val pairingCode = prefs.getString("relay_pairing_code", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { com.xiaopacai.child.util.KeyStoreManager.decryptPrefsValue(it) }
            ?.takeIf { it.isNotBlank() }
        val isRelay = prefs.getBoolean("relay_mode", false)
        val fingerprint = prefs.getString("relay_fingerprint", "")?.takeIf { it.isNotBlank() }
        // [SEC-K2] 家长端中继会话令牌随配置恢复，重连握手仍需携带
        val sessionToken = prefs.getString("relay_session_token", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { com.xiaopacai.child.util.KeyStoreManager.decryptPrefsValue(it) }
            ?.takeIf { it.isNotBlank() }
        val deviceId = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() } ?: return

        Log.i(TAG, "自动重连: $host:$port relay=$isRelay")
        serviceScope.launch(Dispatchers.IO) {
            p2pConnection.connect(
                host = host,
                port = port,
                expectedFingerprint = fingerprint,
                deviceId = deviceId,
                deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
                pairingCode = pairingCode,
                isRelay = isRelay,
                sessionToken = sessionToken,
                scope = serviceScope
            )
        }
    }

    /**
     * 定时更新前台通知，显示当前使用时长
     */
    private fun startNotificationUpdater() {
        serviceScope.launch {
            while (isActive) {
                delay(2 * 60 * 1000L)  // 2 分钟
                // [TASK-PRELAUNCH-P4] 通知显示调整后口径（与超时判定/UI 一致）
                val totalMinutes = collector?.todayAdjustedMinutes ?: 0
                val isTimeout = collector?.isTimeoutActive ?: false

                val contentText = when {
                    isTimeout -> "⚠️ 今日使用时长已超限"
                    totalMinutes > 0 -> "今日已使用 $totalMinutes 分钟"
                    else -> "正在守护孩子的使用时长"
                }
                updateNotification(contentText, totalMinutes.toInt())
            }
        }
    }

    /**
     * 更新前台通知内容
     *
     * @param contentText 通知正文
     * @param progressMinutes 进度分钟数（通知进度条）
     */
    private fun updateNotification(contentText: String, progressMinutes: Int) {
        val channelId = XiaopacaiApp.CHANNEL_GUARDIAN

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("小趴菜守护运行中")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // 显示进度条（限额进度指示）
        val limitMinutes = collector?.todayLimitMinutes?.toInt() ?: 0

        if (limitMinutes > 0) {
            builder.setProgress(limitMinutes, progressMinutes.coerceAtMost(limitMinutes), false)
        }

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止采集、同步与协程
        collector?.stop()
        syncManager?.stop()
        AntiBypassService.stopMonitoring()  // [TASK-D3-03]
        // [TASK-HARDENING-V1.1.1] Bug4-A：注销事件自检接收器
        try {
            unregisterReceiver(eventReceiver)
        } catch (e: Exception) {
            // 未注册或已注销，忽略
        }
        serviceScope.cancel()
        Log.i(TAG, "守护前台服务销毁")
    }

    /**
     * [TASK-D3-05] 获取数据库加密密码（通过 KeyStore）
     */
    private fun getPassphrase(): ByteArray {
        return try {
            com.xiaopacai.child.util.KeyStoreManager.getOrCreateDbMasterKey(this)
        } catch (e: Exception) {
            // KeyStore 不可用时的安全回退
            Log.w(TAG, "KeyStore 不可用，使用备用密码方案")
            val prefs = getSharedPreferences("guardian_secure_prefs", Context.MODE_PRIVATE)
            val seed = prefs.getString("db_key_seed", null)
                ?: java.util.UUID.randomUUID().toString().also {
                    prefs.edit().putString("db_key_seed", it).apply()
                }
            seed.toByteArray(Charsets.UTF_8)
        }
    }
}
