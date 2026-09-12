// [android-tv] 测试 — AntiBypassServiceTV Robolectric 测试
// [TASK-TV-01] 修正 Robolectric ServiceController API

package com.xiaopacai.tvos.service

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/**
 * AntiBypassServiceTV Robolectric 单元测试
 *
 * 验证：
 * 1. Service 生命周期（onCreate, onStartCommand）
 * 2. 包管理器访问
 * 3. 包变更广播接收
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AntiBypassServiceTVTest {

    @Test
    fun testServiceCreation() {
        val service = AntiBypassServiceTV()
        assertNotNull(service)
    }

    @Test
    fun testServiceStartCommandReturnsSticky() {
        val service = Robolectric.buildService(AntiBypassServiceTV::class.java).create().get()
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
    }

    @Test
    fun testGetInstalledPackagesReturnsSet() {
        val service = Robolectric.buildService(AntiBypassServiceTV::class.java).create().get()

        val pm = RuntimeEnvironment.getApplication().packageManager
        val packageInfo = PackageInfo().apply {
            packageName = "com.xiaopacai.tvos"
        }

        val shadowPm = shadowOf(pm)
        shadowPm.addPackage(packageInfo)

        val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        assertTrue(packages.any { it.packageName == "com.xiaopacai.tvos" })
    }

    @Test
    fun testPackageRemovedBroadcastHandled() {
        val service = Robolectric.buildService(AntiBypassServiceTV::class.java).create().get()

        // Register fake package
        val pm = RuntimeEnvironment.getApplication().packageManager
        val packageInfo = PackageInfo().apply {
            packageName = "com.xiaopacai.tvos"
        }
        shadowOf(pm).addPackage(packageInfo)

        // Send package removed broadcast
        val removedIntent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            data = android.net.Uri.parse("package:com.xiaopacai.tvos")
        }

        // Should not throw
        service.packageReceiver.onReceive(RuntimeEnvironment.getApplication(), removedIntent)
    }

    @Test
    fun testPackageAddedBroadcastHandled() {
        val service = Robolectric.buildService(AntiBypassServiceTV::class.java).create().get()

        // Send package added broadcast
        val addedIntent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = android.net.Uri.parse("package:com.new.app")
        }

        // Should not throw
        service.packageReceiver.onReceive(RuntimeEnvironment.getApplication(), addedIntent)
    }

    @Test
    fun testPackageReplacedBroadcastHandled() {
        val service = Robolectric.buildService(AntiBypassServiceTV::class.java).create().get()

        // Send package replaced broadcast
        val replacedIntent = Intent(Intent.ACTION_PACKAGE_REPLACED).apply {
            data = android.net.Uri.parse("package:com.xiaopacai.tvos")
        }

        // Should not throw
        service.packageReceiver.onReceive(RuntimeEnvironment.getApplication(), replacedIntent)
    }
}
