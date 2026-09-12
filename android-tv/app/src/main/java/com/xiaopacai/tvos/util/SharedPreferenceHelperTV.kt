// [android-tv] 工具类 — 偏好设置
// 小趴菜 TVOS 版 — DataStore Preferences

package com.xiaopacai.tvos.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "xiaopacai_tv_settings")

/**
 * 偏好设置工具类
 * 使用 AndroidX DataStore 替代 SharedPreferences
 */
object SharedPreferenceHelperTV {

    // === DataStore Key ===
    private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
    private val KEY_PARENT_ACCOUNT = stringPreferencesKey("parent_account")
    private val KEY_DAILY_LIMIT = intPreferencesKey("daily_limit_minutes")
    private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
    private val KEY_CLOUD_HOST = stringPreferencesKey("cloud_host")
    private val KEY_CLOUD_PORT = intPreferencesKey("cloud_port")
    private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    private val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token")
    /** 【项2】无障碍守护是否曾被家长开启过（用于「应用更新后系统自动解除绑定」的失效提醒） */
    private val KEY_ACCESSIBILITY_EVER_ON = booleanPreferencesKey("accessibility_ever_on")
    private val KEY_DEVICE_BOUND = booleanPreferencesKey("device_bound")
    /** [TASK-GUARD-TOGGLE-V1] 小趴菜管控是否启用（家长在 Web 端「设备管理」禁用/启用；默认启用） */
    private val KEY_GUARD_ENABLED = booleanPreferencesKey("guard_enabled")
    private val KEY_BIND_TOKEN = stringPreferencesKey("bind_token")
    private val KEY_AUTO_START = booleanPreferencesKey("auto_start_enabled")
    private val KEY_IGNORE_BATTERY_OPT = booleanPreferencesKey("ignore_battery_opt")
    private val KEY_LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
    private val KEY_LAST_USAGE_DATE = stringPreferencesKey("last_usage_date")
    private val KEY_IS_LOCKED = booleanPreferencesKey("is_locked")
    private val KEY_USAGE_MINUTES_TODAY = intPreferencesKey("usage_minutes_today")
    /** 【A3】就寝时段（HH:mm，来自云端策略） */
    private val KEY_BEDTIME_START = stringPreferencesKey("bedtime_start")
    private val KEY_BEDTIME_END = stringPreferencesKey("bedtime_end")
    /** 【A15】禁止恢复出厂（本地记录，供安全阀页展示与解除） */
    private val KEY_FACTORY_RESET_BLOCKED = booleanPreferencesKey("factory_reset_blocked")
    /** 【A12】云端下发的应用分类映射（原文，上报使用记录时使用） */
    private val KEY_APP_CATEGORIES = stringPreferencesKey("app_categories")

    /**
     * 初始化（在 Application 中调用）
     */
    fun init(context: Context) {
        // DataStore 懒加载，无需显式初始化
    }

    // ==================== 便捷存取方法 ====================

    /** 获取 PIN 哈希 */
    fun getPinHash(context: Context): Flow<String?> =
        context.dataStore.data.map { it[KEY_PIN_HASH] }

    /** 保存 PIN 哈希 */
    suspend fun savePinHash(context: Context, pinHash: String) {
        context.dataStore.edit { it[KEY_PIN_HASH] = pinHash }
    }

    /** 获取已记忆的家长账号（仅账号；密码从不保存） */
    fun getParentAccount(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_PARENT_ACCOUNT] ?: "" }

    /** 记忆家长账号（登录成功后调用，供下次自动预填） */
    suspend fun saveParentAccount(context: Context, account: String) {
        context.dataStore.edit { it[KEY_PARENT_ACCOUNT] = account }
    }

    /** 清除已记忆的家长账号（退出登录） */
    suspend fun clearParentAccount(context: Context) {
        context.dataStore.edit { it.remove(KEY_PARENT_ACCOUNT) }
    }

    /** 获取每日限额 */
    fun getDailyLimit(context: Context): Flow<Int> =
        context.dataStore.data.map { it[KEY_DAILY_LIMIT] ?: 60 }

    /** 保存每日限额 */
    suspend fun saveDailyLimit(context: Context, minutes: Int) {
        context.dataStore.edit { it[KEY_DAILY_LIMIT] = minutes }
    }

    /** 获取设备名称 */
    fun getDeviceName(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_DEVICE_NAME] ?: "小趴菜TV" }

    /** 保存设备名称 */
    suspend fun saveDeviceName(context: Context, name: String) {
        context.dataStore.edit { it[KEY_DEVICE_NAME] = name }
    }

    /** 获取云端主机 */
    fun getCloudHost(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_CLOUD_HOST] ?: "xpc.winann.com" }

    /** 保存云端主机 */
    suspend fun saveCloudHost(context: Context, host: String) {
        context.dataStore.edit { it[KEY_CLOUD_HOST] = host }
    }

    /** 获取云端端口 */
    fun getCloudPort(context: Context): Flow<Int> =
        context.dataStore.data.map { it[KEY_CLOUD_PORT] ?: 443 }

    /** 保存云端端口 */
    suspend fun saveCloudPort(context: Context, port: Int) {
        context.dataStore.edit { it[KEY_CLOUD_PORT] = port }
    }

    /** 获取设备 ID */
    fun getDeviceId(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_DEVICE_ID] ?: "" }

    /** 生成并保存设备 ID（UUID） */
    suspend fun generateDeviceId(context: Context) {
        val id = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[KEY_DEVICE_ID] = id }
    }

    /** 获取设备令牌（/api/v1/device/register 下发，用于设备侧接口鉴权） */
    fun getDeviceToken(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_DEVICE_TOKEN] ?: "" }

    /** [TASK-GUARD-TOGGLE-V1] 管控启停状态（缺省 true=启用，避免升级后误停用） */
    fun getGuardEnabled(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_GUARD_ENABLED] ?: true }

    /** [TASK-GUARD-TOGGLE-V1] 落盘管控启停状态（设备重启/进程被杀后仍生效） */
    suspend fun saveGuardEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_GUARD_ENABLED] = enabled }
    }

    /** 保存设备令牌 */
    suspend fun saveDeviceToken(context: Context, token: String) {
        context.dataStore.edit { it[KEY_DEVICE_TOKEN] = token }
    }

    /** 设备是否已绑定家长账号（/api/pairing/verify 成功后置 true） */
    fun getDeviceBound(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DEVICE_BOUND] ?: false }

    /** 保存设备绑定状态 */
    suspend fun saveDeviceBound(context: Context, bound: Boolean) {
        context.dataStore.edit { it[KEY_DEVICE_BOUND] = bound }
    }

    /** 【项2】无障碍守护是否曾被开启过（一旦为 true，之后检测到「已关闭」就要提醒家长） */
    /** 【A3】就寝开始（HH:mm，空=未设置） */
    fun getBedtimeStart(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_BEDTIME_START] ?: "" }

    suspend fun saveBedtimeStart(context: Context, value: String) {
        context.dataStore.edit { it[KEY_BEDTIME_START] = value }
    }

    /** 【A3】就寝结束（HH:mm，空=未设置） */
    fun getBedtimeEnd(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_BEDTIME_END] ?: "" }

    suspend fun saveBedtimeEnd(context: Context, value: String) {
        context.dataStore.edit { it[KEY_BEDTIME_END] = value }
    }

    /** 【A15】是否已禁止恢复出厂 */
    fun getFactoryResetBlocked(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_FACTORY_RESET_BLOCKED] ?: false }

    suspend fun saveFactoryResetBlocked(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_FACTORY_RESET_BLOCKED] = value }
    }

    /** 【A12】应用分类映射原文（JSON） */
    fun getAppCategories(context: Context): Flow<String> =
        context.dataStore.data.map { it[KEY_APP_CATEGORIES] ?: "" }

    suspend fun saveAppCategories(context: Context, value: String) {
        context.dataStore.edit { it[KEY_APP_CATEGORIES] = value }
    }

    fun getAccessibilityEverOn(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ACCESSIBILITY_EVER_ON] ?: false }

    suspend fun saveAccessibilityEverOn(context: Context, everOn: Boolean) {
        context.dataStore.edit { it[KEY_ACCESSIBILITY_EVER_ON] = everOn }
    }

    /** 获取绑定令牌 */
    fun getBindToken(context: Context): Flow<String?> =
        context.dataStore.data.map { it[KEY_BIND_TOKEN] }

    /** 保存绑定令牌 */
    suspend fun saveBindToken(context: Context, token: String) {
        context.dataStore.edit { it[KEY_BIND_TOKEN] = token }
    }

    /** 清除所有绑定数据（解绑用） */
    suspend fun clearBindData(context: Context) {
        context.dataStore.edit {
            it.remove(KEY_BIND_TOKEN)
            it.remove(KEY_DEVICE_ID)
        }
    }

    /** 获取上次同步时间 */
    fun getLastSyncTime(context: Context): Flow<Long> =
        context.dataStore.data.map { it[KEY_LAST_SYNC_TIME] ?: 0 }

    /** 保存上次同步时间 */
    suspend fun saveLastSyncTime(context: Context, time: Long) {
        context.dataStore.edit { it[KEY_LAST_SYNC_TIME] = time }
    }
}
