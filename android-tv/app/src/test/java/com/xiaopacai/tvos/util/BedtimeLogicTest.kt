// [android-tv] 就寝时段逻辑单测（A3）
// 用「当前时刻 ± N 分钟」构造窗口，避免依赖固定钟点（跨零点也自动覆盖）
package com.xiaopacai.tvos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BedtimeLogicTest {

    private fun hhmm(offsetMinutes: Int): String {
        val cal = Calendar.getInstance()
        val total = ((cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) + offsetMinutes) % 1440 + 1440) % 1440
        val h = total / 60
        val m = total % 60
        return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
    }

    @Test
    fun `未设置时不处于就寝时段`() {
        TimeoutExecutorTV.setBedtime(null, null)
        assertFalse(TimeoutExecutorTV.isBedtimeNow())
        assertEquals("", TimeoutExecutorTV.bedtimeText())
    }

    @Test
    fun `非法值按未设置处理`() {
        TimeoutExecutorTV.setBedtime("abc", "25:99")
        assertFalse(TimeoutExecutorTV.isBedtimeNow())
        assertEquals("", TimeoutExecutorTV.bedtimeText())
    }

    @Test
    fun `窗口包含当前时刻则为就寝中（含跨零点）`() {
        TimeoutExecutorTV.setBedtime(hhmm(-2), hhmm(2))
        assertTrue(TimeoutExecutorTV.isBedtimeNow())
    }

    @Test
    fun `窗口不含当前时刻则不在就寝中`() {
        TimeoutExecutorTV.setBedtime(hhmm(120), hhmm(180))
        assertFalse(TimeoutExecutorTV.isBedtimeNow())
    }

    @Test
    fun `文案统一补零为 HH 冒号 mm`() {
        TimeoutExecutorTV.setBedtime("21:00", "7:05")
        assertEquals("21:00\u201307:05", TimeoutExecutorTV.bedtimeText())
    }
}
