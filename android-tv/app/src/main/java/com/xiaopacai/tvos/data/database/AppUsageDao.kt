// [android-tv] DAO — 应用使用时长操作
package com.xiaopacai.tvos.data.database

import androidx.room.*
import com.xiaopacai.tvos.data.model.AppUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {

    @Query("SELECT * FROM app_usage WHERE packageName = :pkg LIMIT 1")
    suspend fun getUsage(pkg: String): AppUsageEntity?

    @Query("SELECT * FROM app_usage WHERE isWhitelisted = 1")
    fun getWhitelistedApps(): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage ORDER BY usedMinutesToday DESC")
    fun getAllUsages(): Flow<List<AppUsageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUsage(usage: AppUsageEntity)

    @Query("UPDATE app_usage SET usedMinutesToday = :minutes WHERE packageName = :pkg")
    suspend fun updateTodayUsage(pkg: String, minutes: Int)

    @Query("UPDATE app_usage SET lastUsedTime = :time WHERE packageName = :pkg")
    suspend fun updateLastUsedTime(pkg: String, time: Long)

    @Query("UPDATE app_usage SET totalUsageTime = totalUsageTime + :minutes WHERE packageName = :pkg")
    suspend fun addTotalUsage(pkg: String, minutes: Int)

    @Query("UPDATE app_usage SET isWhitelisted = :whitelisted WHERE packageName = :pkg")
    suspend fun updateWhitelist(pkg: String, whitelisted: Boolean)

    @Query("DELETE FROM app_usage")
    suspend fun clearAll()

    @Query("DELETE FROM app_usage WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}
