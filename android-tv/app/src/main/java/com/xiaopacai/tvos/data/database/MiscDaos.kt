// [android-tv] DAO — 使用记录明细 + 云端策略 + 已安装应用
package com.xiaopacai.tvos.data.database

import androidx.room.*
import com.xiaopacai.tvos.data.model.*
import kotlinx.coroutines.flow.Flow

// ==================== 使用记录明细 ====================
@Dao
interface UsageLogDao {

    @Query("SELECT * FROM usage_log WHERE date = :date ORDER BY startTime DESC")
    fun getLogsForDate(date: String): Flow<List<UsageLogEntity>>

    @Query("SELECT * FROM usage_log WHERE date BETWEEN :start AND :end ORDER BY startTime DESC")
    fun getLogsInRange(start: String, end: String): Flow<List<UsageLogEntity>>

    @Query("DELETE FROM usage_log WHERE date < :keepFrom")
    suspend fun cleanupOldLogs(keepFrom: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(vararg logs: UsageLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: UsageLogEntity)
}

// ==================== 云端策略 ====================
@Dao
interface CloudPolicyDao {

    @Query("SELECT * FROM cloud_policy WHERE policyKey = :key LIMIT 1")
    suspend fun getPolicy(key: String): CloudPolicyEntity?

    @Query("SELECT * FROM cloud_policy ORDER BY syncTime DESC")
    fun getAllPolicies(): Flow<List<CloudPolicyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePolicy(policy: CloudPolicyEntity)

    @Query("DELETE FROM cloud_policy WHERE policyKey = :key")
    suspend fun deletePolicy(key: String)

    @Query("DELETE FROM cloud_policy")
    suspend fun clearAll()
}

// ==================== 已安装应用 ====================
@Dao
interface InstalledAppDao {

    @Query("SELECT * FROM installed_apps ORDER BY appName ASC")
    fun getAllApps(): Flow<List<InstalledAppEntity>>

    @Query("SELECT * FROM installed_apps WHERE packageName = :pkg LIMIT 1")
    suspend fun getApp(pkg: String): InstalledAppEntity?

    @Query("SELECT * FROM installed_apps WHERE packageName IN (:pkgs)")
    suspend fun getAppByPackageNames(pkgs: List<String>): List<InstalledAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: InstalledAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<InstalledAppEntity>)

    @Query("DELETE FROM installed_apps")
    suspend fun clearAll()

    @Query("DELETE FROM installed_apps WHERE packageName = :pkg")
    suspend fun deleteApp(pkg: String)
}
