// [android-tv] 锁屏 Activity — BlockOverlayActivity
// 小趴菜 TVOS 版 — 全屏锁屏覆盖（超时后显示）
// 功能：倒计时、紧急呼叫、家长 PIN 验证

package com.xiaopacai.tvos.ui.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver
import com.xiaopacai.tvos.ui.login.ParentLoginActivity
import com.xiaopacai.tvos.util.AppLogTV
import com.xiaopacai.tvos.util.DeviceHardLock
import com.xiaopacai.tvos.util.EmergencyReleaseServiceTV
import com.xiaopacai.tvos.util.EncryptionHelper
import com.xiaopacai.tvos.util.HeartbeatStatus
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * 锁屏覆盖 Activity
 * 
 * 全屏显示，不可关闭（除非家长输入 PIN 验证）
 * 包含：
 * - 标题：看电视时间到啦！
 * - 提示：今日电视时间已用完
 * - 倒计时：xx 分钟后可重新开始
 * - 家长紧急停用按钮（需要 PIN 验证）
 * - 返回首页按钮
 */
class BlockOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BlockOverlayActivityTV"
    }

    /** 家长登录结果：仅登录成功才解除锁屏（登录页内已完成限时解除激活） */
    private val parentLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            exitLockAndFinish()
        }
    }

    /**
     * [FIX-2026-09-12] 远程解除（家长在 Web 端点「重置限额 / 调高限额」）时，解锁发生在 Service 里，
     * Service 拿不到本 Activity 的引用 → 靠应用内广播通知自行关闭；否则屏幕会一直停在锁屏。
     */
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!TimeoutExecutorTV.isLocked) {
                AppLogTV.i(TAG, "收到远程解除指令 → 关闭锁屏页")
                exitLockAndFinish()
            }
        }
    }

    private fun registerDismissReceiver() {
        runCatching {
            val filter = IntentFilter(TimeoutExecutorTV.ACTION_DISMISS_LOCK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(dismissReceiver, filter)
            }
        }.onFailure { AppLogTV.w(TAG, "注册解除广播失败: ${it.message}") }
    }

    /**
     * [FIX-2026-09-12] **退出锁屏的唯一正确姿势**。
     *
     * 真机实测（小米电视 AIDL00 / Android 5.1）：本页进过 startLockTask() 之后，
     * 光调 finish() 是**无效**的 —— 页面会一直留在前台，家长点「返回首页」「紧急解除」、
     * 或从 Web 端远程重置，屏幕都不会退出（这才是「怎么点都不生效」的底层原因）。
     * 处于锁任务模式的 Activity 必须先 stopLockTask() 退出 kiosk，finish() 才会真正生效。
     */
    private fun exitLockAndFinish() {
        runCatching { stopLockTask() }
            .onSuccess { AppLogTV.i(TAG, "已退出锁任务模式（stopLockTask）") }
            .onFailure { AppLogTV.w(TAG, "stopLockTask 失败（可能未在锁任务中）: ${it.message}") }
        finish()
    }

    /**
     * 遥控器选中项：0 = 家长紧急解除，1 = 返回首页
     * TV 端只靠方向键操作，这里自己维护选中态，不依赖 Compose 焦点系统。
     */
    /** 【项1】锁屏按钮数量：0=家长紧急解除 1=返回首页（紧急电话已按电视端不适用移除） */
    private val BUTTON_COUNT = 2
    private val selectedIndex = mutableStateOf(0)

    /** 由确认键触发的动作，composable 消费后清空 */
    private val pendingAction = mutableStateOf<Int?>(null)

    /**
     * [BUGFIX] 遥控器按键在 Activity 层拦截。
     * 此前完全依赖 Compose 焦点：实测锁屏页按钮在无障碍树里 clickable=false / focusable=false，
     * 遥控器方向键与确认键按下毫无反应（触摸能点，但电视没有触摸屏），家长根本无法解锁。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 两个按钮（家长紧急解除/返回首页）循环切换选中
                    selectedIndex.value = (selectedIndex.value + 1) % BUTTON_COUNT
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    pendingAction.value = selectedIndex.value
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dismissReceiver) }
        // 仍处于锁定态就保持整机拦截（否则孩子按 HOME 就逃逸）；已解除才清空锁任务白名单
        if (!TimeoutExecutorTV.isLocked) {
            DeviceHardLock.release(this)
            AppLogTV.i("BlockOverlay", "锁屏结束 → 已解除整机拦截")
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [FIX-2026-09-12] 先接上「远程解除」广播（家长在 Web 端重置/调高限额时由 Service 发出）
        registerDismissReceiver()

        // 紧急解除期间：不显示锁屏，直接放行
        if (EmergencyReleaseServiceTV.isActive(this)) {
            TimeoutExecutorTV.unlock(this)
            exitLockAndFinish()
            return
        }

        // 全屏 + 锁屏 + 亮屏
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        // 隐藏系统导航栏（电视模式）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 【A7/A14】设备所有者时进「整机拦截」：锁任务白名单只留本应用，
        // 非白名单应用起不来、HOME/最近任务也退不出去（例外只有我们自己的验证页/安全阀页）
        if (GuardianDeviceAdminReceiver.isDeviceOwner(this)) {
            DeviceHardLock.engage(this)
            runCatching { startLockTask() }
                .onSuccess { AppLogTV.i("BlockOverlay", "【A7/A14】已进入锁任务模式（kiosk）") }
                .onFailure { AppLogTV.w("BlockOverlay", "startLockTask 失败: ${it.message}") }
        }

        setContent {
            // [FIX-2026-09-12] 自检兜底：状态机说「已解锁」而本页还挂着（进程重启/跨零点/广播丢失）时自行关闭
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2000)
                    if (!TimeoutExecutorTV.isLocked) {
                        AppLogTV.i(TAG, "状态机已解锁 → 关闭锁屏页")
                        exitLockAndFinish()
                        return@LaunchedEffect
                    }
                }
            }
            LockOverlayScreen(
                countdownMinutes = 15,
                reason = TimeoutExecutorTV.lockReason,
                selectedIndex = selectedIndex.value,
                pendingAction = pendingAction.value,
                onActionConsumed = { pendingAction.value = null },
                onParentOverride = {
                    // 家长紧急停用：限时解除（到期自动恢复守护），需验证密码
                    parentLoginLauncher.launch(
                        Intent(this, ParentLoginActivity::class.java).apply {
                            putExtra(ParentLoginActivity.EXTRA_ACTION_LABEL, "家长紧急解除")
                            putExtra(ParentLoginActivity.EXTRA_UNLOCK_ON_SUCCESS, true)
                            putExtra(ParentLoginActivity.EXTRA_EMERGENCY_RELEASE, true)
                        }
                    )
                },
                onGoHome = {
                    TimeoutExecutorTV.unlock(this)
                    exitLockAndFinish()
                }
            )
        }
    }

    /**
     * 拦截物理按键（防止通过遥控器返回键关闭）
     */
    override fun onUserLeaveHint() {
        // 用户尝试离开，不处理
    }

    override fun onUserInteraction() {
        // 用户交互，不处理
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 拦截返回键和音量键
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                true  // 拦截
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                // 允许确认键（用于 UI 操作）
                false
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }
}

// ==================== Compose 锁屏界面 ====================

@Composable
fun LockOverlayScreen(
    countdownMinutes: Int,
    reason: String = "",
    onParentOverride: () -> Unit,
    onGoHome: () -> Unit,
    selectedIndex: Int = 0,
    pendingAction: Int? = null,
    onActionConsumed: () -> Unit = {}
) {
    // 【项4】心跳状态（与主界面同源：CloudSyncServiceTV）+ 每秒 tick 驱动「最后心跳 X 分钟前」
    val hbOnline by HeartbeatStatus.online.collectAsState()
    val hbAt by HeartbeatStatus.lastSuccessAt.collectAsState()
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }

    // 遥控器确认键触发的动作（由 Activity 的 dispatchKeyEvent 传入）
    LaunchedEffect(pendingAction) {
        when (pendingAction) {
            0 -> onParentOverride()
            1 -> onGoHome()
        }
        if (pendingAction != null) onActionConsumed()
    }

    // 【项1】底图改为全红纯色（原「蓝→红」渐进色已去除）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB71C1C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 标题
            Text(
                text = "⏰ 看电视时间到啦！",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 提示文字（【A3】按锁屏原因区分：就寝时段 / 时长用完）
            Text(
                text = if (reason.contains("就寝")) "现在是就寝时间\n快去休息吧～"
                       else "今日电视时间已用完\n明天再来吧～",
                fontSize = 40.sp,
                color = Color(0xFFE0E0E0),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 【项1】原「⏳ 15 分钟后可以重新开始」属无意义文案，已删除。
            // 【项4】改为显示心跳状态 + 最后与服务器心跳的时间（与主界面一致）
            Text(
                text = buildString {
                    append(if (hbOnline) "\u25CF 已连接服务器" else "\u25CB 未连接服务器")
                    append("  \u00B7  最后心跳：")
                    append(HeartbeatStatus.describeLastHeartbeat(hbAt, nowTick))
                },
                fontSize = 20.sp,
                color = Color.White.copy(alpha = 0.85f)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 【项1】两个按钮并排一行，确保一屏放得下（遥控器左右切换，黄色描边=当前选中）
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onParentOverride,
                    modifier = Modifier
                        .width(260.dp)
                        .height(80.dp)
                        .border(
                            width = if (selectedIndex == 0) 4.dp else 0.dp,
                            color = if (selectedIndex == 0) Color(0xFFFFEB3B) else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        ),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32)
                    )
                ) {
                    Text(
                        text = "🔑 家长紧急解除",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onGoHome,
                    modifier = Modifier
                        .width(260.dp)
                        .height(80.dp)
                        .border(
                            width = if (selectedIndex == 1) 4.dp else 0.dp,
                            color = if (selectedIndex == 1) Color(0xFFFFEB3B) else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        ),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "🏠 返回首页",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

        }
    }
}
