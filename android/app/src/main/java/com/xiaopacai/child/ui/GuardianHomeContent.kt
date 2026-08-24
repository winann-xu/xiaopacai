package com.xiaopacai.child.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.xiaopacai.child.p2p.DiscoveredParent
import com.xiaopacai.child.p2p.P2PConnectionState
import com.xiaopacai.child.p2p.PairingManager
import com.xiaopacai.child.p2p.PairingState
import com.xiaopacai.child.p2p.rejectionHintText
import com.xiaopacai.child.p2p.isRateLimitedRejectionCode
import com.xiaopacai.child.BuildConfig
import com.xiaopacai.child.ui.components.SystemGateDialog
import com.xiaopacai.child.ui.components.AboutDialog
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.service.UsageStatsCollector
import com.xiaopacai.child.service.UsageStatsCollector.CountdownSnapshot
import com.xiaopacai.child.service.UsageStatsCollector.Companion.formatHms
import com.xiaopacai.child.ui.settings.AppCategoryActivity
import com.xiaopacai.child.ui.settings.GuardianStatusActivity
import com.xiaopacai.child.ui.scan.QrScannerActivity
import com.xiaopacai.child.ui.parent.QrCodeGenerator
import com.xiaopacai.child.util.BindingStatusChecker
import com.xiaopacai.child.util.LocalDataWipe
import org.json.JSONObject

/**
 * [TASK-D1-05][TASK-D2-01] 小趴菜儿童端守护主页
 *
 * 展示守护状态的核心页面，包含：
 * - 剩余使用时长（今日，从 UsageStatsCollector 实时获取）
 * - 超时倒计时（动态）
 * - 家长公告区
 * - P2P 连接状态
 * - 设置入口
 *
 * 数据来源：UsageStatsCollector（前台服务中的时长采集器）
 */

/**
 * 公告数据（从 P2P 同步获取，本地数据库缓存）
 */
data class Announcement(
    val id: String,
    val title: String,
    val content: String,
    val time: String,
    val priority: Int,  // 0=普通 1=重要 2=紧急
    // [TASK-PRELAUNCH-P3] 紧急公告回执状态（列表标注已确认/未确认）
    val acknowledged: Boolean = false
)

@Composable
fun GuardianHomeContent(
    onOpenSettings: () -> Unit = {},
    onOpenPermissionGuide: () -> Unit = {},
    onSwitchToParent: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // === 从时长采集器获取实时数据 ===
    val collector = GuardianForegroundService.getCollector()

    // 状态（从采集器初始化，Compose 重组时自动刷新）
    // [TASK-PRELAUNCH-P4] 已用按调整后口径（重置当日限额后从 0 重新计时）
    var todayUsedMinutes by remember { mutableStateOf(collector?.todayAdjustedMinutes?.toInt() ?: 0) }
    var dailyLimitMinutes by remember { mutableStateOf(collector?.todayLimitMinutes?.toInt() ?: 120) }
    var stopMode by remember { mutableStateOf(collector?.stopMode ?: "none") }
    var isTimeoutActive by remember { mutableStateOf(collector?.isTimeoutActive ?: false) }
    var resetOffsetMinutes by remember { mutableStateOf(collector?.resetOffsetMinutes?.toInt() ?: 0) }

    // [FIX-LEGACY-c] 使用共享 P2P 连接服务的实时状态，不再硬编码 DISCONNECTED
    val sharedConnection = remember { GuardianForegroundService.getP2PConnection() }
    val connectionState by sharedConnection.connectionState.collectAsState()
    // [TASK-PRELAUNCH-FIX-SCAN] 确定性握手拒绝提示（解绑/换账号/指纹不匹配等）
    val handshakeRejection by sharedConnection.handshakeRejection.collectAsState()
    val rejectionText = handshakeRejection?.let { rejectionHintText(it.code, it.reason) }
    // [TASK-PRELAUNCH-FIX-RATELIMIT] 限速退避提示（临时性：自动重连中，非错误终态）
    val isRateLimited = handshakeRejection?.let { isRateLimitedRejectionCode(it.code) } == true

    // BUG-0810-10: 关于对话框状态
    var showAboutDialog by remember { mutableStateOf(false) }

    // P2P-FIX-B: 配对管理器与发现状态
    val pairingManager = remember { PairingManager(context) }
    val pairingState by pairingManager.pairingState.collectAsState()
    val discoveredParents by pairingManager.discoveredParents.collectAsState()
    var showPairingDialog by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("9527") }
    var pairingCode by remember { mutableStateOf("") }

    // [REQ] 相机扫码配对 / 我的二维码（被扫）
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var showMyQr by remember { mutableStateOf(false) }
    var myQrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // [REQ] 家长密码验证（应用分类/设置/权限管理统一走此门槛）
    var showParentPwd by remember { mutableStateOf(false) }
    var pendingProtectedAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // [TASK-MILESTONE-V3] 需求 3：儿童端换绑前旧账号残留确认（本地业务数据/旧绑定信息）
    var pendingRebindAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // [TASK-REBIND-GATE] 换绑前置检查：设备仍处于绑定状态时禁止清空重绑（必须先解绑）
    var rebindBusy by remember { mutableStateOf(false) }
    var rebindBlockedMessage by remember { mutableStateOf<String?>(null) }

    /**
     * [TASK-REBIND-GATE] 换绑前先查服务端绑定状态：
     * - 已绑定（bound=true）→ 弹「无法重新绑定」拦截，必须先由原家长端解绑；
     * - 未绑定（bound=false）→ 走旧残留确认，确认后清空并重绑；
     * - 查询失败/未登录 → 同样拦截，避免绕过归属纪律。
     */
    fun checkRebindAllowed(action: () -> Unit) {
        val deviceId = LocalDataWipe.getLocalDeviceId(context)
        if (deviceId == null) {
            LocalDataWipe.resetDeviceIdentitySilently(context)
            action()
            return
        }
        rebindBusy = true
        rebindBlockedMessage = null
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = BindingStatusChecker.check(context, deviceId)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                rebindBusy = false
                when (result) {
                    is BindingStatusChecker.CheckResult.Bound ->
                        rebindBlockedMessage = "设备当前已绑定家长账号" +
                            (result.ownerAccount?.let { "（$it）" } ?: "") +
                            "，请先在原家长端解绑后再重新绑定。"
                    is BindingStatusChecker.CheckResult.NotBound -> pendingRebindAction = action
                    is BindingStatusChecker.CheckResult.Failed ->
                        rebindBlockedMessage = "无法确认设备绑定状态：${result.reason}，请先登录家长账号后重试。"
                }
            }
        }
    }

    /** 配对入口统一把关：有旧残留先弹确认（确认后全清），无残留静默重置设备身份（D2 新身份） */
    fun requestPairing(action: () -> Unit) {
        if (LocalDataWipe.hasChildResidue(context)) {
            checkRebindAllowed(action)
        } else {
            LocalDataWipe.resetDeviceIdentitySilently(context)
            action()
        }
    }

    fun handleQrScanResult(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "pairing", "web_relay" -> {
                    val ips = obj.optJSONArray("ips")
                    val host = obj.optString("host").ifBlank { ips?.optString(0) ?: "" }
                    val port = obj.optInt("port", 9527)
                    val code = obj.optString("pairingCode", "")
                    val fp = obj.optString("fingerprint", "")
                    if (host.isBlank()) {
                        scanMessage = "二维码缺少可连接地址"
                        return
                    }
                    // [TASK-MILESTONE-V3] 需求 3：旧残留确认后再连接；device_id 在清除后读取（全新身份）
                    val action = {
                        val prefs = context.getSharedPreferences("guardian_prefs", android.content.Context.MODE_PRIVATE)
                        val deviceId = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString()
                        val scope = kotlinx.coroutines.CoroutineScope(
                            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
                        )
                        scope.launch {
                            GuardianForegroundService.getP2PConnection().connect(
                                host = host,
                                port = port,
                                // [SEC-P1] 二维码携带可信指纹（Web 3.0 已下发）时固定比对；
                                // 旧服务端二维码无指纹时仅扫码引导流程允许 TOFU（红线 R3.x）
                                expectedFingerprint = fp.ifBlank { null },
                                deviceId = deviceId,
                                deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
                                pairingCode = code,
                                // [REQ] web_relay 二维码 → 经 Web 中继连接（否则服务器不登记中继会话，无法路由）
                                isRelay = obj.optString("type") == "web_relay",
                                allowTofu = fp.isBlank(),
                                scope = scope
                            )
                        }
                        scanMessage = "已通过扫码连接家长端 $host:$port"
                    }
                    requestPairing(action)
                }
                else -> scanMessage = "二维码内容无法识别"
            }
        } catch (e: Exception) {
            scanMessage = "二维码解析失败：${e.message}"
        }
    }

    val qrScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
        if (text.isNullOrBlank()) {
            scanMessage = "未识别到二维码，请重试"
            return@rememberLauncherForActivityResult
        }
        handleQrScanResult(text)
    }

    // [FIX] 必须通过 launcher 启动，否则扫描结果回调不会触发
    fun launchQrScan() {
        try {
            qrScanLauncher.launch(android.content.Intent(context, QrScannerActivity::class.java))
        } catch (e: Exception) {
            scanMessage = "无法打开相机：${e.message}"
        }
    }

    // 扫码结果提示
    scanMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { scanMessage = null },
            title = { Text("扫码结果") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { scanMessage = null }) { Text("知道了") }
            }
        )
    }

    // 我的二维码（家长端扫码识别）
    if (showMyQr) {
        AlertDialog(
            onDismissRequest = { showMyQr = false },
            title = { Text("我的二维码") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    myQrBitmap?.let { bmp ->
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "儿童端二维码",
                            modifier = Modifier.size(260.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("请家长端“扫码配对”识别此设备", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showMyQr = false }) { Text("关闭") }
            }
        )
    }

    // [TASK-ACCOUNT-V1] 家长云端验证门禁（应用分类/设置/权限管理共用，统一 SystemGateDialog）
    if (showParentPwd) {
        SystemGateDialog(
            title = "家长验证",
            description = "此操作涉及守护设置，请输入家长账号邮箱与登录密码。",
            confirmText = "验证",
            onDismiss = {
                showParentPwd = false
                pendingProtectedAction = null
            },
            onVerified = {
                val action = pendingProtectedAction
                showParentPwd = false
                pendingProtectedAction = null
                action?.invoke()
            }
        )
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            pairingManager.destroy()
        }
    }

    // [TASK-HARDENING-V1.1.1] Bug2-A：每秒本地倒计时（HH:MM:SS）
    // 剩余 = 今日限额 −（最近采集已用 + 距最近采集的交互增量）；
    // 采集失效/服务未运行 → healthy=false，如实显示「守护失效」，不假倒计时
    var countdown by remember { mutableStateOf(CountdownSnapshot.EMPTY) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = GuardianForegroundService.getCollector()
            if (c != null) {
                countdown = c.countdownSnapshot()
                // [TASK-HARDENING-V1.1.1] Bug2-B：归零立即锁定（双保险消除 ≤60s 采集空窗）
                c.lockIfCountdownExpired()
                // [TASK-PRELAUNCH-P4] 已用按调整后口径（每 tick 同步）
                todayUsedMinutes = c.todayAdjustedMinutes.toInt()
                dailyLimitMinutes = c.todayLimitMinutes.toInt().coerceAtLeast(1)
                stopMode = c.stopMode
                isTimeoutActive = c.isTimeoutActive
                resetOffsetMinutes = c.resetOffsetMinutes.toInt()
            } else {
                // 守护服务未运行 → 守护失效
                countdown = CountdownSnapshot.EMPTY
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // 进度/告警按秒级快照计算（快照失效时进度条不虚构）
    val usagePercent = if (countdown.limitMillis > 0)
        (countdown.usedMillis.toFloat() / countdown.limitMillis).coerceIn(0f, 1f) else 0f
    val isNearLimit = countdown.healthy && countdown.limitMillis > 0 &&
        !countdown.isTimeoutActive &&
        countdown.remainingMillis in 1..(15 * 60_000L)

    // 从数据库加载公告（30 秒刷新一次）
    var announcements by remember { mutableStateOf(emptyList<Announcement>()) }

    // [TASK-OPT-12-P2] 公告即时弹窗（需求4）：未读普通公告到达后立即弹窗，不再依赖角标+主动查看
    // 紧急公告（priority>=2）由全屏覆盖层处理，此处跳过
    var announcementToShow by remember { mutableStateOf<Announcement?>(null) }
    var shownAnnouncementIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            try {
                val passphrase = com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(context)
                val db = com.xiaopacai.child.XiaopacaiApp.instance.database.getReadable(passphrase)
                // [TASK-PRELAUNCH-P3] 紧急公告（priority>=2）无论已读与否都保留在列表（已确认后仍保留记录）；
                // 普通公告仍只展示未读（读后从列表消失）
                val cursor = db.rawQuery(
                    """SELECT announcement_id, title, content, priority, created_at, acknowledged_at
                       FROM announcements
                       WHERE (priority >= 2 OR is_read = 0)
                       AND (expires_at = 0 OR expires_at > ?)
                       ORDER BY priority DESC, created_at DESC LIMIT 10""",
                    arrayOf((System.currentTimeMillis() / 1000).toString())
                )
                val list = mutableListOf<Announcement>()
                cursor.use {
                    while (it.moveToNext()) {
                        list.add(Announcement(
                            id = it.getString(0),
                            title = it.getString(1),
                            content = it.getString(2),
                            priority = it.getInt(3),
                            time = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(it.getLong(4) * 1000)),
                            acknowledged = it.getLong(5) > 0
                        ))
                    }
                }
                announcements = list

                // [TASK-OPT-12-P2] 挑选一条未展示过的普通公告弹窗（紧急公告走全屏覆盖层）
                if (announcementToShow == null) {
                    val candidate = list.firstOrNull {
                        it.priority < 2 && it.id !in shownAnnouncementIds
                    }
                    if (candidate != null) {
                        announcementToShow = candidate
                        shownAnnouncementIds = shownAnnouncementIds + candidate.id
                    }
                }
            } catch (_: Exception) {
                // 数据库未就绪时使用空列表
            }
        }
    }

    // [TASK-OPT-12-P2] 普通公告弹窗（关闭即标记已读）
    announcementToShow?.let { announcement ->
        AlertDialog(
            onDismissRequest = {
                markAnnouncementRead(context, announcement.id)
                announcementToShow = null
            },
            title = {
                Text(announcement.title, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = announcement.content,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    markAnnouncementRead(context, announcement.id)
                    announcementToShow = null
                }) {
                    Text("知道了")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // === 1. P2P 连接状态栏 ===
        item {
            ConnectionStatusBar(connectionState)
        }

        // === 2. 剩余时长卡片（核心） ===
        item {
            RemainingTimeCard(
                usedMinutes = todayUsedMinutes,
                limitMinutes = dailyLimitMinutes,
                usagePercent = usagePercent,
                isNearLimit = isNearLimit,
                isTimeoutActive = isTimeoutActive,
                stopMode = stopMode,
                resetOffsetMinutes = resetOffsetMinutes,
                countdown = countdown
            )
        }

        // === 3. 超时停用状态横幅（仅超时时显示） ===
        if (isTimeoutActive) {
            item {
                TimeoutBanner(stopMode = stopMode)
            }
        }

        // === 4. 家长公告区 ===
        item {
            Text(
                text = "📢 家长公告",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (announcements.isEmpty()) {
            // [TASK-MILESTONE-V3] 需求 15 走查：公告空态占位
            item {
                Text(
                    text = "暂无家长公告",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(announcements, key = { it.id }) { announcement ->
                AnnouncementCard(announcement)
            }
        }

        // === 5. P2P 配对入口（P2P-FIX-B） ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🔗 连接家长端",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            // [FIX-LEGACY-c] 优先用实时 P2P 连接状态，更精确反映链路状态
                            connectionState == P2PConnectionState.CONNECTED -> "✅ 已连接到家长端"
                            // [TASK-PRELAUNCH-FIX-RATELIMIT] 限速退避：RECONNECTING 中优先
                            // 显示等待提示（临时性，冷却后自动恢复）
                            isRateLimited -> "⏳ ${handshakeRejection?.let { rejectionHintText(it.code, it.reason) } ?: "尝试次数过多，请稍后自动重试"}"
                            connectionState == P2PConnectionState.RECONNECTING -> "◉ 重连中..."
                            connectionState == P2PConnectionState.CONNECTING ||
                                connectionState == P2PConnectionState.HANDSHAKING -> "正在连接..."
                            // [TASK-PRELAUNCH-FIX-SCAN] 确定性拒绝：优先展示具体原因
                            rejectionText != null -> "❌ $rejectionText"
                            pairingState == PairingState.SCANNING -> "正在扫描局域网..."
                            pairingState == PairingState.FOUND_PARENT -> "已发现 ${discoveredParents.size} 台家长端"
                            pairingState == PairingState.PAIRING -> "正在配对..."
                            pairingState == PairingState.ERROR -> "❌ 配对失败"
                            else -> "点击下方按钮开始扫描局域网中的家长端"
                        },
                        fontSize = 13.sp,
                        // [TASK-PRELAUNCH-FIX-RATELIMIT] 限速为警示色（橙），确定性拒绝为错误色
                        color = when {
                            isRateLimited -> Color(0xFFE6A23C)
                            rejectionText != null -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                launchQrScan()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (pairingState == PairingState.SCANNING) "停止扫描" else "扫描家长端",
                                fontSize = 13.sp
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val prefs = context.getSharedPreferences("guardian_prefs", android.content.Context.MODE_PRIVATE)
                                val deviceId = prefs.getString("device_id", null) ?: "unknown"
                                myQrBitmap = QrCodeGenerator.generateChildQrCode(deviceId, "模拟器测试设备")
                                showMyQr = true
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("我的二维码", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { showPairingDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("手动连接", fontSize = 13.sp)
                        }
                    }
                    // [DEBUG] 模拟器无真实相机，调试构建提供扫码结果注入入口
                    if (BuildConfig.DEBUG) {
                        TextButton(
                            onClick = {
                                val testQr = JSONObject().apply {
                                    put("type", "pairing")
                                    put("version", "2.2")
                                    put("deviceId", "parent-debug-test")
                                    put("port", 9528)
                                    // 空指纹 = 跳过证书校验（模拟器测试用，真机扫码会带真实指纹）
                                    put("fingerprint", "")
                                    put("pairingCode", "123456")
                                    put("ips", org.json.JSONArray(listOf("10.0.2.2")))
                                    put("timestamp", System.currentTimeMillis() / 1000)
                                }.toString()
                                qrScanLauncher.launch(
                                    android.content.Intent(context, QrScannerActivity::class.java)
                                        .putExtra(QrScannerActivity.EXTRA_TEST_RESULT, testQr)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("调试：模拟扫码配对", fontSize = 12.sp) }
                    }
                }
            }
        }

        // === 6. 快捷入口 ===
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 设置按钮
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Settings,
                    label = "设置",
                    onClick = {
                        pendingProtectedAction = onOpenSettings
                        showParentPwd = true
                    }
                )
                // 权限管理按钮
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Security,
                    label = "权限管理",
                    onClick = {
                        pendingProtectedAction = onOpenPermissionGuide
                        showParentPwd = true
                    }
                )
                // 关于按钮
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Info,
                    label = "关于",
                    onClick = { showAboutDialog = true }
                )
            }
        }

        // [TASK-OPT-12-P2] 应用分类设置 + 守护状态 快捷入口（需求1/6）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Category,
                    label = "应用分类",
                    onClick = {
                        pendingProtectedAction = {
                            try {
                                context.startActivity(
                                    Intent(context, AppCategoryActivity::class.java)
                                )
                            } catch (e: Exception) {
                                Log.e("GuardianHome", "打开应用分类页失败: ${e.message}")
                            }
                        }
                        showParentPwd = true
                    }
                )
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FactCheck,
                    label = "守护状态",
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(context, GuardianStatusActivity::class.java)
                            )
                        } catch (e: Exception) {
                            Log.e("GuardianHome", "打开守护状态页失败: ${e.message}")
                        }
                    }
                )
            }
        }

        // [TASK-ROLE-P1] 家长端入口（需密码）
        if (onSwitchToParent != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.SupervisorAccount,
                        label = "切换到家长端",
                        onClick = onSwitchToParent
                    )
                }
            }
        }

        // 底部间距
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    // [TASK-MILESTONE-V3] 需求 7：关于对话框（双端统一组件，版本号跟随 Git，年份动态）
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    // [TASK-MILESTONE-V3] 需求 3：儿童端检测到旧账号数据，确认后才清除并继续绑定新家长
    if (pendingRebindAction != null) {
        var rebindError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!rebindBusy) pendingRebindAction = null },
            title = { Text("检测到旧账号数据") },
            text = {
                Column {
                    Text(
                        "本设备已绑定过家长账号并存在历史数据（公告、策略、使用记录等）。\n\n" +
                            "继续绑定新家长将清除旧数据并重置设备身份，旧家长将无法再管控本设备。清除范围：\n" +
                            "• 公告、策略、应用分类、使用记录与报告缓存\n" +
                            "• 中继连接配置与本地缓存\n" +
                            "• 本机设备身份（重新生成）",
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    if (rebindError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(rebindError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = pendingRebindAction ?: return@TextButton
                        rebindBusy = true
                        rebindError = null
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val wipe = LocalDataWipe.wipeAll(context)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                rebindBusy = false
                                when (wipe) {
                                    is LocalDataWipe.WipeResult.Success -> {
                                        pendingRebindAction = null
                                        action()
                                    }
                                    is LocalDataWipe.WipeResult.Failed -> rebindError = wipe.reason
                                }
                            }
                        }
                    },
                    enabled = !rebindBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(if (rebindBusy) "清除中…" else "清除并继续绑定") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRebindAction = null }, enabled = !rebindBusy) {
                    Text("取消")
                }
            }
        )
    }

    // [TASK-REBIND-GATE] 换绑被拦截：设备仍绑定/无法确认绑定状态
    if (rebindBlockedMessage != null) {
        AlertDialog(
            onDismissRequest = { rebindBlockedMessage = null },
            title = { Text("无法重新绑定") },
            text = { Text(rebindBlockedMessage!!) },
            confirmButton = {
                TextButton(onClick = { rebindBlockedMessage = null }) { Text("知道了") }
            }
        )
    }

    // P2P-FIX-B: 手动连接 / 发现设备对话框
    if (showPairingDialog) {
        AlertDialog(
            onDismissRequest = { showPairingDialog = false },
            title = {
                Text("连接家长端", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    // 已发现的设备
                    if (discoveredParents.isNotEmpty()) {
                        Text(
                            "已发现的设备：",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        discoveredParents.forEach { parent ->
                            OutlinedButton(
                                onClick = {
                                    // [TASK-MILESTONE-V3] 需求 3：旧残留确认后再连接
                                    requestPairing { pairingManager.connectToParent(parent, pairingCode) }
                                    showPairingDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text(
                                        "${parent.serviceName}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "${parent.host}:${parent.port} (${parent.discoveryMethod})",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 手动输入
                    Text(
                        "手动输入 IP 地址：",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text("IP 地址") },
                        placeholder = { Text("例如 192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it },
                        label = { Text("端口") },
                        placeholder = { Text("9527") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it },
                        label = { Text("配对码（6 位数字，家长端生成时填写）") },
                        placeholder = { Text("留空则等待家长端生成配对码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val port = manualPort.toIntOrNull() ?: 9527
                        if (manualHost.isNotBlank()) {
                            val parent = pairingManager.addManualParent(manualHost, port)
                            // [TASK-MILESTONE-V3] 需求 3：旧残留确认后再连接
                            requestPairing { pairingManager.connectToParent(parent, pairingCode) }
                        }
                        showPairingDialog = false
                    }
                ) {
                    Text("连接")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairingDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ==================== 子组件 ====================

/**
 * P2P 连接状态栏
 * 绿色已连接 / 黄色连接中 / 灰色未连接
 */
@Composable
fun ConnectionStatusBar(state: P2PConnectionState) {
    val (text, color) = when (state) {
        P2PConnectionState.CONNECTED -> "● 已连接到家长端" to Color(0xFF4CAF50)
        P2PConnectionState.CONNECTING,
        P2PConnectionState.HANDSHAKING -> "◉ 连接中..." to Color(0xFFFF9800)
        P2PConnectionState.RECONNECTING -> "◉ 重连中..." to Color(0xFFFF9800)
        P2PConnectionState.DISCONNECTED -> "○ 未连接" to Color(0xFF9E9E9E)
    }
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/**
 * 剩余时长卡片
 *
 * [TASK-HARDENING-V1.1.1] Bug2：秒级倒计时 HH:MM:SS。
 * - countdown.healthy=false（采集失效/服务未运行）→ 如实显示「守护失效」，
 *   禁止用旧数据假倒计时（平台边界：权限被关后无数据可采）
 * - 正常 → 剩余 HH:MM:SS（含最近采集以来的交互增量）+ 进度条
 * - 归零由采集器 lockIfCountdownExpired 双保险立即锁定，此处同步显示已超时
 */
@Composable
fun RemainingTimeCard(
    usedMinutes: Int,
    limitMinutes: Int,
    usagePercent: Float,
    isNearLimit: Boolean,
    isTimeoutActive: Boolean,
    stopMode: String,
    resetOffsetMinutes: Int = 0,
    countdown: CountdownSnapshot = CountdownSnapshot.EMPTY
) {
    // [TASK-HARDENING-V1.1.1] Bug2-A：守护失效（采集中断/权限被关/服务未运行）
    val guardDown = !countdown.healthy
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (guardDown)
                Color(0xFF546E7A)  // 蓝灰：守护失效（诚实状态，非错误红）
            else if (isTimeoutActive)
                Color(0xFFE53935)  // 超时红色
            else if (isNearLimit)
                Color(0xFFFF9800)  // 警告橙色
            else
                MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态标题
            val titleText = when {
                guardDown -> "🛡️ 守护失效"
                isTimeoutActive && stopMode == "full" -> "🔒 设备已停用"
                isTimeoutActive && stopMode == "partial" -> "⚠️ 娱乐应用已停用"
                isNearLimit -> "⏰ 即将超时"
                else -> "🥬 今日使用时长"
            }
            val brightBg = guardDown || isTimeoutActive || isNearLimit
            Text(
                text = titleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (brightBg) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 剩余时长数字：失效/超时/未设置限额如实展示，其余 HH:MM:SS 秒级倒计时
            val bigText = when {
                guardDown -> "守护失效"
                isTimeoutActive -> "00:00:00"
                countdown.limitMillis <= 0 -> "未设置限额"
                else -> formatHms(countdown.remainingMillis)
            }
            Text(
                text = bigText,
                fontSize = if (guardDown || countdown.limitMillis <= 0) 34.sp else 56.sp,
                fontWeight = FontWeight.Bold,
                color = if (brightBg) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = when {
                    guardDown -> "时长采集已中断"
                    isTimeoutActive -> "已超时"
                    countdown.limitMillis <= 0 -> "家长端尚未设置每日限额"
                    else -> "剩余时长"
                },
                fontSize = 14.sp,
                color = if (brightBg)
                    Color.White.copy(alpha = 0.8f)
                else
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            // [TASK-HARDENING-V1.1.1] Bug2-A：失效原因与恢复引导（如实说明，不假装还在守护）
            if (guardDown) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "使用情况访问权限可能被关闭或采集服务中断。\n" +
                        "请到「设置 → 权限管理」检查并重新授权；恢复后倒计时自动继续。",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度条
            LinearProgressIndicator(
                progress = when {
                    guardDown -> 0f
                    isTimeoutActive -> 1f
                    countdown.limitMillis > 0 -> usagePercent.coerceIn(0f, 1f)
                    else -> 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    guardDown -> Color.White.copy(alpha = 0.6f)
                    isTimeoutActive -> Color.White
                    isNearLimit -> Color.White
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = if (brightBg)
                    Color.White.copy(alpha = 0.3f)
                else
                    MaterialTheme.colorScheme.primaryContainer,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 限额信息（失效时不虚构已用/剩余数字）
            Text(
                text = if (guardDown)
                    "采集恢复后自动继续计时"
                else
                    "今日限额 $limitMinutes 分钟 · 已用 $usedMinutes 分钟",
                fontSize = 12.sp,
                color = if (brightBg)
                    Color.White.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )

            // [TASK-PRELAUNCH-P4] 已重置提示（家长端重置过当日限额）
            if (!guardDown && resetOffsetMinutes > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "今日限额已重置（重置前 $resetOffsetMinutes 分钟不计入）",
                    fontSize = 11.sp,
                    color = if (brightBg)
                        Color.White.copy(alpha = 0.6f)
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * 超时停用横幅
 * 显示停用状态与能力边界提醒
 */
@Composable
fun TimeoutBanner(stopMode: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3E0)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFE65100),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = if (stopMode == "full") "整机停用模式" else "部分应用停用模式",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color(0xFFE65100)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "学习类应用可继续使用。如需恢复，请联系家长。紧急电话始终可用。",
                    fontSize = 12.sp,
                    color = Color(0xFF795548)
                )
            }
        }
    }
}

/**
 * 公告卡片
 * 显示家长推送的公告信息
 * [TASK-PRELAUNCH-P3] 紧急公告（priority>=2）带"紧急"红标与已确认/未确认状态，确认后仍保留记录
 */
@Composable
fun AnnouncementCard(announcement: Announcement) {
    val priorityColor = when (announcement.priority) {
        2 -> Color(0xFFE53935)  // 紧急红
        1 -> Color(0xFFFF9800)  // 重要橙
        else -> Color(0xFF2196F3) // 普通蓝
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 优先级指示器
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(priorityColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // [TASK-PRELAUNCH-P3] 紧急标识
                if (announcement.priority >= 2) {
                    Surface(
                        color = Color(0xFFE53935),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "紧急",
                            fontSize = 11.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = announcement.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = announcement.time,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = announcement.content,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3
            )
            // [TASK-PRELAUNCH-P3] 紧急公告回执状态（已确认后仍保留记录）
            if (announcement.priority >= 2) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (announcement.acknowledged) "✅ 已确认" else "⚠️ 未确认",
                        fontSize = 12.sp,
                        fontWeight = if (announcement.acknowledged) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (announcement.acknowledged)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else Color(0xFFE53935)
                    )
                }
            }
        }
    }
}

/**
 * 快捷操作按钮
 */
@Composable
fun QuickActionButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // [TASK-MILESTONE-V3] 需求 15 走查：文字标签已有语义，图标描述置空避免双重播报
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

// ==================== 工具函数 ====================

/** 格式化分钟数为 mm:ss */
private fun formatMinutes(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    return "%02d:%02d".format(m, 0)
}

/**
 * [TASK-OPT-12-P2] 将公告标记为已读（弹窗关闭/确认后调用）
 */
private fun markAnnouncementRead(context: android.content.Context, announcementId: String) {
    try {
        val passphrase = com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(context)
        com.xiaopacai.child.data.database.AnnouncementDao(
            com.xiaopacai.child.XiaopacaiApp.instance.database
        ).markAsRead(announcementId, passphrase)
    } catch (_: Exception) {
        // 标记已读失败不影响主流程
    }
}
