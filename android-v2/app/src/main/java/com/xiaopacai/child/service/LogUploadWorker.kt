package com.xiaopacai.child.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.LogUploader

/**
 * [TASK-MILESTONE-V3] 需求 14：日志自动上传 Worker（D6 自动定期）
 *
 * 每 6 小时由 WorkManager 触发（LogUploader.schedulePeriodic 调度）：
 * - 未登录家长账号 → uploadBlocking 快速跳过（Skipped）；
 * - 已登录 → 增量上传自上次成功时间戳之后的新条目（批上限 500，循环拉干）；
 * - 失败不重试（下个周期自然再传；重试策略无意义且可能放大服务端压力）。
 */
class LogUploadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        AppLog.d(TAG, "日志自动上传任务开始")
        val result = LogUploader.uploadBlocking(applicationContext)
        AppLog.d(TAG, "日志自动上传任务结束: $result")
        return Result.success()
    }

    companion object {
        private const val TAG = "LogUploadWorker"
    }
}
