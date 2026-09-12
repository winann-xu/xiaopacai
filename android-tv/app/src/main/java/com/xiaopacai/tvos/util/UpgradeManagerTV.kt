// [android-tv] 应用内升级 —— B10
// 小趴菜 TVOS 版
//
// 链路（后端 DeviceApiController）：
//   GET /api/v1/device/upgrade-check（设备令牌）→ { version, downloadUrl, forceUpdate, changelog }
//   注意：服务端只回「该平台最新已发布版本」，是否更新由客户端比较版本号决定；
//        没有已发布版本时 downloadUrl 为空 ⇒ 视为已是最新。
// 安装：
//   1) 设备所有者 → PackageInstaller 静默安装（电视上不用遥控器点系统安装向导）
//   2) 否则回退到系统安装器（ACTION_VIEW + FileProvider）
//
// 服务器上的 AppUpdates 表需要存在 platform="android-tv" 的已发布记录，否则恒为「已是最新」。

package com.xiaopacai.tvos.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver
import com.xiaopacai.tvos.service.UpgradeInstallReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

object UpgradeManagerTV {

    private const val TAG = "UpgradeManagerTV"
    private const val API_UPGRADE_CHECK = "/api/v1/device/upgrade-check"
    private const val APK_NAME = "app-update.apk"

    sealed class State {
        data class Available(
            val version: String,
            val downloadUrl: String,
            val forceUpdate: Boolean,
            val changelog: String
        ) : State()

        data class UpToDate(val version: String) : State()
        data class Failed(val reason: String) : State()
    }

    /** 当前已安装版本名 */
    fun currentVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    /** 检查更新（设备令牌通道，无需家长 JWT） */
    suspend fun check(context: Context): State {
        val token = SharedPreferenceHelperTV.getDeviceToken(context).first()
        if (token.isBlank()) return State.Failed("设备未注册（无令牌）")
        val host = SharedPreferenceHelperTV.getCloudHost(context).first()
        val port = SharedPreferenceHelperTV.getCloudPort(context).first()
        val url = NetworkHelper.buildBaseUrl(host, port) + API_UPGRADE_CHECK
        val (code, body) = suspendCancellableCoroutine<Pair<Int, String>> { cont ->
            NetworkHelper.request("GET", url, null, token) { c, b ->
                if (cont.isActive) cont.resume(c to b)
            }
        }
        if (code !in 200..299) {
            AppLogTV.w(TAG, "检查更新失败 HTTP $code")
            return State.Failed("检查更新失败（HTTP $code）")
        }
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return State.Failed("响应解析失败")
        val version = obj.optString("version", "")
        val downloadUrl = obj.optString("downloadUrl", "")
        val changelog = obj.optString("changelog", "")
        val force = obj.optBoolean("forceUpdate", false)
        val current = currentVersion(context)
        AppLogTV.i(TAG, "检查更新：当前 $current，服务端最新 $version")
        return if (downloadUrl.isBlank() || version.isBlank() || version == current) {
            State.UpToDate(version.ifBlank { current })
        } else {
            State.Available(version, downloadUrl, force, changelog)
        }
    }

    /** 下载 APK 到应用私有目录 */
    suspend fun download(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "upgrade").apply { mkdirs() }
            val target = File(dir, APK_NAME)
            if (target.exists()) target.delete()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            AppLogTV.i(TAG, "升级包已下载：${target.length()} 字节")
            target
        }.onFailure {
            AppLogTV.e(TAG, "升级包下载失败：${it.message}")
        }.getOrNull()
    }

    /**
     * 安装 APK：设备所有者走静默安装，否则回退系统安装器。
     * @return true 表示已提交安装流程
     */
    fun install(context: Context, apk: File): Boolean {
        if (GuardianDeviceAdminReceiver.isDeviceOwner(context)) {
            if (silentInstall(context, apk)) return true
            AppLogTV.w(TAG, "静默安装失败，回退系统安装器")
        }
        return openInstaller(context, apk)
    }

    /** 设备所有者静默安装（遥控器上不用点系统向导） */
    private fun silentInstall(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpgradeInstallReceiver::class.java)
                .setAction(UpgradeInstallReceiver.ACTION_RESULT)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        AppLogTV.i(TAG, "已提交静默安装（PackageInstaller）")
        true
    }.onFailure {
        AppLogTV.e(TAG, "静默安装异常：${it.message}")
    }.getOrDefault(false)

    /** 回退：交给系统安装器 */
    private fun openInstaller(context: Context, apk: File): Boolean = runCatching {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        } else {
            Uri.fromFile(apk)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        AppLogTV.i(TAG, "已拉起系统安装器")
        true
    }.onFailure {
        AppLogTV.e(TAG, "拉起系统安装器失败：${it.message}")
    }.getOrDefault(false)
}
