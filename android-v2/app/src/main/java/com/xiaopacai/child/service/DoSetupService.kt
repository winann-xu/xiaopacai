package com.xiaopacai.child.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiaopacai.child.ui.strict.StrictProvisionActivity
import com.xiaopacai.child.util.AppLog

object DoSetupService {

    private const val TAG = "DoSetupService"
    private const val PREFS_NAME = "do_setup_prefs"
    private const val KEY_DO_ACTIVE = "do_active_checked"

    fun isDeviceOwner(context: Context): Boolean {
        return try {
            GuardianDeviceAdminReceiver.getDpm(context)
                .isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) { false }
    }

    fun startDoSetup(context: Context) {
        AppLog.i(TAG, "启动 DO 授权流程")
        context.startActivity(Intent(context, StrictProvisionActivity::class.java))
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DO_ACTIVE, isDeviceOwner(context))
            .apply()
    }
}
