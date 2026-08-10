package com.xiaopacai.child

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.xiaopacai.child.data.database.AppDatabase

/**
 * [TASK-D1-02] 小趴菜儿童端 Application
 *
 * 应用入口：负责全局初始化（数据库、通知渠道、加密密钥）
 */
class XiaopacaiApp : Application() {

    /** 全局数据库实例（延迟初始化，首次访问时创建） */
    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. 初始化加密数据库
        initDatabase()

        // 2. 创建通知渠道（前台服务必需）
        createNotificationChannels()
    }

    /**
     * 初始化 SQLCipher 加密数据库
     * 首次创建时自动生成加密密钥并安全存储
     */
    private fun initDatabase() {
        // 获取或生成数据库加密密钥（实际项目中应从 KeyStore 获取）
        val dbPassphrase = getOrCreateDbPassphrase()
        database = AppDatabase.getInstance(this, dbPassphrase)
    }

    /**
     * 获取或创建数据库加密密码
     * TODO: 集成 AndroidKeyStore 安全生成与存储密钥
     */
    private fun getOrCreateDbPassphrase(): ByteArray {
        // 临时实现：使用设备 ID 派生密钥
        // 正式版应使用 AndroidKeyStore + AES-GCM
        val prefs = getSharedPreferences("guardian_prefs", MODE_PRIVATE)
        var key = prefs.getString("db_key_seed", null)
        if (key == null) {
            key = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("db_key_seed", key).apply()
        }
        return key.toByteArray(Charsets.UTF_8)
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
        }
    }

    companion object {
        // 通知渠道 ID
        const val CHANNEL_GUARDIAN = "channel_guardian"
        const val CHANNEL_ANNOUNCEMENT = "channel_announcement"

        /** 全局 Application 实例（方便组件访问） */
        lateinit var instance: XiaopacaiApp
            private set
    }
}
