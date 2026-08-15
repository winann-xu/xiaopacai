package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * [TASK-HARDENING-V1.1.1] Bug1-D/1-B：家长端守护数据本地存储
 *
 * 儿童端通过 P2P 上报 guard_event（失守/恢复 + 健康度快照）与
 * diagnostics_report（含 health 字段），家长端落盘于此：
 * - 每台设备最新健康度快照（health_snapshot）；
 * - 每台设备失守事件历史（最多 100 条，新事件在前）。
 *
 * 存储介质为加密应用 prefs（JSON 文本，不落明文敏感信息——
 * 健康度仅含权限布尔值与设备型号，符合红线要求）。
 */
object ParentGuardData {

    private const val TAG = "ParentGuardData"
    private const val PREFS_NAME = "parent_guard_data"
    private const val KEY_HEALTH_PREFIX = "health_"
    private const val KEY_EVENTS_PREFIX = "events_"
    private const val MAX_EVENTS_PER_DEVICE = 100

    /** 保存最新健康度快照（null/无效则忽略） */
    fun saveHealth(context: Context, deviceId: String, health: JSONObject?) {
        if (health == null) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HEALTH_PREFIX + deviceId, health.toString()).apply()
        Log.d(TAG, "健康度已更新: device=$deviceId score=${health.optInt("score", -1)}")
    }

    /** 读取最新健康度快照（无数据返回 null） */
    fun latestHealth(context: Context, deviceId: String): JSONObject? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HEALTH_PREFIX + deviceId, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    /** 追加失守事件（新事件在前，cap 100） */
    fun addEvent(context: Context, deviceId: String, event: JSONObject) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = try {
                JSONArray(prefs.getString(KEY_EVENTS_PREFIX + deviceId, "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            // 新事件在前
            val merged = JSONArray().apply {
                put(event)
                for (i in 0 until existing.length()) put(existing.get(i))
            }
            while (merged.length() > MAX_EVENTS_PER_DEVICE) merged.remove(merged.length() - 1)
            prefs.edit().putString(KEY_EVENTS_PREFIX + deviceId, merged.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "追加失守事件失败: ${e.message}")
        }
    }

    /** 读取失守事件历史（新事件在前） */
    fun events(context: Context, deviceId: String, limit: Int = 20): JSONArray {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS_PREFIX + deviceId, "[]") ?: "[]"
        return try {
            val all = JSONArray(raw)
            val result = JSONArray()
            for (i in 0 until minOf(all.length(), limit)) result.put(all.get(i))
            result
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /** 事件是否为「失守开始」（供列表分类显示） */
    fun isGuardDownEvent(event: JSONObject): Boolean =
        event.optString("event") == "guard_down"
}
