// [android-tv] DAO — 设备配置操作
// 小趴菜 TVOS 版 — 加密数据库访问

package com.xiaopacai.tvos.data.database

import androidx.room.*
import com.xiaopacai.tvos.data.model.DeviceConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceConfigDao {

    @Query("SELECT * FROM device_config LIMIT 1")
    fun getConfig(): Flow<DeviceConfigEntity?>

    @Query("SELECT * FROM device_config LIMIT 1")
    suspend fun getConfigSync(): DeviceConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(config: DeviceConfigEntity)

    @Query("DELETE FROM device_config WHERE id = :id")
    suspend fun deleteConfig(id: Long = 1L)

    @Query("UPDATE device_config SET pinHash = :pinHash WHERE id = 1")
    suspend fun updatePinHash(pinHash: String)

    @Query("UPDATE device_config SET dailyLimitMinutes = :limit WHERE id = 1")
    suspend fun updateDailyLimit(limit: Int)

    @Query("UPDATE device_config SET deviceName = :name WHERE id = 1")
    suspend fun updateDeviceName(name: String)

    @Query("UPDATE device_config SET cloudHost = :host, cloudPort = :port WHERE id = 1")
    suspend fun updateCloudConfig(host: String, port: Int)

    @Query("UPDATE device_config SET bindToken = :token WHERE id = 1")
    suspend fun updateBindToken(token: String)

    @Query("UPDATE device_config SET updatedAt = :time WHERE id = 1")
    suspend fun updateTimestamp(time: Long = System.currentTimeMillis())
}
