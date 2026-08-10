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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "守护前台服务创建")

        // [TASK-D3-03] 启动防绕过监控
        AntiBypassService.startMonitoring(this, serviceScope)
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

        return START_STICKY  // 服务被杀后自动重启
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
        syncManager = SyncManager(this, p2pConnection, serviceScope)
        syncManager?.start()
    }

    /**
     * 定时更新前台通知，显示当前使用时长
     */
    private fun startNotificationUpdater() {
        serviceScope.launch {
            while (isActive) {
                delay(2 * 60 * 1000L)  // 2 分钟
                val totalMinutes = collector?.todayTotalMinutes ?: 0
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
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
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
