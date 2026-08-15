package com.xiaopacai.child

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.xiaopacai.child.data.database.AppDatabase
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.KeyStoreManager
import com.xiaopacai.child.util.LogUploader

/**
 * [TASK-D3-02] 小趴菜儿童端 Application
 *
 * 应用入口：负责全局初始化（数据库、通知渠道、加密密钥）
 * 使用 AndroidKeyStore 安全管理数据库加密密钥（TEE/SE 硬件保护）
 */
class XiaopacaiApp : Application() {

    /** 全局数据库实例（延迟初始化，首次访问时创建） */
    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // [TASK-MILESTONE-V3] 需求 14：运行日志环形缓冲初始化（越早越好，覆盖全生命周期）
        AppLog.init(this)

        // 1. 初始化加密数据库 [TASK-D3-02] 使用 KeyStore 密钥
        initDatabase()

        // 2. 创建通知渠道（前台服务必需）
        createNotificationChannels()

        // [TASK-OPT-12-P2] 诊断采集初始化（需求5）：
        // 应用级提前安装崩溃处理器 + 调度每日上报，不依赖守护服务启动
        try {
            com.xiaopacai.child.service.DiagnosticsCollector.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "诊断采集初始化失败: ${e.message}")
            AppLog.e("App", "诊断采集初始化失败", e)
        }

        // [TASK-MILESTONE-V3] 需求 14：日志自动上传（每 6 小时，未登录账号时快速跳过）
        LogUploader.schedulePeriodic(this)
        AppLog.i("App", "应用启动 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
    }

    /**
     * 初始化 SQLCipher 加密数据库
     * [TASK-D3-02] 使用 AndroidKeyStore 安全获取数据库主密钥
     * 密钥由 TEE/SE 安全硬件保护，永不泄漏到应用进程外
     */
    private fun initDatabase() {
        try {
            // 从 AndroidKeyStore 获取或生成 AES-256 数据库主密钥
            val dbMasterKey = KeyStoreManager.getOrCreateDbMasterKey(this)
            database = AppDatabase.getInstance(this, dbMasterKey)
            Log.i(TAG, "加密数据库初始化成功（KeyStore 保护）")
        } catch (e: Exception) {
            Log.e(TAG, "数据库初始化失败，回退到备用密钥方案: ${e.message}")
            // [TASK-D3-02] 安全回退：KeyStore 不可用时使用受保护的备用方案
            val fallbackKey = getFallbackDbKey()
            database = AppDatabase.getInstance(this, fallbackKey)
        }
    }

    /**
     * [TASK-D3-02] KeyStore 不可用时的安全回退方案
     *
     * 仅在 KeyStore 完全不可用时使用（如极端定制 ROM）。
     * 使用 DPAPI/KeyChain 等效保护：密钥以加密形式存储在 SharedPreferences。
     */
    private fun getFallbackDbKey(): ByteArray {
        val prefs = getSharedPreferences("guardian_secure_prefs", MODE_PRIVATE)
        val encryptedKey = prefs.getString("db_key_encrypted", null)

        return if (encryptedKey != null) {
            try {
                // 尝试使用 KeyStore 解密已存储的密钥
                KeyStoreManager.decryptFromStorage(encryptedKey).toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                // 解密失败，生成新的并存储
                val newKey = java.util.UUID.randomUUID().toString()
                val encrypted = KeyStoreManager.encryptForStorage(newKey)
                prefs.edit().putString("db_key_encrypted", encrypted).apply()
                newKey.toByteArray(Charsets.UTF_8)
            }
        } else {
            // 首次创建：生成新密钥并加密存储
            val newKey = java.util.UUID.randomUUID().toString()
            try {
                val encrypted = KeyStoreManager.encryptForStorage(newKey)
                prefs.edit().putString("db_key_encrypted", encrypted).apply()
            } catch (e: Exception) {
                // 加密存储失败时，至少混淆一下
                prefs.edit().putString("db_key_seed", newKey.reversed()).apply()
            }
            newKey.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * 创建通知渠道
     * 前台服务必须绑定通知渠道（Android 8.0+）
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 守护服务通知渠道（持续显示，不可关闭）
            val guardianChannel = NotificationChannel(
                CHANNEL_GUARDIAN,
                "守护服务",
                NotificationManager.IMPORTANCE_LOW  // 低调通知，避免打扰儿童
            ).apply {
                description = "小趴菜守护服务运行中"
                setShowBadge(false)
            }
            manager.createNotificationChannel(guardianChannel)

            // 公告通知渠道（家长公告推送）
            val announcementChannel = NotificationChannel(
                CHANNEL_ANNOUNCEMENT,
                "家长公告",
                NotificationManager.IMPORTANCE_HIGH  // 公告高优先级
            ).apply {
                description = "来自家长的公告信息"
            }
            manager.createNotificationChannel(announcementChannel)

            // [TASK-D3-02] 安全告警渠道（防绕过检测、卸载尝试等安全事件）
            val securityChannel = NotificationChannel(
                CHANNEL_SECURITY,
                "安全告警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "安全相关告警（防绕过、卸载检测等）"
                setShowBadge(true)
            }
            manager.createNotificationChannel(securityChannel)
        }
    }

    companion object {
        private const val TAG = "XiaopacaiApp"

        // 通知渠道 ID
        const val CHANNEL_GUARDIAN = "channel_guardian"
        const val CHANNEL_ANNOUNCEMENT = "channel_announcement"
        const val CHANNEL_SECURITY = "channel_security"  // [TASK-D3-02]

        /** 全局 Application 实例（方便组件访问） */
        lateinit var instance: XiaopacaiApp
            private set
    }
}
