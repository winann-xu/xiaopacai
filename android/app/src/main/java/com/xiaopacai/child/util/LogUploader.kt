package com.xiaopacai.child.util

import android.content.Context
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
 * - [TASK-HARDENING-V1.1.1] Bug3-B：登录/绑定成功后立即上传 + 失败指数退避
 *   （5/15/60 分钟，此后维持 60 分钟；6 小时周期任务兜底保留）；
 * - [TASK-HARDENING-V1.1.1] Bug3-C：持久化最近一次失败时间/原因，日志页如实展示；
 * - 服务端保留最近 7 天，本地上传不限历史（缓冲环形上限 5000 条自然封顶）。
 */
object LogUploader {

    private const val TAG = "LogUploader"
    private const val PREFS = "xpc_log_upload"
    private const val KEY_LAST_TS = "last_upload_ts"
    private const val KEY_LAST_FAIL_TS = "last_fail_ts"
    private const val KEY_LAST_FAIL_REASON = "last_fail_reason"
    private const val KEY_RETRY_COUNT = "retry_count"
    private const val BATCH_MAX = 500
    private const val WORK_NAME = "xpc_log_upload_periodic"
    private const val RETRY_WORK_NAME = "xpc_log_upload_retry"

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

    // ---- [TASK-HARDENING-V1.1.1] Bug3-C：失败状态查询（日志页如实展示） ----

    /** 最近一次失败时间戳（0=无失败记录） */
    fun lastFailTs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_FAIL_TS, 0L)

    /** 最近一次失败原因（空=无失败记录） */
    fun lastFailReason(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_FAIL_REASON, "") ?: ""

    /** 当前退避重试次数（成功清零） */
    fun retryCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RETRY_COUNT, 0)

    /** [Bug3-B] 指数退避延迟（纯函数，单测）：第 1 次失败 5 分钟，第 2 次 15 分钟，其后 60 分钟 */
    fun retryDelayMinutes(failCount: Int): Long = when {
        failCount <= 1 -> 5L
        failCount == 2 -> 15L
        else -> 60L
    }

    // ==================== 失败记录与退避重试 ====================

    /** 上传失败：记录时间/原因 + 计数 + 调度指数退避重试 */
    private fun recordFailure(context: Context, reason: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_RETRY_COUNT, 0) + 1
        prefs.edit()
            .putLong(KEY_LAST_FAIL_TS, System.currentTimeMillis())
            .putString(KEY_LAST_FAIL_REASON, reason.take(160))
            .putInt(KEY_RETRY_COUNT, count)
            .apply()
        scheduleRetry(context, count)
    }

    /** 上传成功：清除失败记录 + 归零重试计数 + 取消待执行的重试任务 */
    private fun recordSuccess(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_LAST_FAIL_TS)
            .remove(KEY_LAST_FAIL_REASON)
            .putInt(KEY_RETRY_COUNT, 0)
            .apply()
        cancelRetry(context)
    }

    /** [Bug3-B] 调度一次性退避重试（REPLACE 幂等；成功后被 recordSuccess 取消） */
    private fun scheduleRetry(context: Context, failCount: Int) {
        val delayMin = retryDelayMinutes(failCount)
        try {
            val request = OneTimeWorkRequestBuilder<LogUploadWorker>()
                .setInitialDelay(delayMin, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, request
            )
            AppLog.d(TAG, "上传失败，${delayMin} 分钟后自动重试（第 $failCount 次）")
        } catch (e: Exception) {
            AppLog.w(TAG, "调度退避重试失败: ${e.message}")
        }
    }

    /** 取消退避重试任务（成功/清账时调用；取消不存在的工作为无操作） */
    fun cancelRetry(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(RETRY_WORK_NAME)
        } catch (e: Exception) {
            AppLog.w(TAG, "取消退避重试失败: ${e.message}")
        }
    }

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
            ?: return fail(context, "尚未配置服务器地址")
        val port = CloudAccountManager.getServerPort(context)
        val token = CloudAccountManager.getToken(context)
            ?: return fail(context, "缺少登录凭据")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_TS, 0L)

        // 升序批次（旧 → 新），只传 lastTs 之后的新条目
        val pending = AppLog.entries().filter { it.ts > lastTs }.reversed()
        if (pending.isEmpty()) {
            AppLog.d(TAG, "无新日志，跳过上传")
            recordSuccess(context)  // 无积压视为一次成功链路（清失败标记）
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
                return fail(context, "网络不可达，稍后重试")
            }
            if (result.first !in 200..299) {
                AppLog.w(TAG, "日志上传失败: HTTP ${result.first}")
                return fail(context, "上传失败: HTTP ${result.first}")
            }

            uploaded += batch.size
            cursor = batch.last().ts
            prefs.edit().putLong(KEY_LAST_TS, cursor).apply()
            idx += BATCH_MAX
        }
        AppLog.i(TAG, "日志上传完成: $uploaded 条")
        recordSuccess(context)
        return UploadResult.Ok(uploaded)
    }

    /** [Bug3-B] 失败出口：记录失败状态 + 调度退避重试后返回 Err */
    private fun fail(context: Context, reason: String): UploadResult.Err {
        recordFailure(context, reason)
        return UploadResult.Err(reason)
    }
}
