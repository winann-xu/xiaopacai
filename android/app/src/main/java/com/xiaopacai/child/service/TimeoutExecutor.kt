package com.xiaopacai.child.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.ui.BlockOverlayActivity
import com.xiaopacai.child.util.DbPassphraseProvider
import net.sqlcipher.database.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.*

/**
 * [TASK-D2-06] 超时停用执行器（核心功能）
 *
 * 负责超时停用的实际执行：
 * 1. 监听 UsageStatsCollector 的超时状态变化
 * 2. 超时触发时启动全屏封锁界面
 * 3. 超时解除时恢复正常状态
 * 4. 记录超时事件到本地数据库（供家长端查询）
 *
 * 超时模式：
 * - full: 整机停用，仅允许系统应用 + 白名单
 * - partial: 部分停用，非学习类应用被拦截
 * - none: 正常使用（无限制）
 */
class TimeoutExecutor(private val context: Context) {

    companion object {
        private const val TAG = "TimeoutExecutor"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 上次已知的超时状态（用于检测状态变更） */
    private var lastTimeoutState: Boolean = false

    /** 上次的停用模式 */
    private var lastStopMode: String = "none"

    /** 是否正在显示封锁界面 */
    @Volatile
    private var isBlockOverlayShowing: Boolean = false

    /**
     * 检查并执行超时停用
     * 由 UsageStatsCollector 在每次采集后调用
     *
     * @param isTimeout 是否超时
     * @param stopMode 停用模式（full/partial/none）
     * @param usedMinutes 已用分钟数
     * @param limitMinutes 限额分钟数
     */
    fun checkAndExecute(
        isTimeout: Boolean,
        stopMode: String,
        usedMinutes: Long,
        limitMinutes: Long
    ) {
        // 状态未变化则跳过
        if (isTimeout == lastTimeoutState && stopMode == lastStopMode) {
            return
        }

        if (isTimeout && !lastTimeoutState) {
            // 超时触发：锁定设备
            onTimeoutStarted(stopMode, usedMinutes, limitMinutes)
        } else if (!isTimeout && lastTimeoutState) {
            // 超时解除：恢复正常
            onTimeoutEnded(stopMode)
        }

        lastTimeoutState = isTimeout
        lastStopMode = stopMode
    }

    /**
     * 超时开始处理
     *
     * [TASK-OPT-12-P2] 支持 partial/warn 模式（需求7）：
     * - full：启动全屏封锁界面（整机停用）
     * - partial：不启动全屏界面，由无障碍服务按"黑名单 + 非白名单"单应用拦截
     * - warn：仅发通知提醒，不拦截
     */
    private fun onTimeoutStarted(stopMode: String, usedMinutes: Long, limitMinutes: Long) {
        Log.w(TAG, "=== 超时停用触发 === 模式: $stopMode, 已用: ${usedMinutes}分钟/${limitMinutes}分钟")

        // 1. 记录超时事件到数据库
        logTimeoutEvent("timeout_start", stopMode, mapOf(
            "usedMinutes" to usedMinutes,
            "limitMinutes" to limitMinutes
        ))

        when (stopMode) {
            // 2. Full 模式：主动启动全屏封锁界面
            "full" -> {
                showBlockOverlay("system_timeout", "今日使用时长已达上限（${usedMinutes}/${limitMinutes} 分钟）")
            }
            // 3. Partial 模式：发通知提示，单应用拦截交给无障碍服务
            "partial" -> {
                notifyTimeout("⚠️ 部分应用已停用",
                    "今日使用时长已达上限，娱乐应用将被拦截，白名单与学习类应用可继续使用")
            }
            // 4. Warn 模式：仅警告，不拦截
            "warn" -> {
                notifyTimeout("⏰ 使用时长已超限",
                    "今日使用时长已达上限（${usedMinutes}/${limitMinutes} 分钟），请合理安排休息")
            }
        }
    }

    /**
     * [TASK-OPT-12-P2] 发送超时提示通知（partial/warn 模式用）
     */
    private fun notifyTimeout(title: String, message: String) {
        try {
            val notificationManager = context.getSystemService(
                android.content.Context.NOTIFICATION_SERVICE
            ) as android.app.NotificationManager
            val notification = androidx.core.app.NotificationCompat.Builder(
                context, com.xiaopacai.child.XiaopacaiApp.CHANNEL_ANNOUNCEMENT
            )
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(3002, notification)
        } catch (e: Exception) {
            Log.e(TAG, "发送超时提示通知失败: ${e.message}")
        }
    }

    /**
     * 超时结束处理（次日重置）
     */
    private fun onTimeoutEnded(stopMode: String) {
        Log.i(TAG, "超时停用已解除，模式: $stopMode")

        // 记录解除事件
        logTimeoutEvent("timeout_end", stopMode, emptyMap())

        // 关闭封锁界面（如果正在显示）
        dismissBlockOverlay()
    }

    /**
     * 显示全屏封锁界面
     */
    private fun showBlockOverlay(targetPackage: String, reason: String) {
        if (isBlockOverlayShowing) return

        try {
            val intent = Intent(context, BlockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("target_package", targetPackage)
                putExtra("reason", reason)
            }
            context.startActivity(intent)
            isBlockOverlayShowing = true
            Log.i(TAG, "全屏封锁界面已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动封锁界面失败: ${e.message}", e)
        }
    }

    /**
     * 关闭封锁界面
     */
    fun dismissBlockOverlay() {
        isBlockOverlayShowing = false
        Log.i(TAG, "封锁界面状态已重置")
    }

    /**
     * 记录超时事件到本地加密数据库
     */
    private fun logTimeoutEvent(
        eventType: String,
        stopMode: String,
        detail: Map<String, Any>
    ) {
        try {
            val passphrase = getPassphrase()

            // 获取设备 ID
            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", "unknown")!!

            val db = XiaopacaiApp.instance.database.getWritable(passphrase)
            try {
                // 事件写入 usage_records 表的同步标记（简化：通过 daily_summary 记录）
                // 主要使用 daily_summary 表记录超时状态
                val values = android.content.ContentValues().apply {
                    put("date", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
                    put("stop_mode", stopMode)
                    put("limit_exceeded", 1)
                }
                db.update("daily_summary", values, "date = ?", arrayOf(
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                ))
            } finally {
                db.close()
            }

            Log.i(TAG, "超时事件已记录: type=$eventType, mode=$stopMode, device=$deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "记录超时事件失败: ${e.message}", e)
        }
    }

    /**
     * 获取超时事件历史（最近 7 天）
     */
    fun getRecentTimeoutEvents(days: Int = 7): List<Map<String, Any?>> {
        return try {
            val passphrase = getPassphrase()
            val db = XiaopacaiApp.instance.database.getReadable(passphrase)
            try {
                val results = mutableListOf<Map<String, Any?>>()
                val cursor = db.rawQuery(
                    """SELECT date, total_minutes, limit_minutes, stop_mode, limit_exceeded
                       FROM daily_summary
                       WHERE limit_exceeded = 1
                       ORDER BY date DESC LIMIT ?""",
                    arrayOf(days.toString())
                )
                cursor.use {
                    while (it.moveToNext()) {
                        results.add(mapOf(
                            "date" to it.getString(0),
                            "totalMinutes" to it.getLong(1),
                            "limitMinutes" to it.getLong(2),
                            "stopMode" to it.getString(3),
                            "exceeded" to (it.getInt(4) == 1)
                        ))
                    }
                }
                results
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询超时事件失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取数据库密码 [TASK-D3-05]
     */
    private fun getPassphrase(): ByteArray {
        return DbPassphraseProvider.getPassphrase(context)
    }
}
