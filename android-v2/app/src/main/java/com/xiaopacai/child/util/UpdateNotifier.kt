package com.xiaopacai.child.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.MainActivity
import com.xiaopacai.child.R
import com.xiaopacai.child.XiaopacaiApp

/**
 * [TASK-APP-UPDATE-V1] 更新流程通知（渠道 channel_updates，ADR 0017 C4）：
 * - 更新可用：点击进入家长端弹更新弹窗
 * - 下载中：进度条通知（ongoing）
 * - 下载完成：点击直接唤起安装（携带版本码，MainActivity 定位已校验过的 APK）
 * - 下载失败：点击重试（回到家长端弹窗流程）
 *
 * 儿童端守护不被打断：以上均为普通通知，无 full-screen intent。
 */
object UpdateNotifier {

    private const val TAG = "UpdateNotifier"

    const val EXTRA_INSTALL_VERSION_CODE = "update_install_version_code"
    const val NOTIFY_ID_UPDATE = 7001
    const val NOTIFY_ID_DOWNLOAD = 7002

    private fun nm(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 打开家长端弹窗（CLEAR_TOP 复用现有实例，与公告通知同模式） */
    private fun openAppIntent(context: Context, versionCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (versionCode > 0) putExtra(EXTRA_INSTALL_VERSION_CODE, versionCode)
        }
        return PendingIntent.getActivity(
            context,
            if (versionCode > 0) versionCode else NOTIFY_ID_UPDATE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 新版本可用（P2P 推送/静默检查发现）。点击携带版本码 → 家长端直接弹更新弹窗 */
    fun notifyAvailable(context: Context, info: UpdateManager.UpdateInfo) {
        try {
            val text = if (info.force) "必须更新后才能继续使用家长端" else "点击查看更新详情"
            val notification = NotificationCompat.Builder(context, XiaopacaiApp.CHANNEL_UPDATES)
                .setContentTitle("发现新版本 v${info.versionName}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    (info.changelog.ifBlank { text }) + "\n" + text))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(if (info.force) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context, info.versionCode))
                .build()
            nm(context).notify(NOTIFY_ID_UPDATE, notification)
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败: ${e.message}")
        }
    }

    /** 下载进度（0..100；percent>=100 由下载完成通知接管） */
    fun notifyDownloadProgress(context: Context, versionName: String, percent: Int) {
        try {
            val notification = NotificationCompat.Builder(context, XiaopacaiApp.CHANNEL_UPDATES)
                .setContentTitle("正在下载 v$versionName")
                .setContentText("$percent%")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent.coerceIn(0, 100), false)
                .build()
            nm(context).notify(NOTIFY_ID_DOWNLOAD, notification)
        } catch (e: Exception) {
            Log.e(TAG, "下载进度通知失败: ${e.message}")
        }
    }

    /** 下载完成且 SHA-256 已通过：点击直接进入安装流程 */
    fun notifyDownloadComplete(context: Context, info: UpdateManager.UpdateInfo) {
        try {
            val notification = NotificationCompat.Builder(context, XiaopacaiApp.CHANNEL_UPDATES)
                .setContentTitle("v${info.versionName} 已下载完成")
                .setContentText("已通过安全校验，点击安装")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context, info.versionCode))
                .build()
            nm(context).notify(NOTIFY_ID_DOWNLOAD, notification)
        } catch (e: Exception) {
            Log.e(TAG, "下载完成通知失败: ${e.message}")
        }
    }

    /** 下载失败：点击回家长端重试 */
    fun notifyDownloadFailed(context: Context, versionName: String) {
        try {
            val notification = NotificationCompat.Builder(context, XiaopacaiApp.CHANNEL_UPDATES)
                .setContentTitle("v$versionName 下载失败")
                .setContentText("点击重试更新")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            nm(context).notify(NOTIFY_ID_DOWNLOAD, notification)
        } catch (e: Exception) {
            Log.e(TAG, "下载失败通知失败: ${e.message}")
        }
    }
}
