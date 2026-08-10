package com.xiaopacai.child.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.R
import com.xiaopacai.child.XiaopacaiApp

/**
 * [TASK-D1-02] 小趴菜守护前台服务
 *
 * 后台常驻服务，负责：
 * 1. 持续采集应用使用时长（避免进程被系统杀死后统计中断）
 * 2. 维持 P2P 长连接心跳
 * 3. 超时停用守护（前台识别 + 拦截触发）
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 服务创建时即启动前台通知
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: [TASK-D1-02] 在此处启动时长采集、P2P 心跳、守护循环
        // 当前为骨架实现，后续任务中逐步填充

        return START_STICKY  // 服务被杀后自动重启
    }

    /**
     * 启动前台通知
     * 使用 LOW 优先级持续通知，保持服务活跃同时最小化干扰
     */
    private fun startForegroundNotification() {
        val channelId = XiaopacaiApp.CHANNEL_GUARDIAN

        // 构建通知
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("小趴菜守护运行中")
            .setContentText("正在守护孩子的使用时长")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)  // TODO: 替换为自定义图标
            .setOngoing(true)  // 持续通知，不可滑动清除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // 启动前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 需要指定前台服务类型
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 服务销毁时的清理工作
        // TODO: [TASK-D1-02] 停止采集、关闭 P2P 连接
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

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
}
