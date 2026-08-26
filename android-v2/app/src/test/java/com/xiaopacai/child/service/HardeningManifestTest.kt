package com.xiaopacai.child.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [TASK-HARDENING-V1.1.1] Bug1（1-A）回归用例：
 *
 * 1. Manifest 声明：GuardianForegroundService 与 ParentP2PListenerService
 *    均须声明 android:stopWithTask="false"（上滑最近任务不销毁服务，
 *    OPPO 真机实测根因——此前缺失导致管控失效）。
 * 2. 恢复链路：onTaskRemoved 注册的 5 秒上滑恢复闹钟延迟契约 +
 *    恢复通知文案分支（管控曾生效/未生效）。
 *
 * 测试运行工作目录为模块目录（app/），直接读取源 Manifest 断言真实声明。
 */
class HardeningManifestTest {

    /** 按 service 名提取声明块，断言其包含 stopWithTask=false */
    private fun assertServiceStopWithTaskFalse(serviceName: String) {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("Manifest 文件不存在（测试工作目录应为模块目录 app/）", manifest.exists())
        val content = manifest.readText()

        val marker = "android:name=\".service.$serviceName\""
        assertTrue("Manifest 未找到服务声明: $serviceName", content.contains(marker))

        // 取该 <service> 起始到 /> 或 </service> 结束的声明块
        val start = content.indexOf(marker)
        val block = content.substring(start, start + 400)
        assertTrue(
            "$serviceName 缺少 android:stopWithTask=\"false\"（上滑最近任务会销毁服务）",
            block.contains("android:stopWithTask=\"false\"")
        )
    }

    @Test
    fun guardianForegroundService_declaresStopWithTaskFalse() {
        assertServiceStopWithTaskFalse("GuardianForegroundService")
    }

    @Test
    fun parentP2PListenerService_declaresStopWithTaskFalse() {
        // 实际声明为 .p2p.ParentP2PListenerService（包路径与 Guardian 前台服务不同）
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("Manifest 文件不存在（测试工作目录应为模块目录 app/）", manifest.exists())
        val content = manifest.readText()
        val marker = "android:name=\".p2p.ParentP2PListenerService\""
        assertTrue("Manifest 未找到服务声明: .p2p.ParentP2PListenerService", content.contains(marker))
        val start = content.indexOf(marker)
        val block = content.substring(start, start + 400)
        assertTrue(
            "ParentP2PListenerService 缺少 android:stopWithTask=\"false\"",
            block.contains("android:stopWithTask=\"false\"")
        )
    }

    @Test
    fun swipeRecoveryDelay_isFiveSeconds() {
        // 恢复链路契约：onTaskRemoved 抢先注册的 5 秒一次性闹钟（系统侧不随进程消亡）
        assertEquals(5_000L, GuardianAlarmReceiver.SWIPE_RECOVERY_DELAY_MS)
    }

    @Test
    fun recoveryText_whenEnforcementWasActive() {
        val (title, text) = GuardianAlarmReceiver().recoveryNotificationText(wasEnforcing = true)
        assertEquals("守护已自动恢复，管控重新生效", title)
        assertTrue(text.contains("重新执行管控"))
    }

    @Test
    fun recoveryText_whenEnforcementWasNotActive() {
        val (title, text) = GuardianAlarmReceiver().recoveryNotificationText(wasEnforcing = false)
        assertEquals("守护已自动恢复", title)
        assertTrue(text.contains("守护已自动恢复"))
    }

    // ---- [TASK-HARDENING-V1.1.1] Bug4-A：事件触发自检注册契约 ----

    @Test
    fun guardianEventReceiver_declaredForPackageReplaced() {
        // 应用更新会关停无障碍服务，必须静态注册 MY_PACKAGE_REPLACED 兜底
        // （亮屏/解锁为动态注册，回前台为 Application 回调，静态注册仅此一个动作）
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("Manifest 文件不存在（测试工作目录应为模块目录 app/）", manifest.exists())
        val content = manifest.readText()
        val marker = "android:name=\".service.GuardianEventReceiver\""
        assertTrue("Manifest 未找到接收器声明: .service.GuardianEventReceiver", content.contains(marker))
        val start = content.indexOf(marker)
        val block = content.substring(start, start + 400)
        assertTrue(
            "GuardianEventReceiver 缺少 MY_PACKAGE_REPLACED 动作（更新后无障碍被关无法即时发现）",
            block.contains("android.intent.action.MY_PACKAGE_REPLACED")
        )
    }

    @Test
    fun guardianEventReceiver_dynamicFilterCoversScreenAndUnlock() {
        // IntentFilter 为 Android 框架类，纯 JVM 单测（returnDefaultValues）下
        // addAction 为静默空操作，无法行为断言 → 与 Manifest 测试同法，
        // 直接断言源文件契约（动态注册须覆盖亮屏+解锁两个事件动作）。
        val src = File("src/main/java/com/xiaopacai/child/service/GuardianEventReceiver.kt")
        assertTrue("GuardianEventReceiver.kt 不存在", src.exists())
        val content = src.readText()
        val dynamicFilterBlock = content.substringAfter("fun dynamicFilter()")
            .substringBefore("override fun onReceive")
        assertTrue("动态注册缺少 ACTION_SCREEN_ON", dynamicFilterBlock.contains("Intent.ACTION_SCREEN_ON"))
        assertTrue("动态注册缺少 ACTION_USER_PRESENT", dynamicFilterBlock.contains("Intent.ACTION_USER_PRESENT"))
    }
}
