package com.xiaopacai.child.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TASK-HARDENING-V1.1.1] Bug3-B：日志上传失败指数退避延迟纯函数用例
 *
 * 契约：第 1 次失败 5 分钟，第 2 次 15 分钟，其后维持 60 分钟
 * （6 小时 WorkManager 周期任务兜底保留）。
 */
class LogUploaderRetryTest {

    @Test
    fun firstFailure_retriesIn5Minutes() {
        assertEquals(5L, LogUploader.retryDelayMinutes(1))
    }

    @Test
    fun secondFailure_retriesIn15Minutes() {
        assertEquals(15L, LogUploader.retryDelayMinutes(2))
    }

    @Test
    fun thirdAndLater_retriesIn60Minutes() {
        assertEquals(60L, LogUploader.retryDelayMinutes(3))
        assertEquals(60L, LogUploader.retryDelayMinutes(10))
    }
}
