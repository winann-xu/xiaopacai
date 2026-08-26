package com.xiaopacai.child.service

import android.content.Context
import android.util.Log
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.CloudAccountManager
import com.xiaopacai.child.util.httpGetJson
import org.json.JSONObject

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

    fun checkForUpdate(context: Context): UpdateInfo? {
        val token = CloudAccountManager.getToken(context)
        return try {
            val (code, resp, err) = httpGetJson(CloudSyncService.CLOUD_HOST,
                CloudSyncService.CLOUD_PORT, "/api/v1/device/update-check", token)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()
            if (code in 200..299) {
                val json = JSONObject(resp)
                val hasUpdate = json.optBoolean("hasUpdate", false)
                if (hasUpdate) {
                    val info = json.optJSONObject("update") ?: return null
                    UpdateInfo(
                        versionName = info.optString("versionName", ""),
                        versionCode = info.optInt("versionCode", 0),
                        changelog = info.optString("changelog", ""),
                        downloadUrl = info.optString("downloadUrl", ""),
                        force = info.optBoolean("force", false),
                        sizeBytes = info.optLong("sizeBytes", 0)
                    )
                } else null
            } else null
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
