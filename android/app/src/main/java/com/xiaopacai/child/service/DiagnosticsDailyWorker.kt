package com.xiaopacai.child.service

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * [TASK-OPT-12-P2] 每日诊断上报 Worker（需求5）
 *
 * 每天执行一次 DiagnosticsCollector.report：
 * - 有 P2P 连接则直接发送
 * - 未连接则缓存，重连后由 SyncManager 补传
 */
class DiagnosticsDailyWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "每日诊断上报任务开始")
        val sent = DiagnosticsCollector.report(applicationContext)
        Log.i(TAG, if (sent) "每日诊断上报成功" else "每日诊断上报未连接，已缓存")
        return Result.success()
    }

    companion object {
        private const val TAG = "DiagnosticsWorker"
    }
}
