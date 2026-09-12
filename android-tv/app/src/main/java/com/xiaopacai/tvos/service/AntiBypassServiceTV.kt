// [android-tv] 防绕过服务 — AntiBypassServiceTV
// 小趴菜 TVOS 版 — 防止应用被卸载/禁用/切换

package com.xiaopacai.tvos.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.xiaopacai.tvos.util.NetworkHelper
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import kotlinx.coroutines.*

/**
 * 防绕过服务 — 持续监控关键系统事件
 * 防止家长/孩子通过以下方式绕过限制：
 *  1. 卸载应用
 *  2. 禁用应用
 *  3. 清除应用数据
 *  4. 切换用户/访客模式
 *  5. 关闭网络
 *  6. 飞行模式
 */
class AntiBypassServiceTV : Service() {

    companion object {
        private const val TAG = "AntiBypassSvcTV"
        private const val MONITOR_INTERVAL_MS = 5_000L  // 5 秒检查一次
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 包管理器注册表（首次扫描时的已安装应用列表）
    private var registeredPackages: Set<String> = emptySet()

    // 包变更广播接收器
    internal val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_REMOVED -> {
                    val pkg = intent.data?.schemeSpecificPart
                    if (pkg != null) onPackageRemoved(pkg)
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    val pkg = intent.data?.schemeSpecificPart
                    if (pkg != null) onPackageUpdated(pkg)
                }
                Intent.ACTION_PACKAGE_ADDED -> {
                    val pkg = intent.data?.schemeSpecificPart
                    if (pkg != null) onPackageAdded(pkg)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 注册包变更监听
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }

        // 启动监控循环
        startMonitoring()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 监控逻辑 ====================

    /**
     * 启动持续监控
     */
    private fun startMonitoring() {
        scope.launch {
            // 获取初始应用列表
            registeredPackages = getInstalledPackages()

            while (isActive) {
                try {
                    checkAppIntegrity()
                    checkNetworkStatus()
                    checkUserSession()
                } catch (e: Exception) {
                    Log.e(TAG, "监控检查异常", e)
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    /**
     * 检查应用完整性（是否被卸载/禁用）
     */
    private fun checkAppIntegrity() {
        val currentPackages = getInstalledPackages()

        // 检测是否有重要应用被移除
        val removed = registeredPackages - currentPackages
        val added = currentPackages - registeredPackages

        if (removed.isNotEmpty() || added.isNotEmpty()) {
            Log.i(TAG, "检测到包变更: removed=$removed, added=$added")
            // 重新扫描并更新注册表
            registeredPackages = currentPackages
        }
    }

    /**
     * 检查网络状态
     */
    private fun checkNetworkStatus() {
        if (!NetworkHelper.isNetworkAvailable()) {
            Log.w(TAG, "网络不可用，云端同步将暂停")
        }
    }

    /**
     * 检查用户会话（电视多用户模式）
     */
    private fun checkUserSession() {
        // TODO: 检查当前用户是否被切换
        // android.app.UiModeManager / UserManager
    }

    // ==================== 事件处理 ====================

    /**
     * 应用被卸载（不应发生）
     */
    private fun onPackageRemoved(pkg: String) {
        if (pkg == packageName) {
            Log.e(TAG, "⚠️ 小趴菜 TV 被卸载！")
            // 无法恢复已卸载的应用，但会记录日志
            // 实际防卸载应使用 DevicePolicyManager
        }
    }

    /**
     * 应用被更新
     */
    private fun onPackageUpdated(pkg: String) {
        if (pkg == packageName) {
            Log.i(TAG, "应用已更新，重新初始化")
            // 重新初始化服务
        }
    }

    /**
     * 新应用安装
     */
    private fun onPackageAdded(pkg: String) {
        // 可选：自动扫描并添加到已安装应用列表
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取所有已安装应用的包名
     */
    private fun getInstalledPackages(): Set<String> {
        return try {
            packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
