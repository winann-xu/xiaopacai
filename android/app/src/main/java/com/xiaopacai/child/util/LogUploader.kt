package com.xiaopacai.child.util

import android.content.Context
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xiaopacai.child.service.LogUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [TASK-MILESTONE-V3] 需求 14：运行日志上传 Web（D6 自动定期 + 手动按钮）
 *
 * - 增量上传：按 prefs 记录的最后成功时间戳，只传新条目（服务端不重复入库）；
 * - 批上限：每批 500 条（服务端同口径校验），循环批次直至拉干；
 * - 自动：WorkManager 每 6 小时（仅已登录家长账号时真正上报，未登录快速跳过）；
 * - 手动：日志页「上传云端」按钮；
 * - 服务端保留最近 7 天，本地上传不限历史（缓冲环形上限 5000 条自然封顶）。
 */
object LogUploader {

    private const val TAG = "LogUploader"
    private const val PREFS = "xpc_log_upload"
    private const val KEY_LAST_TS = "last_upload_ts"
    private const val BATCH_MAX = 500
    private const val WORK_NAME = "xpc_log_upload_periodic"

    sealed class UploadResult {
        /** 未登录账号（家长端未绑定 Web 账号），跳过上传 */
        object Skipped : UploadResult()

        data class Ok(val uploaded: Int) : UploadResult()
        data class Err(val message: String) : UploadResult()
    }

    // ==================== 自动上传调度 ====================

    /** 应用启动时调用一次：每 6 小时周期任务（KEEP 幂等） */
    fun schedulePeriodic(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<LogUploadWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            AppLog.d(TAG, "日志自动上传已调度（每 6 小时）")
        } catch (e: Exception) {
            AppLog.w(TAG, "调度日志自动上传失败: ${e.message}")
        }
    }

    /** 最近一次成功上传时间戳（UI 展示用） */
    fun lastUploadTs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_TS, 0L)

    // ==================== 手动/自动上传 ====================

    /** 协程版（UI 调用）：IO 线程执行阻塞网络 */
    suspend fun uploadNow(context: Context): UploadResult =
        withContext(Dispatchers.IO) { uploadBlocking(context) }

    /** 阻塞版（Worker 调用） */
    fun uploadBlocking(context: Context): UploadResult {
        if (!CloudAccountManager.isBound(context)) {
            AppLog.d(TAG, "未登录家长账号，跳过日志上传")
            return UploadResult.Skipped
        }
        val host = CloudAccountManager.getServerHost(context)
            ?: return UploadResult.Err("尚未配置服务器地址")
        val port = CloudAccountManager.getServerPort(context)
        val token = CloudAccountManager.getToken(context)
            ?: return UploadResult.Err("缺少登录凭据")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_TS, 0L)

        // 升序批次（旧 → 新），只传 lastTs 之后的新条目
        val pending = AppLog.entries().filter { it.ts > lastTs }.reversed()
        if (pending.isEmpty()) {
            AppLog.d(TAG, "无新日志，跳过上传")
            return UploadResult.Ok(0)
        }

        val client = "${Build.MODEL}/${Build.VERSION.RELEASE}".take(64)
        var uploaded = 0
        var cursor = lastTs
        var idx = 0
        while (idx < pending.size) {
            val batch = pending.subList(idx, minOf(idx + BATCH_MAX, pending.size))
            val body = JSONObject()
                .put("client", client)
                .put(
                    "logs",
                    JSONArray().apply {
                        batch.forEach { e ->
                            put(JSONObject().put("t", e.ts).put("level", e.level).put("tag", e.tag).put("msg", e.msg))
                        }
                    }
                )
                .toString()

            val result = try {
                httpPostJson(host, port, "/api/logs", body, token)
            } catch (e: Exception) {
                AppLog.w(TAG, "日志上传网络异常: ${e.message}")
                return UploadResult.Err("网络不可达，稍后重试")
            }
            if (result.first !in 200..299) {
                AppLog.w(TAG, "日志上传失败: HTTP ${result.first}")
                return UploadResult.Err("上传失败: HTTP ${result.first}")
            }

            uploaded += batch.size
            cursor = batch.last().ts
            prefs.edit().putLong(KEY_LAST_TS, cursor).apply()
            idx += BATCH_MAX
        }
        AppLog.i(TAG, "日志上传完成: $uploaded 条")
        return UploadResult.Ok(uploaded)
    }
}
