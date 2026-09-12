// [android-tv] 应用类 — 全局初始化 + SQLCipher 初始化
package com.xiaopacai.tvos

import android.app.Application
import net.sqlcipher.database.SQLiteDatabase

/**
 * 小趴菜 TVOS 版 — 全局 Application
 * 负责 SQLCipher 加密库初始化、全局依赖注入、
 * 后台保活策略注册等
 */
class XiaopacaiTVApp : Application() {

    companion object {
        // 全局访问
        lateinit var INSTANCE: XiaopacaiTVApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this

        // SQLCipher 加密数据库初始化
        SQLiteDatabase.loadLibs(this)

        // 【项8】日志缓冲初始化（落盘需要应用级 context）+ 安装崩溃捕获
        com.xiaopacai.tvos.util.AppLogTV.init(this)
        // 【项8】安装崩溃捕获（脱敏后记入待上送缓冲，供 diagnostics-report 的 recentCrashes 使用）
        com.xiaopacai.tvos.util.AppLogTV.installCrashHandler(this)
        // 【DO】若本应用是设备所有者，启动即兑现「真正禁止卸载」
        com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver.applyDeviceOwnerPolicies(this)
        com.xiaopacai.tvos.util.AppLogTV.i(
            "App",
            "设备所有者=" + com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver.isDeviceOwner(this)
        )
        com.xiaopacai.tvos.util.AppLogTV.i(
            "App",
            "小趴菜电视端启动 version=${runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"} sdk=${android.os.Build.VERSION.SDK_INT}"
        )
    }
}
