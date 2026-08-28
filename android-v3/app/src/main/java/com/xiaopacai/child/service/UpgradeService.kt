package com.xiaopacai.child.service

import android.content.Context
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.UpdateManager
import com.xiaopacai.child.BuildConfig

object UpgradeService {

    private const val TAG = "UpgradeService"
    private const val PREFS_NAME = "upgrade_prefs"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED_VERSION = "skipped_version"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val changelog: String,
        val downloadUrl: String,
        val force: Boolean,
        val sizeBytes: Long
    )

    /**
     * [TASK-V208-UNBIND-FIX] 自动升级页检查更新。
     * 此前调用 /api/v1/device/update-check，既不传渠道也不比对版本号，
     * 导致 special（testkey）设备把同版本号 stable 包误报为“新版本”。
     * 现复用渠道感知的 /api/update/check（abi + versionCode + channel），
     * 并按 versionCode 防降级比对，只有真正更高版本才返回更新信息。
     */
    fun checkForUpdate(context: Context): UpdateInfo? {
        val host = com.xiaopacai.child.util.CloudAccountManager.getServerHost(context) ?: return null
        val port = com.xiaopacai.child.util.CloudAccountManager.getServerPort(context)
        return try {
            val (code, resp, _) = UpdateManager.client.check(
                host, port,
                UpdateManager.currentAbi(),
                BuildConfig.VERSION_CODE,
                BuildConfig.UPDATE_CHANNEL
            )
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()
            if (code !in 200..299) return null
            val info = UpdateManager.UpdateInfo.fromJson(resp)
            if (!info.hasUpdate || info.abiMissing || info.versionCode <= BuildConfig.VERSION_CODE) return null
            UpdateInfo(
                versionName = info.versionName,
                versionCode = info.versionCode,
                changelog = info.changelog,
                downloadUrl = info.url,
                force = info.force,
                sizeBytes = info.sizeBytes
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "更新检查失败: ${e.message}")
            null
        }
    }

    fun isSkipped(context: Context, versionCode: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SKIPPED_VERSION, 0) == versionCode
    }

    fun markSkipped(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SKIPPED_VERSION, versionCode)
            .apply()
    }

    fun shouldCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        return System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS
    }
}
