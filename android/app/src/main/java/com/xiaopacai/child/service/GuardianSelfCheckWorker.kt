package com.xiaopacai.child.service

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * [TASK-OPT-12-P2] 守护自检 Worker（需求6 双守护：WorkManager 周期自检）
 *
 * 每 15 分钟由 WorkManager 触发一次：
 * 1. 执行防绕过全向量检查（无障碍/用量/设备管理器/电池优化），异常发通知
 * 2. 确保守护前台服务在运行（服务被杀后自动拉起）
 *
 * 与 AntiBypassService 的分钟级协程检查、AlarmManager 兜底构成三重守护。
 */
class GuardianSelfCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d(TAG, "守护自检开始（WorkManager 15 分钟周期）")
        try {
            // 1. 防绕过全向量检查（异常时自动通知）
            AntiBypassService.checkAllBypassVectors(applicationContext)

            // 2. 确保前台守护服务存活
            GuardianForegroundService.start(applicationContext)

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "守护自检异常: ${e.message}")
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "GuardianSelfCheck"
    }
}
