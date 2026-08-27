package com.xiaopacai.child.util

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.xiaopacai.child.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * [TASK-APP-UPDATE-V1] 更新闭环纯逻辑单元测试（JUnit + Mockito，ADR 0017）
 *
 * 覆盖验收关键点：
 * - 清单解析（/api/update/check 响应 → UpdateInfo，含缺省字段容错）
 * - 频控与跳过（D6：强制每次提示；可选「版本+每日一次」；跳过版本不再提示）
 * - 防降级（服务端版本码 ≤ 本机 → 视为最新，即使 hasUpdate=true）
 * - SHA-256 校验（已知向量 + 不匹配拒绝安装）
 * - 下载成功/失败路径（校验失败删除文件）
 */
class UpdateLogicTest {

    /** 内存版偏好存储：验证频控状态在多次调用间真实流转 */
    private val backing = mutableMapOf<String, Any>()

    /** filesDir 桩：真实临时目录（JVM 单测无 Android 私有目录），每用例新建、tearDown 清理 */
    private val tempRoot: File =
        File(System.getProperty("java.io.tmpdir"), "xpc-upd-test-${System.nanoTime()}")

    private fun mockPrefs(): SharedPreferences {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        // 双桩：本 Mockito 版本 anyString() 不匹配 null，null 与 "" 默认值分别覆盖
        `when`(prefs.getString(anyString(), anyString()))
            .thenAnswer { inv -> backing[inv.getArgument<String>(0)] as? String }
        `when`(prefs.getString(anyString(), org.mockito.ArgumentMatchers.isNull()))
            .thenAnswer { inv -> backing[inv.getArgument<String>(0)] as? String }
        `when`(prefs.getInt(anyString(), anyInt()))
            .thenAnswer { inv -> backing[inv.getArgument<String>(0)] as? Int ?: inv.getArgument<Int>(1) }
        `when`(prefs.getBoolean(anyString(), anyBoolean()))
            .thenAnswer { inv -> backing[inv.getArgument<String>(0)] as? Boolean ?: inv.getArgument<Boolean>(1) }
        `when`(editor.putString(anyString(), anyString())).thenAnswer { inv ->
            backing[inv.getArgument<String>(0)] = inv.getArgument<String>(1); editor }
        `when`(editor.putInt(anyString(), anyInt())).thenAnswer { inv ->
            backing[inv.getArgument<String>(0)] = inv.getArgument<Int>(1); editor }
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            backing[inv.getArgument<String>(0)] = inv.getArgument<Boolean>(1); editor }
        return prefs
    }

    private fun mockContext(serverHost: String = "192.168.50.99"): Context {
        val prefs = mockPrefs()
        val context = mock(Context::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(context.filesDir).thenReturn(tempRoot)
        backing["web_host"] = serverHost
        return context
    }

    @org.junit.After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun info(
        versionCode: Int = BuildConfig.VERSION_CODE + 100,
        force: Boolean = false,
        hasUpdate: Boolean = true,
        sha256: String = "ab",
    ) = UpdateManager.UpdateInfo(
        hasUpdate = hasUpdate,
        versionCode = versionCode,
        versionName = "1.2.0",
        minVersionCode = 10200,
        force = force,
        abiMissing = false,
        url = "/downloads/XiaopacaiParent-1.2.0-arm64-v8a.apk",
        sha256 = sha256,
        sizeBytes = 1024,
        changelog = "修复若干问题",
    )

    @Before
    fun setUp() {
        backing.clear()
    }

    // ==================== 清单解析 ====================

    @Test
    fun fromJson_parsesFullManifest() {
        val json = """
            {"hasUpdate":true,"latestVersionCode":10200,"latestVersionName":"1.2.0",
             "minVersionCode":10200,"force":true,"abiMissing":false,
             "url":"/downloads/app.apk","sha256":"ABCDEF","sizeBytes":12345,
             "changelog":"更新说明","publishedAt":"2026-08-22T10:00:00"}
        """.trimIndent()
        val parsed = UpdateManager.UpdateInfo.fromJson(json)
        assertTrue(parsed.hasUpdate)
        assertEquals(10200, parsed.versionCode)
        assertEquals("1.2.0", parsed.versionName)
        assertEquals(10200, parsed.minVersionCode)
        assertTrue(parsed.force)
        assertFalse(parsed.abiMissing)
        assertEquals("/downloads/app.apk", parsed.url)
        assertEquals("ABCDEF", parsed.sha256)
        assertEquals(12345L, parsed.sizeBytes)
        assertEquals("更新说明", parsed.changelog)
    }

    @Test
    fun fromJson_missingFields_defaultSafely() {
        val parsed = UpdateManager.UpdateInfo.fromJson("{}")
        assertFalse(parsed.hasUpdate)
        assertEquals(0, parsed.versionCode)
        assertEquals("", parsed.versionName)
        assertEquals("", parsed.sha256)
        assertEquals(0L, parsed.sizeBytes)
        assertFalse(parsed.force)
    }

    // ==================== 频控与跳过（D6） ====================

    @Test
    fun shouldPrompt_force_alwaysTrue() {
        val context = mockContext()
        val force = info(force = true)
        assertTrue(UpdateManager.shouldPrompt(context, force))
        UpdateManager.markPrompted(context, force) // 强制不记录，但即便记录也仍提示
        assertTrue(UpdateManager.shouldPrompt(context, force))
    }

    @Test
    fun shouldPrompt_optional_oncePerVersionPerDay() {
        val context = mockContext()
        val optional = info()
        assertTrue(UpdateManager.shouldPrompt(context, optional))
        UpdateManager.markPrompted(context, optional)
        assertFalse(UpdateManager.shouldPrompt(context, optional))
    }

    @Test
    fun shouldPrompt_optional_newerVersion_promptsAgain() {
        val context = mockContext()
        val v1 = info()
        UpdateManager.markPrompted(context, v1)
        val v2 = info(versionCode = v1.versionCode + 100)
        assertTrue(UpdateManager.shouldPrompt(context, v2))
    }

    @Test
    fun markSkipped_thatVersionNeverPromptsButNewerDoes() {
        val context = mockContext()
        val skipped = info()
        UpdateManager.markSkipped(context, skipped.versionCode)
        assertFalse(UpdateManager.shouldPrompt(context, skipped))
        // 更高版本不受跳过影响
        assertTrue(UpdateManager.shouldPrompt(context, info(versionCode = skipped.versionCode + 1)))
    }

    @Test
    fun markSkipped_olderVersionCodeDoesNotSuppressNewer() {
        val context = mockContext()
        val newer = info()
        UpdateManager.markSkipped(context, newer.versionCode - 100)
        assertTrue(UpdateManager.shouldPrompt(context, newer))
    }

    // ==================== 自动下载开关（C6 默认关） ====================

    @Test
    fun autoDownload_defaultOff_togglePersists() {
        val context = mockContext()
        assertFalse(UpdateManager.isAutoDownloadEnabled(context))
        UpdateManager.setAutoDownloadEnabled(context, true)
        assertTrue(UpdateManager.isAutoDownloadEnabled(context))
        UpdateManager.setAutoDownloadEnabled(context, false)
        assertFalse(UpdateManager.isAutoDownloadEnabled(context))
    }

    // ==================== 检查：防降级 / ABI 缺失 ====================

    private class FakeClient(
        var responseJson: String,
        var code: Int = 200,
        var downloadBytes: ByteArray = ByteArray(0),
    ) : UpdateManager.UpdateClient {
        var lastChannel: String? = null
        var lastAbi: String? = null
        var lastHost: String? = null
        var lastPort: Int = -1

        override fun check(host: String, port: Int, abi: String, versionCode: Int, channel: String): Triple<Int, String, String> {
            lastHost = host
            lastPort = port
            lastAbi = abi
            lastChannel = channel
            return Triple(code, responseJson, "")
        }

        override fun download(host: String, port: Int, path: String, destFile: File, onProgress: ((Long, Long) -> Unit)?): Long {
            destFile.writeBytes(downloadBytes)
            return downloadBytes.size.toLong()
        }
    }

    private fun checkJson(versionCode: Int, abiMissing: Boolean = false) = """
        {"hasUpdate":true,"latestVersionCode":$versionCode,"latestVersionName":"1.2.0",
         "minVersionCode":10200,"force":false,"abiMissing":$abiMissing,
         "url":"/downloads/app.apk","sha256":"","sizeBytes":1,"changelog":""}
    """.trimIndent()

    @Test
    fun check_newerVersion_returnsUpdate() = runBlocking {
        val context = mockContext()
        val newer = BuildConfig.VERSION_CODE + 100
        val fake = FakeClient(checkJson(newer))
        UpdateManager.client = fake
        val result = UpdateManager.check(context, manual = false)
        assertTrue(result is UpdateManager.CheckResult.Update)
        assertEquals(newer, (result as UpdateManager.CheckResult.Update).info.versionCode)
    }

    @Test
    fun check_sendsCurrentBuildChannel() = runBlocking {
        val context = mockContext()
        val fake = FakeClient(checkJson(BuildConfig.VERSION_CODE + 100))
        UpdateManager.client = fake
        UpdateManager.check(context, manual = false)
        // [TASK-UPDATE-CHANNEL] 检查请求必须携带本机构建渠道，服务端据此路由，杜绝跨渠道下发
        assertEquals(BuildConfig.UPDATE_CHANNEL, fake.lastChannel)
        assertEquals("arm64-v8a", fake.lastAbi) // JVM 单测无 Build.SUPPORTED_ABIS → 兜底 arm64
    }

    @Test
    fun check_equalOrLowerVersion_treatedAsUpToDate() = runBlocking {
        val context = mockContext()
        // 防降级：服务端返回 ≤ 本机版本码 → 不提示更新（哪怕 hasUpdate=true）
        UpdateManager.client = FakeClient(checkJson(BuildConfig.VERSION_CODE))
        assertTrue(UpdateManager.check(context) is UpdateManager.CheckResult.UpToDate)
        UpdateManager.client = FakeClient(checkJson(BuildConfig.VERSION_CODE - 100))
        assertTrue(UpdateManager.check(context) is UpdateManager.CheckResult.UpToDate)
    }

    @Test
    fun check_abiMissing_returnsFailedWithHint() = runBlocking {
        val context = mockContext()
        UpdateManager.client = FakeClient(checkJson(BuildConfig.VERSION_CODE + 100, abiMissing = true))
        val result = UpdateManager.check(context)
        assertTrue(result is UpdateManager.CheckResult.Failed)
        assertTrue((result as UpdateManager.CheckResult.Failed).reason.contains("暂不支持"))
    }

    @Test
    fun check_httpError_returnsFailed() = runBlocking {
        val context = mockContext()
        UpdateManager.client = FakeClient("", code = 429)
        assertTrue(UpdateManager.check(context) is UpdateManager.CheckResult.Failed)
    }

    @Test
    fun check_noServerConfig_usesDefaultHost() = runBlocking {
        // [V2.0.4] 未配置服务器地址时回退默认生产地址 xpc.winann.com:443，
        // 更新检查直接走默认服务器（不再报"未配置服务器"）
        val prefs = mockPrefs()
        val context = mock(Context::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        val fake = FakeClient(checkJson(BuildConfig.VERSION_CODE + 100))
        UpdateManager.client = fake
        val result = UpdateManager.check(context)
        assertTrue(result is UpdateManager.CheckResult.Update)
        assertEquals("xpc.winann.com", fake.lastHost)
        assertEquals(443, fake.lastPort)
    }

    // ==================== SHA-256 校验 ====================

    // ==================== 安装结果显式化（TASK-UPDATE-DEADLOCK-FIX） ====================

    @Test
    fun installApk_signatureMismatch_returnsSignatureMismatch() {
        // 回归：跨签名更新包必须显式返回 SignatureMismatch（旧逻辑静默 false →
        // 强制更新弹窗无提示反复出现，真机死锁不可见）
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("com.xiaopacai.child")
        val self = PackageInfo()
        self.signatures = arrayOf(Signature(byteArrayOf(1, 2, 3)))
        `when`(pm.getPackageInfo(anyString(), anyInt())).thenReturn(self)
        // 目标 APK 无法解析出证书 → 安全拒绝
        `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(null)
        val apk = File.createTempFile("upd-mismatch", ".apk")
        try {
            apk.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            assertTrue(UpdateManager.installApk(context, apk) is UpdateManager.InstallResult.SignatureMismatch)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun installApk_missingFile_returnsFailedWithReason() {
        val context = mock(Context::class.java)
        val missing = File(tempRoot, "not-exists.apk")
        val result = UpdateManager.installApk(context, missing)
        assertTrue(result is UpdateManager.InstallResult.Failed)
        assertTrue((result as UpdateManager.InstallResult.Failed).reason.isNotBlank())
    }

    @Test
    fun sha256Of_emptyFile_matchesKnownVector() {
        val f = File.createTempFile("upd", ".apk")
        try {
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                UpdateManager.sha256Of(f)
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun sha256Of_knownContent_matchesKnownVector() {
        val f = File.createTempFile("upd", ".apk")
        try {
            f.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateManager.sha256Of(f)
            )
        } finally {
            f.delete()
        }
    }

    // ==================== 下载：SHA-256 不匹配拒绝 ====================

    @Test
    fun downloadApk_shaMismatch_deletesFileReturnsNull() = runBlocking {
        val context = mockContext()
        val correctHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        UpdateManager.client = FakeClient("", downloadBytes = "abc".toByteArray())
        // 期望哈希错误 → 校验失败，删除文件、返回 null
        val bad = info(sha256 = correctHash.reversed())
        assertNull(UpdateManager.downloadApk(context, bad))
        val dir = UpdateManager.updateDir(context)
        assertTrue(dir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun downloadApk_shaMatches_returnsFile() = runBlocking {
        val context = mockContext()
        val correctHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        UpdateManager.client = FakeClient("", downloadBytes = "abc".toByteArray())
        val good = info(sha256 = correctHash)
        val file = UpdateManager.downloadApk(context, good)
        assertNotNull(file)
        assertTrue(file!!.exists())
        // 下载成功记录 → 可从 prefs 定位（下载完成通知点击直达安装路径）
        assertEquals(file.absolutePath, UpdateManager.lastDownloadedApk(context, good.versionCode)?.absolutePath)
        file.delete()
        Unit
    }

    @Test
    fun lastDownloadedApk_versionMismatch_returnsNull() = runBlocking {
        val context = mockContext()
        val correctHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        UpdateManager.client = FakeClient("", downloadBytes = "abc".toByteArray())
        val file = UpdateManager.downloadApk(context, info(sha256 = correctHash))
        assertNotNull(file)
        // 其它版本码找不到该包
        assertNull(UpdateManager.lastDownloadedApk(context, BuildConfig.VERSION_CODE + 999))
        file!!.delete()
        Unit
    }
}
