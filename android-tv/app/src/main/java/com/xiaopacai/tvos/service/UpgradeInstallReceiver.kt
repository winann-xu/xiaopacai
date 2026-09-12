// [android-tv] 安装结果回调（B10 静默安装用）
package com.xiaopacai.tvos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.xiaopacai.tvos.util.AppLogTV

class UpgradeInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESULT = "com.xiaopacai.tvos.UPGRADE_INSTALL_RESULT"
        private const val TAG = "UpgradeInstall"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                AppLogTV.i(TAG, "静默安装成功（新版已生效）")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 需要用户确认（非设备所有者时会出现）：把系统的确认页拉起来
                AppLogTV.w(TAG, "安装需要用户确认，转交系统向导")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            else -> AppLogTV.e(TAG, "静默安装失败 status=$status msg=$message")
        }
    }
}
