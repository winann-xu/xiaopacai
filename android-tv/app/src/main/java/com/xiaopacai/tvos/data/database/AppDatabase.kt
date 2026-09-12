// [android-tv] 类型转换器 + Room 数据库
package com.xiaopacai.tvos.data.database

import androidx.room.*
import com.xiaopacai.tvos.data.model.*
import net.sqlcipher.database.SupportFactory
import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec

@TypeConverters
class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Long? {
        return value
    }

    @TypeConverter
    fun dateFromTimestamp(value: Long?): String? {
        return value?.toString()
    }
}

/**
 * 加密 Room 数据库
 * 使用 SQLCipher 提供透明加密
 */
@Database(
    entities = [
        DeviceConfigEntity::class,
        AppUsageEntity::class,
        UsageLogEntity::class,
        CloudPolicyEntity::class,
        InstalledAppEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun deviceConfigDao(): DeviceConfigDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun usageLogDao(): UsageLogDao
    abstract fun cloudPolicyDao(): CloudPolicyDao
    abstract fun installedAppDao(): InstalledAppDao

    companion object {
        private const val DATABASE_NAME = "xiaopacai_tv.db"

        /**
         * 从 PIN 派生数据库加密密钥
         * 使用 AES-256
         */
        fun getEncryptedDatabase(
            pin: String,
            deviceId: String,
            context: android.content.Context
        ): AppDatabase {
            // 密钥派生：SHA-256 哈希 PIN + device id
            val keyInput = (pin + deviceId).toByteArray(StandardCharsets.UTF_8)
            val keyBytes = keyInput.sha256()
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val passphrase = keySpec.encoded

            val sqliteSupportFactory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                DATABASE_NAME
            ).openHelperFactory(sqliteSupportFactory)
                .build()
        }

        /**
         * 无 PIN 时（首次设置），使用默认密钥
         */
        fun getDatabase(context: android.content.Context): AppDatabase {
            val defaultKey = "xiaopacai_default_key_2024_tv".toByteArray(StandardCharsets.UTF_8).sha256()
            val keySpec = SecretKeySpec(defaultKey, "AES")
            val sqliteSupportFactory = SupportFactory(keySpec.encoded)

            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                DATABASE_NAME
            ).openHelperFactory(sqliteSupportFactory)
                .build()
        }

        /**
         * 计算 SHA-256 哈希
         */
        private fun ByteArray.sha256(): ByteArray {
            return java.security.MessageDigest.getInstance("SHA-256").digest(this)
        }
    }
}
