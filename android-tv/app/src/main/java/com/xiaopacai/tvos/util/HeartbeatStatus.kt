// [android-tv] 工具类 — 心跳/连接状态
// 小趴菜 TVOS 版 — 供主界面与锁屏界面显示「心跳状态 + 最后心跳时间」（对标手机版 CloudSyncService.connectionState）
//
// 状态来源：CloudSyncServiceTV 每次心跳成功/失败时调用 markSuccess()/markFailure()。
// 跨进程重启后仍能显示最后成功时间（由 SharedPreferenceHelperTV.last_sync_time 兜底）。

package com.xiaopacai.tvos.util

import kotlinx.coroutines.flow.MutableStateFlow

object HeartbeatStatus {

    /** 是否在线（最近一次心跳是否成功） */
    val online = MutableStateFlow(false)

    /** 最后一次心跳成功的时间戳（毫秒，0 = 从未成功） */
    val lastSuccessAt = MutableStateFlow(0L)

    /** 本进程内最后一次心跳尝试的时间戳 */
    val lastAttemptAt = MutableStateFlow(0L)

    /** 最后一次失败原因（用于界面提示） */
    val lastError = MutableStateFlow("")

    fun markAttempt() {
        lastAttemptAt.value = System.currentTimeMillis()
    }

    fun markSuccess() {
        val now = System.currentTimeMillis()
        online.value = true
        lastSuccessAt.value = now
        lastError.value = ""
    }

    fun markFailure(reason: String) {
        online.value = false
        lastError.value = reason
    }

    /** 进程重启后用持久化的时间兜底 */
    fun seedLastSuccess(ts: Long) {
        if (lastSuccessAt.value == 0L && ts > 0L) lastSuccessAt.value = ts
    }

    /**
     * 人类可读的「最后心跳」描述 —— 用户要求：**不再显示「刚刚」**，直接显示实际同步时刻。
     *  - 从未心跳 → "尚未与服务器心跳"
     *  - 今天 → "16:37:05"
     *  - 更早 → "09-12 16:37:05"
     */
    fun describeLastHeartbeat(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return "尚未与服务器心跳"
        val fmt = if (isSameDay(ts, now)) "HH:mm:ss" else "MM-dd HH:mm:ss"
        return java.text.SimpleDateFormat(fmt, java.util.Locale.CHINA).format(java.util.Date(ts))
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
        return fmt.format(java.util.Date(a)) == fmt.format(java.util.Date(b))
    }
}
