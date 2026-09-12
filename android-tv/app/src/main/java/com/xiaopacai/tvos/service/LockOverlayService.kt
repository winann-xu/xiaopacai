// [android-tv] 锁屏覆盖服务 — LockOverlayService
// [TASK-TV-01] 按 CORRECTION.md 偏离纠正 1：Service + WindowManager 纯 View 覆盖层
// 小趴菜 TVOS 版 — 由前台服务直接 addView 到 WindowManager
// TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW，纯 View 实现（不用 Compose）

package com.xiaopacai.tvos.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.core.app.NotificationCompat
import com.xiaopacai.tvos.R
import com.xiaopacai.tvos.ui.login.ParentLoginActivity
import com.xiaopacai.tvos.util.EncryptionHelper
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 锁屏覆盖前台服务
 * 
 * 功能：
 * 1. 以 TYPE_APPLICATION_OVERLAY 在 WindowManager 上加全屏 View
 * 2. 盖住所有应用（含全屏视频）
 * 3. HOME 键不会关闭覆盖层（非 Activity，无 Back Stack）
 * 4. 周期 reassert() 确保覆盖层不被其他应用遮挡
 * 5. 家长验证入口：点击"家长紧急停用"跳转到 ParentLoginActivity（账号 + 密码登录）
 * 
 * 权限要求：SYSTEM_ALERT_WINDOW（Manifest 已声明）
 */
class LockOverlayService : Service() {

    companion object {
        private const val TAG = "LockOverlayService"
        private const val NOTIFICATION_ID = 8888
        private const val CHANNEL_ID = "lock_overlay_channel"
        private const val REASSERT_INTERVAL_MS = 5_000L // 5 秒周期重提

        // 单例引用
        @Volatile
        private var instance: LockOverlayService? = null

        /** 获取锁屏服务实例（供 TimeoutExecutorTV 调用） */
        fun getInstance(): LockOverlayService? = instance
    }

    private var windowManager: WindowManager? = null
    private var lockOverlayView: View? = null
    private var countdownTextView: TextView? = null
    private var reassertExecutor = Executors.newSingleThreadScheduledExecutor()
    private var isOverlayShown = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 创建前台通知
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        // 初始化 WindowManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 显示锁屏覆盖层
        showLockOverlay()

        // 启动周期 reassert（确保覆盖层不被遮挡）
        startReassert()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null

        // 移除覆盖层
        removeLockOverlay()

        // 停止 reassert
        reassertExecutor.shutdownNow()

        // 停止前台服务
        // [BUGFIX] stopForeground(int) 是 API 24+ 的重载，Android 5.1(TV) 上直接调用会抛
        // NoSuchMethodError 崩溃（实测栈：removeOverlay -> onDestroy -> stopForeground(I)V）。这里按版本分支。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 覆盖层管理 ====================

    /**
     * 显示锁屏覆盖层
     */
    private fun showLockOverlay() {
        // 创建锁屏 UI（纯 View）
        lockOverlayView = createLockOverlayView()

        // 设置 WindowManager 参数：TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            flags = (
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            gravity = Gravity.CENTER
            format = android.graphics.PixelFormat.TRANSLUCENT
            packageName = packageName
        }

        // [BUGFIX-2026-09-12] MIUI/老机型常拒绝 TYPE_SYSTEM_ALERT（未授予悬浮窗）：
        // 原来这里直接 addView 会抛 BadTokenException → 服务创建失败 → 进程被杀。
        // 现在失败只记日志并退出该服务；锁屏由 BlockOverlayActivity 承担（不需要悬浮窗权限）。
        runCatching { windowManager?.addView(lockOverlayView, params) }
            .onFailure {
                android.util.Log.e(TAG, "addView 被拒（多半是未授予悬浮窗）", it)
                com.xiaopacai.tvos.util.AppLogTV.w(TAG, "覆盖层 addView 被拒: ${it.message}")
                stopSelf()
                return
            }
        isOverlayShown = true
    }

    /**
     * 移除锁屏覆盖层
     */
    private fun removeLockOverlay() {
        lockOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // 忽略移除失败
            }
        }
        lockOverlayView = null
        isOverlayShown = false
    }

    /**
     * 重新提���覆盖层（周期调用，确保不被遮挡）
     */
    fun reassert() {
        if (isOverlayShown) {
            lockOverlayView?.let { view ->
                try {
                    windowManager?.updateViewLayout(view, view.layoutParams)
                } catch (_: Exception) {
                    // 更新失败则重建
                    removeLockOverlay()
                    showLockOverlay()
                }
            }
        } else {
            showLockOverlay()
        }
    }

    /**
     * 启动周期 reassert 任务
     */
    private fun startReassert() {
        reassertExecutor.scheduleWithFixedDelay(
            { reassert() },
            REASSERT_INTERVAL_MS,
            REASSERT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * 创建锁屏覆盖层 View
     * 纯 View 实现，不使用 Compose
     */
    private fun createLockOverlayView(): View {
        // 根布局：全屏线性布局
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A237E.toInt())
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        // 背景渐变（使用 ShapeDrawable）
        val gradientDrawable = android.graphics.drawable.GradientDrawable().apply {
            setGradientType(android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT)
            setColors(
                intArrayOf(0xFF1A237E.toInt(), 0xFFB71C1C.toInt())
            )
            setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            rootLayout.background = gradientDrawable
        }

        // 标题："看电视时间到啦！"
        val titleText = TextView(this).apply {
            text = "\u23F0 看电视时间到啦！"
            textSize = 48f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        rootLayout.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 提示文字
        val hintText = TextView(this).apply {
            text = "今日电视时间已用完\n明天再来吧～"
            textSize = 32f
            setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        rootLayout.addView(hintText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 倒计时
        countdownTextView = TextView(this).apply {
            text = "\u23F3 15 分钟后可以重新开始"
            textSize = 40f
            setTextColor(android.graphics.Color.parseColor("#FFEB3B"))
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 48, 0, 0)
        }
        rootLayout.addView(countdownTextView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 家长紧急停用按钮
        val parentButton = Button(this).apply {
            text = "\uD83D\uDD11 家长紧急停用"
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundTintList(ColorStateList.valueOf(0xFF4CAF50.toInt()))
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(480, 80).apply {
                setMargins(0, 48, 0, 24)
            }
            setOnClickListener {
                // 跳转到家长登录（账号 + 密码），登录成功后解除锁屏
                val loginIntent = Intent(this@LockOverlayService, ParentLoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(ParentLoginActivity.EXTRA_ACTION_LABEL, "家长紧急停用")
                    putExtra(ParentLoginActivity.EXTRA_UNLOCK_ON_SUCCESS, true)
                    putExtra(ParentLoginActivity.EXTRA_EMERGENCY_RELEASE, true)
                }
                startActivity(loginIntent)
            }
        }
        rootLayout.addView(parentButton)

        // 返回首页按钮
        val homeButton = Button(this).apply {
            text = "\uD83C\uDFE0 返回首页"
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundTintList(ColorStateList.valueOf(0xFF607D8B.toInt()))
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(480, 72).apply {
                setMargins(0, 0, 0, 24)
            }
            setOnClickListener {
                TimeoutExecutorTV.unlock(this@LockOverlayService)
                TimeoutExecutorTV.removeOverlay()
            }
        }
        rootLayout.addView(homeButton)

        // 白名单说明
        val whitelistText = TextView(this).apply {
            text = "\uD83D\uDCF4 白名单应用不受今日时长限制"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        rootLayout.addView(whitelistText)

        // 紧急呼叫按钮
        val emergencyButton = Button(this).apply {
            text = "\uD83D\uDCF1 紧急电话"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundTintList(ColorStateList.valueOf(0xFFD32F2F.toInt()))
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(360, 64).apply {
                setMargins(0, 16, 0, 0)
            }
            setOnClickListener {
                val callIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = android.net.Uri.parse("tel:110")
                }
                startActivity(callIntent)
            }
        }
        rootLayout.addView(emergencyButton)

        return rootLayout
    }

    // ==================== 前台通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "锁屏覆盖服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "小趴菜锁屏覆盖层服务"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⏰ 小趴菜守护中")
            .setContentText("锁屏覆盖层运行中")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 更新倒计时显示
     */
    fun updateCountdown(minutes: Int) {
        countdownTextView?.post {
            countdownTextView?.text = "\u23F3 $minutes 分钟后可以重新开始"
        }
    }
}
