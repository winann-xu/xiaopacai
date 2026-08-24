package com.xiaopacai.child.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.xiaopacai.child.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [TASK-APP-UPDATE-V1] App 自动更新闭环（ADR 0017）：
 * - 检查：GET /api/update/check（HTTPS 优先，复用 CloudHttp 通道）
 * - 频控：可选更新按「版本 + 每日一次」去重；强制更新每次提示
 * - 下载：应用私有目录 + 边下边报进度 + 下载完成 SHA-256 校验（失败拒绝安装）
 * - 安装：PackageInstaller session 为主，FileProvider+ACTION_VIEW 兜底；
 *   未知来源权限（REQUEST_INSTALL_PACKAGES）缺失时引导用户开启
 * - 数据保留红线：升级不动任何本地数据；MY_PACKAGE_REPLACED 仅做权限自检（GuardianEventReceiver 既有行为）
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    const val PREFS_UPDATE = "xiaopacai_update_prefs"
    private const val KEY_PROMPT_DATE_VERSION = "prompt_date_version"
    private const val KEY_SKIPPED_VERSION = "skipped_version_code"
    private const val KEY_AUTO_DOWNLOAD = "auto_download_enabled"
    private const val KEY_LAST_APK = "last_apk_path"
    private const val KEY_LAST_APK_NAME = "last_apk_version_name"

    /** 更新包存放目录（应用私有，卸载即清；升级不清理） */
    fun updateDir(context: Context): File =
        File(context.filesDir, "app-updates").apply { mkdirs() }

    /** 本设备 ABI（Build.SUPPORTED_ABIS[0]，v7a 设备运行 32 位包；JVM 单测下为空 → 默认 arm64） */
    fun currentAbi(): String = Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"

    // ==================== 更新信息 ====================

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val versionCode: Int,
        val versionName: String,
        val minVersionCode: Int,
        val force: Boolean,
        val abiMissing: Boolean,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val changelog: String,
    ) {
        companion object {
            fun fromJson(json: String): UpdateInfo {
                val obj = JSONObject(json)
                return UpdateInfo(
                    hasUpdate = obj.optBoolean("hasUpdate", false),
                    versionCode = obj.optInt("latestVersionCode", 0),
                    versionName = obj.optString("latestVersionName", ""),
                    minVersionCode = obj.optInt("minVersionCode", 0),
                    force = obj.optBoolean("force", false),
                    abiMissing = obj.optBoolean("abiMissing", false),
                    url = obj.optString("url", ""),
                    sha256 = obj.optString("sha256", ""),
                    sizeBytes = obj.optLong("sizeBytes", 0),
                    changelog = obj.optString("changelog", ""),
                )
            }
        }
    }

    sealed class CheckResult {
        /** 有更新且可升级 */
        data class Update(val info: UpdateInfo) : CheckResult()

        /** 已是最新 */
        object UpToDate : CheckResult()

        /** 无网络/服务端异常（不弹窗，仅手动检查时提示） */
        data class Failed(val reason: String) : CheckResult()
    }

    /** 网络检查客户端（可注入替换，便于单元测试） */
    interface UpdateClient {
        /** @return Triple(HTTP 状态码, 响应体, 错误体) */
        fun check(host: String, port: Int, abi: String, versionCode: Int, channel: String): Triple<Int, String, String>

        /** 流式下载到 destFile，返回文件字节数（进度回调供 UI/通知展示） */
        fun download(host: String, port: Int, path: String, destFile: File, onProgress: ((Long, Long) -> Unit)?): Long
    }

    var client: UpdateClient = HttpUpdateClient

    /** 默认实现：复用 CloudHttp 的 HTTPS 优先通道 */
    private object HttpUpdateClient : UpdateClient {
        override fun check(host: String, port: Int, abi: String, versionCode: Int, channel: String): Triple<Int, String, String> {
            val path = "/api/update/check?platform=android&abi=$abi&versionCode=$versionCode&channel=$channel"
            return httpGetJson(host, port, path, null)
        }

        override fun download(host: String, port: Int, path: String, destFile: File, onProgress: ((Long, Long) -> Unit)?): Long {
            return httpDownloadFile(host, port, path, destFile, onProgress)
        }
    }

    // ==================== 检查 ====================

    /**
     * 检查更新。manual=true（关于页按钮）失败时透出错误文案；静默检查失败不打扰。
     */
    suspend fun check(context: Context, manual: Boolean = false): CheckResult =
        withContext(Dispatchers.IO) {
            val host = CloudAccountManager.getServerHost(context) ?: run {
                return@withContext CheckResult.Failed("未配置服务器地址")
            }
            val port = CloudAccountManager.getServerPort(context)
            try {
                val (code, resp, err) = client.check(host, port, currentAbi(), BuildConfig.VERSION_CODE, BuildConfig.UPDATE_CHANNEL)
                if (code !in 200..299) {
                    return@withContext CheckResult.Failed(parseError(err) ?: "检查失败（HTTP $code）")
                }
                val info = UpdateInfo.fromJson(resp)
                when {
                    !info.hasUpdate -> CheckResult.UpToDate
                    info.abiMissing -> CheckResult.Failed("新版本暂不支持本设备，请留意后续版本")
                    info.versionCode <= BuildConfig.VERSION_CODE -> CheckResult.UpToDate // 防降级兜底
                    else -> CheckResult.Update(info)
                }
            } catch (e: CloudConnectionException) {
                val reason = when (e.kind) {
                    CloudConnectionException.Kind.NO_NETWORK -> "网络不可用"
                    CloudConnectionException.Kind.CANNOT_CONNECT -> "无法连接服务器"
                    CloudConnectionException.Kind.HTTPS_REQUIRED -> "服务器未启用 HTTPS"
                }
                CheckResult.Failed(if (manual) reason else "静默检查失败")
            } catch (e: Exception) {
                CheckResult.Failed(if (manual) "检查失败：${e.message}" else "静默检查失败")
            }
        }

    private fun parseError(errBody: String): String? {
        return try {
            JSONObject(errBody).optString("error", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 频控与跳过 ====================

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * 是否允许弹窗提示本次更新：
     * - 强制更新：总是允许（每次进入家长端提示，D6）
     * - 可选更新：版本+日期每日一次；被「跳过此版本」的版本不再提示
     */
    fun shouldPrompt(context: Context, info: UpdateInfo): Boolean {
        if (info.force) return true
        val p = prefs(context)
        if (p.getInt(KEY_SKIPPED_VERSION, 0) >= info.versionCode) return false
        return p.getString(KEY_PROMPT_DATE_VERSION, "") != "${todayKey()}:${info.versionCode}"
    }

    /** 记录本次已弹窗（可选更新日频控）；强制更新不记录 */
    fun markPrompted(context: Context, info: UpdateInfo) {
        if (info.force) return
        prefs(context).edit().putString(KEY_PROMPT_DATE_VERSION, "${todayKey()}:${info.versionCode}").apply()
    }

    /** 跳过此版本（仅可选更新） */
    fun markSkipped(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_SKIPPED_VERSION, versionCode).apply()
    }

    // ==================== 自动下载开关 ====================

    fun isAutoDownloadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_DOWNLOAD, false)

    fun setAutoDownloadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_DOWNLOAD, enabled).apply()
    }

    // ==================== 下载 + 校验 ====================

    /**
     * 下载 APK 到应用私有目录，完成后校验 SHA-256。
     * @return 校验通过返回文件，失败返回 null（文件已删除，可重试）
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        val host = CloudAccountManager.getServerHost(context) ?: return@withContext null
        val port = CloudAccountManager.getServerPort(context)
        val dest = File(updateDir(context), "app-${info.versionCode}-${currentAbi()}.apk")
        if (dest.exists() && dest.length() > 0) dest.delete()
        try {
            client.download(host, port, info.url, dest, onProgress)
            val actual = sha256Of(dest)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                Log.e(TAG, "SHA-256 校验失败，拒绝安装: expected=${info.sha256.take(16)} actual=${actual.take(16)}")
                dest.delete()
                return@withContext null
            }
            prefs(context).edit()
                .putString(KEY_LAST_APK, dest.absolutePath)
                .putString(KEY_LAST_APK_NAME, info.versionName)
                .apply()
            dest
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 界面关闭/协程取消：清理半成品后向上传递取消（不视为失败）
            dest.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}", e)
            dest.delete()
            null
        }
    }

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(81920)
            var read: Int
            while (input.read(buf).also { read = it } > 0) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 最近一次下载成功（且已通过 SHA-256 校验）的 APK。
     * 下载完成通知点击后据此直接安装，避免重复下载；文件不存在则返回 null 走正常检查流程。
     */
    fun lastDownloadedApk(context: Context, versionCode: Int): File? {
        val saved = prefs(context).getString(KEY_LAST_APK, null) ?: return null
        val file = File(saved)
        if (!file.exists() || file.length() == 0L) return null
        return if (file.name.contains("app-$versionCode-")) file else null
    }

    /** 上次下载成功 APK 对应的版本名（离线/检查失败时兜底展示） */
    fun lastDownloadedVersionName(context: Context, versionCode: Int): String? {
        val file = lastDownloadedApk(context, versionCode) ?: return null
        return prefs(context).getString(KEY_LAST_APK_NAME, null)
    }

    // ==================== 安装 ====================

    /**
     * 安装 APK：PackageInstaller session 为主（minSdk 26 全程支持），
     * 异常/不支持时回退 FileProvider + ACTION_VIEW（系统安装器，用户可见确认）。
     * 平台边界：非系统应用无法静默安装，最终都有一次系统确认（ADR 0017）。
     * @return true=已发起安装（session commit 或 ACTION_VIEW 已弹出）
     */
    fun installApk(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        if (!isSameSignerAsSelf(context, file)) {
            Log.e(TAG, "签名校验失败：更新包与本机渠道签名不一致，拒绝安装（防跨渠道顶替）")
            return false
        }
        return try {
            if (installViaSession(context, file)) return true
            installViaActionView(context, file)
        } catch (e: Exception) {
            Log.e(TAG, "session 安装失败，回退 ACTION_VIEW: ${e.message}", e)
            try {
                installViaActionView(context, file)
            } catch (e2: Exception) {
                Log.e(TAG, "ACTION_VIEW 安装失败: ${e2.message}", e2)
                false
            }
        }
    }

    /**
     * [TASK-UPDATE-CHANNEL] 渠道隔离兜底：更新包签名证书必须与本机安装包完全一致。
     * 服务端按渠道路由是第一道防线，此处防服务端误配/中间人/伪造同渠道清单导致跨签名安装。
     * 任何解析异常一律拒绝（安全优先），避免把 stable/special 互相覆盖。
     */
    fun isSameSignerAsSelf(context: Context, apk: File): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val selfInfo = pm.getPackageInfo(context.packageName, flags)
            val selfSigs = if (Build.VERSION.SDK_INT >= 28) {
                selfInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                selfInfo.signatures
            }
            val selfSet = selfSigs?.mapNotNull { certSha256(it) }?.toSet().orEmpty()
            if (selfSet.isEmpty()) return false

            val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
            val targetSigs = if (Build.VERSION.SDK_INT >= 28) {
                archive.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                archive.signatures
            }
            val targetSet = targetSigs?.mapNotNull { certSha256(it) }?.toSet().orEmpty()
            targetSet.isNotEmpty() && targetSet == selfSet
        } catch (e: Exception) {
            Log.e(TAG, "签名一致性校验异常，拒绝安装: ${e.message}", e)
            false
        }
    }

    private fun certSha256(sig: Signature): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 未知来源权限是否已允许（targetSdk 26+ 安装前必须检查） */
    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** 引导用户开启「允许安装未知来源应用」 */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // 极旧/定制系统无此设置页：退到应用详情
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(fallback) } catch (_: Exception) { /* 无详情页则放弃引导 */ }
        }
    }

    private fun installViaSession(context: Context, file: File): Boolean {
        if (!canRequestPackageInstalls(context)) return false
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                // 落盘后再 commit，避免系统读取不完整包
                (out as? java.io.FileOutputStream)?.fd?.sync()
            }
            val pending = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java)
                    .putExtra("session_id", sessionId)
                    .putExtra("version_code", 0),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pending.intentSender)
            return true
        } catch (e: Exception) {
            try { session.abandon() } catch (_: Exception) { }
            throw e
        } finally {
            try { session.close() } catch (_: Exception) { }
        }
    }

    private fun installViaActionView(context: Context, file: File): Boolean {
        if (!canRequestPackageInstalls(context)) return false
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.updatefileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}

/**
 * [TASK-APP-UPDATE-V1] PackageInstaller session 结果广播：
 * 安装成功 → 通知提醒（进程即将重启，守护自启恢复）；失败 → 提示可重试。
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i("UpdateManager", "APK 安装会话成功（系统确认后由系统完成安装）")
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                Log.i("UpdateManager", "等待用户确认安装")
            else ->
                Log.w("UpdateManager", "安装会话失败: status=$status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
