// [android-tv] 主 Activity — TvHomeActivity
// 小趴菜 TVOS 版 — 电视端主界面（Compose）
// 功能：logo/家长账号展示、使用时长、白名单/家长设置/公告（均需家长验证）、家长验证、家长紧急解除
// 适配：720p/1080p 电视自适应（BoxWithConstraints + weight 布局），固定单屏无滚动

package com.xiaopacai.tvos.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.tvos.R
import com.xiaopacai.tvos.service.AntiBypassServiceTV
import com.xiaopacai.tvos.service.CloudSyncServiceTV
import com.xiaopacai.tvos.service.TVGuardianForegroundService
import com.xiaopacai.tvos.ui.announcement.AnnouncementStore
import com.xiaopacai.tvos.ui.login.ParentLoginActivity
import com.xiaopacai.tvos.ui.settings.SettingsActivity
import com.xiaopacai.tvos.ui.theme.XiaopacaiTVTheme
import com.xiaopacai.tvos.ui.whitelist.WhitelistActivity
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import com.xiaopacai.tvos.util.EmergencyReleaseServiceTV
import com.xiaopacai.tvos.util.HeartbeatStatus
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import com.xiaopacai.tvos.util.UsageStatsHelperTV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 Activity — 电视端主界面
 */
class TvHomeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TvHomeActivityTV"
    }

    /** 家长验证通过后要执行的动作（白名单 / 家长设置 / 公告 三个受控入口共用），入参为访问令牌 */
    private var pendingAfterVerify: ((String) -> Unit)? = null

    private val parentAccountState = mutableStateOf("")
    private val emergencyRemainingState = mutableStateOf(0)
    /** 今日已用**秒数**（真实统计：UsageStatsManager；秒级 + 单调，供倒计时平滑递减） */
    private val usedSecondsState = mutableStateOf(0)
    /** 每日限额（读自 DataStore，与「家长设置」保存的值一致） */
    private val dailyLimitState = mutableStateOf(60)
    /** 本次用量采样时刻：倒计时按「采样时刻 + 每秒流逝」平滑递减 */
    private val usageSampledAtState = mutableStateOf(0L)
    /** 设备是否已绑定到家长账号（绑定后 Web 端才可见、才能下发策略/公告） */
    private val deviceBoundState = mutableStateOf(false)
    /** 【项2】无障碍拦截曾开启但现已失效（应用更新后系统会解除绑定）→ 主界面警示 */
    private val accessibilityLostState = mutableStateOf(false)

    /** [TASK-GUARD-TOGGLE-V1] 管控启停（家长 Web 端「设备管理」切换；停用时不锁屏并在首页提示） */
    private val guardEnabledState = mutableStateOf(true)

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val verifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingAfterVerify
        pendingAfterVerify = null
        if (result.resultCode == RESULT_OK) {
            val token = result.data?.getStringExtra(ParentLoginActivity.EXTRA_ACCESS_TOKEN) ?: ""
            action?.invoke(token)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 【B10】启动时检查一次更新（6 小时节流；有新版才拉起升级页）
        maybeCheckUpgrade()

        startGuardianServices()

        // 首页数据轮询：每 30 秒刷新（与守护服务判定周期一致）
        uiScope.launch {
            while (true) {
                refreshHomeData()
                delay(30_000)
            }
        }

        setContent {
            XiaopacaiTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        parentAccount = parentAccountState.value,
                        emergencyRemaining = emergencyRemainingState.value,
                        usedSeconds = usedSecondsState.value,
                        dailyLimit = dailyLimitState.value,
                        usageSampledAt = usageSampledAtState.value,
                        deviceBound = deviceBoundState.value,
                        accessibilityLost = accessibilityLostState.value,
                        guardEnabled = guardEnabledState.value,
                        onRestoreGuard = {
                            EmergencyReleaseServiceTV.deactivate(this@TvHomeActivity)
                            refreshHomeData()
                        },
                        onNavigateToWhitelist = {
                            requireParentVerify("白名单") {
                                startActivity(Intent(this, WhitelistActivity::class.java))
                            }
                        },
                        onNavigateToSettings = {
                            requireParentVerify("家长设置") {
                                startActivity(Intent(this, SettingsActivity::class.java))
                            }
                        },
                        onParentVerify = {
                            startActivity(Intent(this, ParentLoginActivity::class.java))
                        },
                        onEmergencyRelease = {
                            startActivity(
                                Intent(this, ParentLoginActivity::class.java).apply {
                                    putExtra(ParentLoginActivity.EXTRA_ACTION_LABEL, "家长紧急解除")
                                    putExtra(ParentLoginActivity.EXTRA_EMERGENCY_RELEASE, true)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHomeData()
    }

    /** 刷新首页数据：家长账号 / 紧急解除剩余 / 每日限额（DataStore）/ 今日已用（真实统计） */
    private fun refreshHomeData() {
        uiScope.launch {
            parentAccountState.value =
                SharedPreferenceHelperTV.getParentAccount(this@TvHomeActivity).first()
            emergencyRemainingState.value =
                EmergencyReleaseServiceTV.getRemainingMinutes(this@TvHomeActivity)
            dailyLimitState.value =
                SharedPreferenceHelperTV.getDailyLimit(this@TvHomeActivity).first()
            val (usedSeconds, anchorAt) = withContext(Dispatchers.IO) {
                runCatching {
                    // [FIX-2026-09-12] 秒级 + 交互增量估算（对标手机端 countdownSnapshot）：
                    // 倒计时只增不减，系统用量未进位时用交互增量延续 → 不再卡在 18:xx 循环
                    UsageStatsHelperTV.sampleDisplayUsedSeconds(this@TvHomeActivity)
                }.getOrElse { 0 to System.currentTimeMillis() }
            }
            usedSecondsState.value = usedSeconds
            usageSampledAtState.value = anchorAt
            // [首页内嵌公告框] 与公告框数据源同步（服务端已按 published 过滤）
            withContext(Dispatchers.IO) {
                runCatching { CloudSyncServiceTV.refreshAnnouncementsIntoStore(this@TvHomeActivity) }
            }
            deviceBoundState.value =
                SharedPreferenceHelperTV.getDeviceBound(this@TvHomeActivity).first()
            // 【项2】曾开启过无障碍但现在系统里是关的 → 说明被更新/系统解除了，需要提醒家长
            val accessEverOn = SharedPreferenceHelperTV
                .getAccessibilityEverOn(this@TvHomeActivity).first()
            accessibilityLostState.value = accessEverOn && !isAccessibilityServiceEnabled()
            // [TASK-GUARD-TOGGLE-V1] 管控启停（内存值由 P2P/心跳同步，停用则首页出提示）
            guardEnabledState.value = TimeoutExecutorTV.isGuardEnabled()
            // 进程重启后用持久化的最后心跳时间兜底显示
            HeartbeatStatus.seedLastSuccess(
                SharedPreferenceHelperTV.getLastSyncTime(this@TvHomeActivity).first()
            )
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /** 受控入口：先做家长验证，通过后把访问令牌交给 action */
    private fun requireParentVerify(label: String, action: (String) -> Unit) {
        pendingAfterVerify = action
        verifyLauncher.launch(
            Intent(this, ParentLoginActivity::class.java).apply {
                putExtra(ParentLoginActivity.EXTRA_ACTION_LABEL, label)
            }
        )
    }

    private fun startGuardianServices() {
        startService(Intent(this, TVGuardianForegroundService::class.java))
        startService(Intent(this, CloudSyncServiceTV::class.java))
        startService(Intent(this, AntiBypassServiceTV::class.java))
    }
    /**
     * 【B10】自动检查更新：6 小时内只查一次；发现新版本才拉起升级页（避免打扰）。
     */
    private fun maybeCheckUpgrade() {
        val prefs = getSharedPreferences("upgrade_check", MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0L)
        if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            when (val s = com.xiaopacai.tvos.util.UpgradeManagerTV.check(this@TvHomeActivity)) {
                is com.xiaopacai.tvos.util.UpgradeManagerTV.State.Available -> {
                    com.xiaopacai.tvos.util.AppLogTV.i("TvHome", "发现新版本 ${s.version}，拉起升级页")
                    runOnUiThread {
                        runCatching {
                            startActivity(
                                Intent(this@TvHomeActivity, com.xiaopacai.tvos.ui.upgrade.UpgradeActivity::class.java)
                            )
                        }
                    }
                }
                is com.xiaopacai.tvos.util.UpgradeManagerTV.State.UpToDate ->
                    com.xiaopacai.tvos.util.AppLogTV.i("TvHome", "已是最新版本 ${s.version}")
                is com.xiaopacai.tvos.util.UpgradeManagerTV.State.Failed ->
                    com.xiaopacai.tvos.util.AppLogTV.w("TvHome", "检查更新失败：${s.reason}")
            }
        }
    }

}

// ==================== Compose UI 组件 ====================

/**
 * 【项5】首页「已安装应用」卡片开关。
 * 该卡片对应 Web 端的「分类限额」模块（模块暂未启用）→ 先隐藏；改回 true 即可恢复。
 */
private const val SHOW_INSTALLED_APPS = false

/**
 * 主界面 — 固定单屏，所有按钮一屏可见，无下拉滚动
 */
@Composable
fun HomeScreen(
    parentAccount: String,
    emergencyRemaining: Int,
    usedSeconds: Int,
    dailyLimit: Int,
    usageSampledAt: Long,
    deviceBound: Boolean,
    accessibilityLost: Boolean,
    guardEnabled: Boolean,
    onRestoreGuard: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onParentVerify: () -> Unit,
    onEmergencyRelease: () -> Unit
) {
    // [首页内嵌公告框] 已发布公告（撤回/删除会被服务端过滤或 P2P clearance 移除）
    val announcements by AnnouncementStore.items.collectAsState()
    // 【项4】心跳状态：来自 CloudSyncServiceTV 的实时状态
    val heartbeatOnline by HeartbeatStatus.online.collectAsState()
    val heartbeatAt by HeartbeatStatus.lastSuccessAt.collectAsState()

    // 【项7】每秒 tick：驱动「剩余时长」倒计时与「最后心跳 X 分钟前」的相对时间
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxWidth = maxWidth

        val density = when {
            maxWidth <= 700.dp -> 0.80f
            maxWidth <= 1100.dp -> 1.00f
            else -> 1.25f
        }

        // [FIX-2026-09-12 布局预算] 真机实测 1280×720@213dpi = 960×541dp：加公告框后
        // 倒计时卡片被压到 107px，第三行（「今日已用 x 分钟」/「已超时…」）被静默裁掉。
        // 按实测重排：外框 24→20dp、间距 10→7dp、公告框 112→96dp，给倒计时卡片留出三行所需高度
        // （含「管控已停用」「无障碍失效」等警示行同时出现时也不裁）。
        val pad = (20 * density).dp
        val gap = (7 * density).dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = pad, top = pad, end = pad, bottom = pad),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                // ===== 顶部：logo + 标题 + 已登录家长账号 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.logo_xiaopacai),
                        contentDescription = "小趴菜",
                        modifier = Modifier.height((56 * density).dp)
                    )
                    Spacer(modifier = Modifier.width((12 * density).dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "小趴菜 \u00B7 电视守护",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = buildString {
                                append(
                                    if (parentAccount.isBlank()) "家长：未登录绑定"
                                    else "家长：$parentAccount"
                                )
                                append(if (deviceBound) "  \u00B7  设备已绑定" else "  \u00B7  设备未绑定")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (deviceBound) MaterialTheme.colorScheme.onSurfaceVariant
                            else Color(0xFFFFB74D),
                            maxLines = 1
                        )
                        // 【项4】心跳状态 + 最后与服务器心跳的时间（对标手机版首页连接状态）
                        Text(
                            text = buildString {
                                append(if (heartbeatOnline) "\u25CF 已连接服务器" else "\u25CB 未连接服务器")
                                append("  \u00B7  最后心跳：")
                                append(HeartbeatStatus.describeLastHeartbeat(heartbeatAt, nowTick))
                            },
                            fontSize = 11.sp,
                            color = if (heartbeatOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                            maxLines = 1
                        )
                    }
                    if (emergencyRemaining > 0) {
                        Text(
                            text = "\uD83C\uDD98 紧急解除中\n剩余 $emergencyRemaining 分钟",
                            fontSize = 12.sp,
                            color = Color(0xFFFF7043),
                            textAlign = TextAlign.End
                        )
                    }
                }

                // ===== 使用时长（弹性填充剩余高度）=====
                UsageSummaryCard(
                    usedSeconds = usedSeconds,
                    dailyLimit = dailyLimit,
                    inBedtime = TimeoutExecutorTV.isBedtimeNow(),
                    bedtimeLabel = TimeoutExecutorTV.bedtimeText(),
                    usageSampledAt = usageSampledAt,
                    nowTick = nowTick,
                    density = density,
                    guardDisabled = !guardEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                // ===== 公告：直接显示在倒计时框下方（不是按钮；无公告时整块不占位）=====
                AnnouncementCard(items = announcements, density = density)

                // 【项2】无障碍拦截失效警示（应用更新后系统会解除无障碍绑定，拦截层静默失效）
                if (accessibilityLost) {
                    Text(
                        text = "\u26A0 无障碍拦截已失效：应用更新后需重新开启（家长设置 \u2192 防护增强）",
                        fontSize = 13.sp,
                        color = Color(0xFFFFB74D),
                        maxLines = 1
                    )
                }

                // [TASK-GUARD-TOGGLE-V1] 管控被家长停用时的提示（孩子看到的是「没限制了」，家长要知道为什么）
                // 已并入倒计时卡片内部（见 UsageSummaryCard 的 guardDisabled 分支），不再单独占一行高度

                // ===== 已安装应用：【项5】暂时隐藏 =====
                // 该卡片对应 Web 端的「分类限额」模块（模块暂未启用），故先隐藏；
                // 代码原样保留，把 SHOW_INSTALLED_APPS 改回 true 即可恢复显示。
                if (SHOW_INSTALLED_APPS) {
                    Text(
                        text = "\uD83D\uDCFA 已安装应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy((14 * density).dp)
                    ) {
                        items(appList) { app ->
                            AppUsageCard(app, density = density)
                        }
                    }
                }

                // ===== 第一排：受控入口（需家长验证）=====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton("📺 白名单", onNavigateToWhitelist, density, Modifier.weight(1f))
                    ActionButton("⚙ 家长设置", onNavigateToSettings, density, Modifier.weight(1f))
                    // 「🔔 公告」按钮已移除：公告改为倒计时框下方内嵌展示（见 AnnouncementCard）
                }

                // ===== 第二排：家长验证 / 紧急解除 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton("🔑 家长验证", onParentVerify, density, Modifier.weight(1f))
                    ActionButton(
                        "🆘 家长紧急解除",
                        onEmergencyRelease,
                        density,
                        Modifier.weight(1f),
                        containerColor = Color(0xFFD32F2F)
                    )
                    // 【项6】仅在紧急解除生效期间显示「恢复管制」
                    if (emergencyRemaining > 0) {
                        ActionButton(
                            "\uD83D\uDEE1 恢复管制",
                            onRestoreGuard,
                            density,
                            Modifier.weight(1f),
                            containerColor = Color(0xFF2E7D32)
                        )
                    }
                }

                Text(
                    text = "🥬 小趴菜 · 守护孩子的健康使用习惯",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 使用时长卡片 — 【项7】对标手机版：显示「剩余可用时长」实时倒计时（HH:MM:SS，每秒递减）
 *   - 限额为 0（家长完全禁用）→ 显示「已禁用」
 *   - 已用完 → 00:00:00 +「已超时」
 *   - 剩余 ≤10 分钟 → 转橙色警示（与手机版同样的临期提醒语义）
 */
@Composable
fun UsageSummaryCard(
    usedSeconds: Int,
    dailyLimit: Int,
    inBedtime: Boolean = false,
    bedtimeLabel: String = "",
    usageSampledAt: Long,
    nowTick: Long,
    density: Float,
    /** [TASK-GUARD-TOGGLE-V1] 家长在 Web 端停用了本设备的管控（停用时不锁屏、数字不再减少） */
    guardDisabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 采样时刻的剩余毫秒 − 采样后已流逝的时间 = 平滑递减的剩余时长
    // [FIX-2026-09-12] 对标手机端修法（android-v3 UsageStatsCollector）：
    //   剩余 = 限额 −（上次采集已用 + **距采集的交互增量**），交互增量只在屏幕可交互时计、封顶一个采样周期。
    //   这样：看着电视 → 逐秒递减；熄屏/待机 → 不再虚减；系统用量分钟未进位 → 用增量延续，绝不回跳。
    val context = LocalContext.current
    val interactive = remember(nowTick) { UsageStatsHelperTV.isScreenInteractive(context) }
    val usedWithDeltaMillis = usedSeconds * 1000L +
        UsageStatsHelperTV.interactiveDeltaMs(usageSampledAt, nowTick, interactive)
    val remainingMillis = (dailyLimit.toLong() * 60_000L - usedWithDeltaMillis).coerceAtLeast(0L)
    val usedMinutes = (usedWithDeltaMillis / 60_000L).toInt()

    val disabled = dailyLimit <= 0
    val overtime = !disabled && remainingMillis <= 0L

    val bigText = when {
        inBedtime -> "就寝时段"
        guardDisabled -> "已停用"
        disabled -> "已禁用"
        overtime -> "00:00:00"
        else -> formatHms(remainingMillis)
    }
    val subText = when {
        inBedtime -> "$bedtimeLabel · 该休息了，明天再见～"
        guardDisabled -> "家长已停用管控 · 当前不做任何限制"
        disabled -> "家长已完全禁用今日使用"
        overtime -> "已超时 · 今日已用 $usedMinutes 分钟"
        else -> "今日已用 $usedMinutes 分钟 / 限额 $dailyLimit 分钟"
    }
    val accent = when {
        inBedtime -> Color(0xFF7E57C2)
        guardDisabled || disabled || overtime -> Color(0xFFEF5350)
        remainingMillis <= 10 * 60_000L -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier,
        onClick = {},
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding((12 * density).dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (overtime || disabled || guardDisabled) "今日使用时长" else "剩余可用时长",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height((3 * density).dp))
            Text(
                text = bigText,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Spacer(modifier = Modifier.height((4 * density).dp))
            Text(
                text = subText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 首页内嵌公告框 —— 直接显示在「剩余可用时长」倒计时框下方（不再是按钮）
 *
 * 展示口径：只显示**已发布**的公告。服务端 /api/v1/device/announcements 已按
 * status=published + 目标设备 + 有效期内 过滤（撤回 revoked / 删除的记录不会返回），
 * 实时撤回还会通过 P2P announcement_clear 把已展示的条目移除。
 *
 * 版式：固定高度的框（不随内容长短变化，「整体比例协调」）；多条 → 每 8 秒自动轮播并显示 i/N；
 * 长内容 → 框内最多两行 + 省略号。无公告时整块不占位，倒计时框自动补满。
 */
@Composable
fun AnnouncementCard(
    items: List<AnnouncementStore.Item>,
    density: Float
) {
    if (items.isEmpty()) return

    var index by remember(items.size) { mutableStateOf(0) }
    LaunchedEffect(items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(8_000)
            index = (index + 1) % items.size
        }
    }
    val current = items[index.coerceIn(0, items.lastIndex)]
    val accent = when (current.priority) {
        "urgent" -> Color(0xFFE53935)
        "important" -> Color(0xFFFF7043)
        else -> Color(0xFF4CAF50)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height((96 * density).dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = (18 * density).dp, vertical = (12 * density).dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(text = "\uD83D\uDD14", fontSize = (18 * density).sp)
            Spacer(modifier = Modifier.width((10 * density).dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (items.size > 1) {
                        Spacer(modifier = Modifier.width((8 * density).dp))
                        Text(
                            text = "${index + 1} / ${items.size} 条",
                            fontSize = (11 * density).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Spacer(modifier = Modifier.height((6 * density).dp))
                Text(
                    text = current.content.ifBlank { "（无正文）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 毫秒 → HH:MM:SS（对标手机版 UsageStatsCollector.formatHms 的显示形式） */
private fun formatHms(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
}

/**
 * 操作按钮
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    density: Float,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(0xFF4CAF50)
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        modifier = modifier.height((62 * density).dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1
        )
    }
}

/**
 * 应用使用卡片
 */
@Composable
fun AppUsageCard(app: AppItem, density: Float) {
    Card(
        modifier = Modifier
            .width((140 * density).dp)
            .height((84 * density).dp),
        onClick = {},
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding((12 * density).dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFFFFFFF),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height((2 * density).dp))
            Text(
                text = "${app.usedMinutes} 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = if (app.usedMinutes > 30) Color(0xFFFF9800) else Color(0xFFB0B0B0),
                maxLines = 1
            )
        }
    }
}

/**
 * 已安装应用列表（模拟数据，实际从数据库读取）
 */
private val appList = listOf(
    AppItem("tv.danmaku.bili", "哔哩哔哩 TV", 25),
    AppItem("com.duokan.airplay.tvs", "小米投屏", 15),
    AppItem("com.dmtech.dmbdmb", "大芒果", 30),
    AppItem("com.huliao.video", "黄瓜视频", 45),
    AppItem("com.leju.lelive", "乐视听电视", 10)
)

/**
 * 应用使用数据
 */
data class AppItem(
    val packageName: String,
    val name: String,
    val usedMinutes: Int
)

/** 【项2】系统的无障碍服务里是否已启用我们的守护服务 */
private fun isAccessibilityServiceEnabled(): Boolean = runCatching {
    val ctx = com.xiaopacai.tvos.XiaopacaiTVApp.INSTANCE
    @Suppress("DEPRECATION")
    val enabled = android.provider.Settings.Secure.getString(
        ctx.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    enabled.split(':').any { it.startsWith("${ctx.packageName}/") }
}.getOrDefault(false)
