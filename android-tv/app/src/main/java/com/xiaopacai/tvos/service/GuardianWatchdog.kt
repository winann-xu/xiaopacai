// [android-tv] 守护看门狗 —— 【项2】防绕过 / 防退出的核心兜底
// 小趴菜 TVOS 版
//
// 为什么需要它：在小米电视（MIUI TV / Android 5.1）上，孩子可以
//   ① 长按遥控器 HOME 键结束任务   ② 从最近任务列表里清掉 App   ③ 让系统杀后台省电
// 单靠 Service 的 START_STICKY 并不保证被系统拉回来（尤其任务被显式移除时）。
//
// 这里用 AlarmManager（系统级闹钟，**本进程死了也照常触发**）做两件事：
//   1) 每分钟自检：守护前台服务 / 防绕过服务 / 云同步服务还活着吗？锁屏该盖着吗？缺什么补什么
//   2) onTaskRemoved（App 被划掉）时安排 1 秒后的快速重启，把空档压到最短
//
// 这是「进程死了也能自愈」的那一层；开机自启那一层在 BootReceiver。

package com.xiaopacai.tvos.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.xiaopacai.tvos.util.AppLogTV
import com.xiaopacai.tvos.util.TimeoutExecutorTV

object GuardianWatchdog {

    private const val TAG = "GuardianWatchdog"

    const val ACTION_TICK = "com.xiaopacai.tvos.WATCHDOG_TICK"
    const val ACTION_QUICK_RESTART = "com.xiaopacai.tvos.WATCHDOG_QUICK_RESTART"

    private const val REQ_TICK = 9001
    private const val REQ_QUICK_RESTART = 9002

    /** 自检周期：1 分钟（越短越不可能被绕过，但耗电略增；电视常插电，取 60s） */
    private const val INTERVAL_MS = 60_000L

    /**
     * 启动周期性自检（幂等，同一 PendingIntent 会覆盖旧闹钟）
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, ACTION_TICK, REQ_TICK) ?: return
        runCatching {
            am.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pi
            )
            Log.i(TAG, "守护看门狗已启动：每 ${INTERVAL_MS / 1000} 秒自检一次")
        }.onFailure { Log.e(TAG, "看门狗启动失败", it) }
    }

    /**
     * 取消自检（仅在家长明确要求关闭守护时调用）
     */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, ACTION_TICK, REQ_TICK) ?: return
        runCatching { am.cancel(pi) }
        Log.i(TAG, "守护看门狗已取消")
    }

    /**
     * App 被划掉（onTaskRemoved）后 1 秒快速重启，尽量不给孩子留空档
     */
    fun scheduleQuickRestart(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, ACTION_QUICK_RESTART, REQ_QUICK_RESTART) ?: return
        runCatching {
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1_000L,
                pi
            )
            Log.i(TAG, "已安排 1 秒后快速重启守护服务")
        }.onFailure { Log.e(TAG, "快速重启安排失败", it) }
    }

    /**
     * 自检 + 拉起所有守护组件（幂等，随时可调用）
     */
    fun ensureGuardsRunning(context: Context) {
        AppLogTV.i(TAG, "守护自检：拉起全部守护组件")
        startService(context, TVGuardianForegroundService::class.java)
        startService(context, AntiBypassServiceTV::class.java)
        startService(context, CloudSyncServiceTV::class.java)
        // 【方案B】P2P 实时通道：断线由 TvP2pClient 内部退避重连；进程被杀后由本自检拉回
        startService(context, TvP2pService::class.java)
        // 锁屏：只要「该锁」就确保盖着（isLocked 是内存态，进程重启后为 false，
        // 所以这里同时触发一次用量重算，超时的话 refreshUsage 内部会自己盖上锁屏）。
        // [BUGFIX-2026-09-12] 原来这里 startService(LockOverlayService)：该服务在 Android 5.1 上
        // addView(TYPE_APPLICATION_OVERLAY) 会被 WindowManager 拒绝 → 服务创建即崩 → 进程被杀，
        // 而它恰好在「限额用超」时被拉起 ⇒ 最关键的时刻守护全灭。改用锁屏 Activity（不受窗口权限限制）。
        if (TimeoutExecutorTV.isLocked) {
            runCatching {
                context.startActivity(
                    Intent(context, com.xiaopacai.tvos.ui.lock.BlockOverlayActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }.onFailure { AppLogTV.w(TAG, "拉起锁屏页失败: ${it.message}") }
        }
        runCatching { TimeoutExecutorTV.refreshUsage(context) }
            .onFailure { Log.e(TAG, "用量重算失败", it) }
    }

    private fun startService(context: Context, cls: Class<*>) {
        val intent = Intent(context, cls)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }.onFailure { Log.w(TAG, "拉起 ${cls.simpleName} 失败: ${it.message}") }
    }

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent? =
        runCatching {
            val intent = Intent(context, GuardianWatchdogReceiver::class.java).setAction(action)
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.getOrNull()
}

/**
 * 看门狗闹钟接收器 —— 进程被杀后由系统按闹钟重新拉起
 */
class GuardianWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "看门狗触发: ${intent?.action}")
        // 系统在极端情况下可能清掉重复闹钟，这里顺手补一次
        GuardianWatchdog.schedule(context)
        GuardianWatchdog.ensureGuardsRunning(context)
    }

    companion object {
        private const val TAG = "GuardianWatchdogRcv"
    }
}
