package com.xiaopacai.child.service

import android.content.Context
import android.util.Log
import com.xiaopacai.child.util.CloudAccountManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * [TASK-HARDENING-V1.1.1] Bug1-D：守护失守监控（儿童端）
 *
 * 职责：
 * 1. 记录守护失效开始/结束时间与失守时长（本地持久化 + 云端上报）；
 * 2. 守护健康度快照计算（权限/服务/Device Owner/OPPO 保活引导项）；
 * 3. 离线时事件入队，连接恢复后补传（SyncManager 同步循环调用 flushPendingSend）。
 *
 * 失守触发源（各调用点集成）：
 * - 进程被系统/OEM 杀（GuardianForegroundService.detectKillRecovery，startTs=最后心跳）
 * - 上滑最近任务（GuardianAlarmReceiver.scheduleSwipeRecovery）
 * - 无障碍服务被移除且管控生效中（AntiBypassService.checkAllBypassVectors）
 *
 * 语义边界（如实记录，不夸大）：
 * - 失守开始时间 = 检测依据的近似时刻（心跳/上滑/权限检查），非毫秒级精确；
 * - 「强制停止」后本进程无法感知与上报，事件在下次启动检测到心跳缺口时补记。
 */
object GuardDownMonitor {

    private const val TAG = "GuardDownMonitor"
    private const val PREFS_NAME = "guard_monitor_prefs"
    private const val KEY_PENDING_DOWN = "pending_down"      // {startTs, reason, wasEnforcing}
    private const val KEY_HISTORY = "down_history"           // JSONArray，最多 100 条
    private const val KEY_PENDING_SEND = "pending_send"      // 待补传事件 JSONArray，最多 50 条
    private const val MAX_HISTORY = 100
    private const val MAX_PENDING_SEND = 50

    // ==================== 失守事件 ====================

    /** 守护失效开始（幂等：已在失守中则保留最早的开始时间与原因） */
    fun onGuardLost(
        context: Context,
        reason: String,
        startTs: Long = System.currentTimeMillis(),
        wasEnforcing: Boolean? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PENDING_DOWN, null)
        if (existing != null) {
            Log.d(TAG, "已在失守状态（$existing），忽略重复失守事件 reason=$reason")
            return
        }
        val enforcing = wasEnforcing ?: GuardianForegroundService.isEnforcementActive(context)
        val entry = JSONObject().apply {
            put("startTs", startTs)
            put("reason", reason)
            put("wasEnforcing", enforcing)
        }
        prefs.edit().putString(KEY_PENDING_DOWN, entry.toString()).apply()
        Log.w(TAG, "守护失守开始: reason=$reason, startTs=$startTs, enforcing=$enforcing")

        queueEvent(context, JSONObject().apply {
            put("event", "guard_down")
            put("startTs", startTs)
            put("reason", reason)
            put("wasEnforcing", enforcing)
        })
    }

    /**
     * 守护恢复（有失守记录时才结算；无记录时仅补发一次恢复事件用于健康度同步）
     * @return 失守时长秒数；无失守记录返回 null
     */
    fun onGuardRestored(context: Context, restoredReason: String): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingJson = prefs.getString(KEY_PENDING_DOWN, null)
        val endTs = System.currentTimeMillis()

        if (pendingJson == null) {
            Log.d(TAG, "无失守记录，恢复事件不结算")
            return null
        }

        return try {
            val pending = JSONObject(pendingJson)
            val startTs = pending.optLong("startTs", endTs)
            val reason = pending.optString("reason", "unknown")
            val enforcing = pending.optBoolean("wasEnforcing", false)
            val durationSec = ((endTs - startTs) / 1000L).coerceAtLeast(0L)

            // 1. 本地历史持久化（cap 100）
            val history = readHistory(context)
            val entry = JSONObject().apply {
                put("startTs", startTs)
                put("endTs", endTs)
                put("durationSec", durationSec)
                put("reason", reason)
                put("restoredReason", restoredReason)
                put("wasEnforcing", enforcing)
            }
            history.put(entry)
            while (history.length() > MAX_HISTORY) history.remove(0)
            prefs.edit()
                .putString(KEY_HISTORY, history.toString())
                .remove(KEY_PENDING_DOWN)
                .apply()

            Log.i(TAG, "守护已恢复: reason=$reason → $restoredReason, 失守 ${durationSec}s")
            queueEvent(context, JSONObject().apply {
                put("event", "guard_restored")
                put("startTs", startTs)
                put("endTs", endTs)
                put("durationSec", durationSec)
                put("reason", reason)
                put("restoredReason", restoredReason)
                put("wasEnforcing", enforcing)
            })
            durationSec
        } catch (e: Exception) {
            Log.e(TAG, "结算失守事件失败: ${e.message}")
            null
        }
    }

    /** 当前是否处于失守状态 */
    fun isGuardDown(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_DOWN, null) != null

    /** 当前失守原因（无失守返回 null；调用方按原因匹配后结算，避免误关其他失守事件） */
    fun pendingReason(context: Context): String? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_DOWN, null) ?: return null
        return try {
            JSONObject(json).optString("reason", "unknown")
        } catch (e: Exception) {
            "unknown"
        }
    }

    /** 本地失守历史（JSONArray，最近在前由调用方决定顺序） */
    fun history(context: Context): JSONArray = readHistory(context)

    private fun readHistory(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    // ==================== 健康度 ====================

    /**
     * 计算守护健康度快照（JSON）。
     *
     * 计分项（可程序检测，共 6 项）：设备管理器 / 无障碍 / 使用情况访问 /
     * 开机自启 / 电池优化 / 通知权限。
     * 引导项（OEM 无公开检测接口，仅引导，不计分）：自启动管理 / 后台冻结 /
     * 最近任务锁定 —— status 恒为 "guide"（如实边界，不伪造检测结果）。
     * Device Owner 仅检测与展示，不落地激活（Bug1-C）。
     */
    fun computeHealth(context: Context): JSONObject {
        val health = JSONObject()
        val items = JSONObject()

        val deviceAdmin = GuardianDeviceAdminReceiver.isActive(context)
        val accessibility = AntiBypassService.isAccessibilityServiceEnabled(context)
        val usageStats = AntiBypassService.isUsageStatsPermissionGranted(context)
        val boot = hasBootPermission(context)
        val batteryDisabled = !AntiBypassService.isBatteryOptimizationEnabled(context)
        val notification = hasNotificationPermission(context)

        items.put("deviceAdmin", deviceAdmin)
        items.put("accessibility", accessibility)
        items.put("usageStats", usageStats)
        items.put("bootAutoStart", boot)
        items.put("batteryOptimizationDisabled", batteryDisabled)
        items.put("notification", notification)

        val readyCount = listOf(deviceAdmin, accessibility, usageStats, boot, batteryDisabled, notification)
            .count { it }
        val score = (readyCount * 100) / 6
        health.put("score", score)
        health.put("readyCount", readyCount)
        health.put("totalCount", 6)
        health.put("items", items)
        health.put("status", when {
            !deviceAdmin || !accessibility -> "danger"     // 拦截/防卸载核心缺失
            readyCount < 6 -> "attention"
            else -> "good"
        })
        health.put("guardDown", isGuardDown(context))
        health.put("manufacturer", android.os.Build.MANUFACTURER)
        health.put("model", android.os.Build.MODEL)

        // Device Owner 检测（Bug1-C：仅检测与说明，不落地 DPC 激活）
        health.put("deviceOwner", detectDeviceOwner(context))

        // OPPO/ColorOS 保活引导项（无公开检测 API，统一 guide 状态，家长按引导确认）
        health.put("keepaliveGuides", JSONObject().apply {
            put("autoStart", "guide")
            put("backgroundFreeze", "guide")
            put("recentsLock", "guide")
            put("colorOs", android.os.Build.MANUFACTURER?.lowercase()?.contains("oppo") == true ||
                android.os.Build.MANUFACTURER?.lowercase()?.contains("realme") == true ||
                android.os.Build.MANUFACTURER?.lowercase()?.contains("oneplus") == true)
        })
        health.put("timestamp", System.currentTimeMillis() / 1000)
        return health
    }

    /** Bug1-C：Device Owner 检测（激活状态 + 是否具备预置条件；不激活） */
    private fun detectDeviceOwner(context: Context): JSONObject {
        return JSONObject().apply {
            try {
                val dpm = GuardianDeviceAdminReceiver.getDpm(context)
                put("isActive", dpm.isDeviceOwnerApp(context.packageName))
                put("provisioningAllowed", try {
                    dpm.isProvisioningAllowed(
                        android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE
                    )
                } catch (e: Exception) { false })
            } catch (e: Exception) {
                put("isActive", false)
                put("provisioningAllowed", false)
            }
            // [TASK-STRICT-PROVISION-V1] 强管制模式：自授权预置（ADR 0018），无需电脑
            put("selfProvisionSupported", android.os.Build.VERSION.SDK_INT >= 30)
            put("boundary", "强管制模式自授权预置（ADR 0018）：无需电脑；需 Android 11+ 与无账号状态；定制 ROM 以实测为准")
        }
    }

    private fun hasBootPermission(context: Context): Boolean {
        return try {
            context.packageManager.checkPermission(
                android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
                context.packageName
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ==================== 云端上报 ====================

    /** 事件入队（本地持久化），并尝试立即发送 */
    private fun queueEvent(context: Context, event: JSONObject) {
        event.put("deviceId", getDeviceId(context))
        event.put("health", computeHealth(context))
        addToPendingSend(context, event)
        sendPending(context)
    }

    /** 待补传队列（离线缓存） */
    private fun addToPendingSend(context: Context, event: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val queue = try {
                JSONArray(prefs.getString(KEY_PENDING_SEND, "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            queue.put(event)
            while (queue.length() > MAX_PENDING_SEND) queue.remove(0)
            prefs.edit().putString(KEY_PENDING_SEND, queue.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "事件入队失败: ${e.message}")
        }
    }

    /**
     * 立即尝试发送待补传事件；未连接时保留队列。
     * @return 成功发送条数
     */
    fun sendPending(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val queue = try {
            JSONArray(prefs.getString(KEY_PENDING_SEND, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        if (queue.length() == 0) return 0

        var sentCount = 0
        val remaining = JSONArray()
        for (i in 0 until queue.length()) {
            val event = try { queue.getJSONObject(i) } catch (e: Exception) { continue }
            if (sendEvent(context, event)) {
                sentCount++
            } else {
                remaining.put(event)
            }
        }
        prefs.edit().putString(KEY_PENDING_SEND, remaining.toString()).apply()
        if (sentCount > 0) Log.i(TAG, "已上报 $sentCount 条守护事件")
        return sentCount
    }

    /** 发送单条守护事件（通过云端上报） */
    private fun sendEvent(context: Context, event: JSONObject): Boolean {
        return try {
            CloudSyncService.reportGuardEvent(context, event)
        } catch (e: Exception) {
            Log.e(TAG, "发送守护事件失败: ${e.message}")
            false
        }
    }

    private fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }
}
