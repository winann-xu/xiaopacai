package com.xiaopacai.child.ui.parent

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.data.database.ParentDao
import com.xiaopacai.child.p2p.ChildDeviceInfo
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.ui.components.SystemGateDialog
import com.xiaopacai.child.ui.components.AboutDialog
import com.xiaopacai.child.BuildConfig
import com.xiaopacai.child.ui.scan.QrScannerActivity
import com.xiaopacai.child.util.ParentCloudSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * [TASK-ROLE-P2] 家长端主页 — 五大功能板块（完整实现）
 *
 * 底部导航标签：设备 | 策略 | 公告 | 报告 | 设置
 * 所有数据从 ParentDao（SQLCipher）读取，P2P 服务状态实时联动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    // [TASK-ACCOUNT-V1] 切回儿童端前已通过 SystemGateDialog 云端验证（无密码参数）
    onSwitchToChild: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    // 角色切换对话框
    var showSwitchDialog by remember { mutableStateOf(false) }

    // P2P 状态
    var isServiceRunning by remember { mutableStateOf(ParentP2PListenerService.isRunning) }
    var connectedDevices by remember { mutableStateOf(emptyList<ChildDeviceInfo>()) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var showPairingCode by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf("未初始化") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // [REQ] 本机 IP 列表：生成配对码时采集，展示在配对码旁边，方便家长告知儿童端手动连接
    var localIps by remember { mutableStateOf(emptyList<String>()) }
    // [REQ] 扫码识别到的儿童设备信息（对话框展示）
    var scanChildInfo by remember { mutableStateOf<String?>(null) }
    val childScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
        if (text.isNullOrBlank()) return@rememberLauncherForActivityResult
        try {
            val obj = JSONObject(text)
            if (obj.optString("type") == "xiaopacai_child") {
                val name = obj.optString("deviceName", "未知设备")
                val id = obj.optString("deviceId", "")
                val code = ParentP2PListenerService.instance?.generatePairingCode() ?: "------"
                scanChildInfo = "已识别儿童设备：$name\n设备 ID：$id\n\n配对码：$code\n请在儿童端使用该配对码连接家长端（IP + 配对码）。"
            } else {
                scanChildInfo = "二维码内容无法识别为儿童设备"
            }
        } catch (e: Exception) {
            scanChildInfo = "二维码解析失败：${e.message}"
        }
    }

    // [FIX] 必须通过 launcher 启动，否则扫描结果回调不会触发
    fun launchChildScan() {
        try {
            childScanLauncher.launch(android.content.Intent(context, QrScannerActivity::class.java))
        } catch (e: Exception) {
            scanChildInfo = "无法打开相机：${e.message}"
        }
    }

    // 扫码识别儿童设备结果对话框
    scanChildInfo?.let { msg ->
        AlertDialog(
            onDismissRequest = { scanChildInfo = null },
            title = { Text("扫码配对") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { scanChildInfo = null }) { Text("知道了") }
            }
        )
    }


    // 定时刷新 P2P 状态
    LaunchedEffect(Unit) {
        while (true) {
            isServiceRunning = ParentP2PListenerService.isRunning
            if (isServiceRunning) {
                connectedDevices = ParentP2PListenerService.instance?.getConnectedDevices() ?: emptyList()
            } else {
                connectedDevices = emptyList()
            }
            delay(5000L)
        }
    }

    // 获取本机局域网 IP
    fun getLocalIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (!iface.isLoopback && iface.isUp) {
                    iface.inetAddresses.toList().forEach { addr ->
                        val host = addr.hostAddress
                        // [TASK-MILESTONE-V3] 需求 15 走查：条件优先级加括号——
                        // 此前 10./172. 分支未排除含冒号地址（IPv6 会漏入）
                        if (host != null && !host.contains(':') &&
                            (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))
                        ) {
                            ips.add(host)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    val tabs = listOf("设备", "策略", "公告", "报告", "设置")
    val tabIcons = listOf(
        Icons.Filled.Devices, Icons.Filled.Tune, Icons.Filled.Campaign,
        Icons.Filled.Assessment, Icons.Filled.Settings
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小趴菜家长端") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { showSwitchDialog = true }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "切换角色")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.Logout, contentDescription = "退出登录")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        icon = { Icon(tabIcons[index], contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            // P2P 状态条
            P2pStatusBar(
                isRunning = isServiceRunning,
                deviceCount = connectedDevices.size,
                pairingCode = pairingCode,
                showPairingCode = showPairingCode,
                fingerprint = fingerprint,
                qrBitmap = qrBitmap,
                localIps = localIps,
                onScanPair = { launchChildScan() },
                onStartStop = {
                    if (isServiceRunning) {
                        ParentP2PListenerService.stop(context)
                        isServiceRunning = false
                        connectedDevices = emptyList()
                        pairingCode = null
                        qrBitmap = null
                    } else {
                        ParentP2PListenerService.start(context)
                        isServiceRunning = true
                        fingerprint = ParentP2PListenerService.instance?.getCertificateFingerprint() ?: "未知"
                    }
                },
                onGeneratePairingCode = {
                    val code = ParentP2PListenerService.instance?.generatePairingCode() ?: return@P2pStatusBar
                    pairingCode = code
                    showPairingCode = true
                    localIps = getLocalIps()
                    val fp = ParentP2PListenerService.instance?.getCertificateFingerprint() ?: ""
                    fingerprint = fp
                    // [TASK-OPT-12-P3] 生成二维码供儿童端扫码配对
                    qrBitmap = QrCodeGenerator.generatePairingQrCode(
                        deviceId = "parent-${android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).take(8)}",
                        port = 9527,
                        fingerprint = fp,
                        pairingCode = code,
                        hostIps = localIps
                    )
                }
            )

            // [TASK-MILESTONE-V3] 需求 8：删除家长端 ADB 快速指南卡片（普通用户界面不再出现调试命令）

            // [DEBUG] 模拟器无真实相机，调试构建提供扫码结果注入入口
            if (BuildConfig.DEBUG) {
                TextButton(
                    onClick = {
                        val testQr = JSONObject().apply {
                            put("type", "xiaopacai_child")
                            put("deviceId", "child-debug-emulator")
                            put("deviceName", "测试儿童设备")
                            put("timestamp", System.currentTimeMillis() / 1000)
                        }.toString()
                        childScanLauncher.launch(
                            android.content.Intent(context, QrScannerActivity::class.java)
                                .putExtra(QrScannerActivity.EXTRA_TEST_RESULT, testQr)
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("调试：模拟儿童端二维码", fontSize = 12.sp) }
            }

            // 标签内容
            when (selectedTab) {
                0 -> DeviceTab(devices = connectedDevices, isServiceRunning = isServiceRunning)
                1 -> PolicyTab()
                2 -> AnnouncementTab()
                3 -> ReportTab()
                4 -> SettingsTab(onLogout = onLogout)
            }
        }
    }

    // [TASK-ACCOUNT-V1] 角色切换对话框：统一云端验证门禁
    if (showSwitchDialog) {
        SystemGateDialog(
            title = "切换到儿童端",
            description = "请输入家长账号邮箱与登录密码以切换角色。",
            confirmText = "验证并切换",
            onDismiss = { showSwitchDialog = false },
            onVerified = {
                showSwitchDialog = false
                onSwitchToChild()
            }
        )
    }
}

// ==================== P2P 状态条 ====================

@Composable
private fun P2pStatusBar(
    isRunning: Boolean, deviceCount: Int, pairingCode: String?,
    showPairingCode: Boolean, fingerprint: String,
    qrBitmap: Bitmap?,
    localIps: List<String>,
    onScanPair: () -> Unit,
    onStartStop: () -> Unit, onGeneratePairingCode: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("P2P 监听服务", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (isRunning) "端口 9527 | 已连接 $deviceCount 台设备" else "已停止",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isRunning) {
                        FilledTonalButton(onClick = onScanPair,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("扫码配对", fontSize = 12.sp)
                        }
                        FilledTonalButton(onClick = onGeneratePairingCode,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("配对码", fontSize = 12.sp)
                        }
                    }
                    Button(onClick = onStartStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        ), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isRunning) "停止" else "启动", fontSize = 12.sp)
                    }
                }
            }
            if (showPairingCode && pairingCode != null && isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text("配对码:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(pairingCode, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, letterSpacing = 4.sp)
                    // [REQ] 本机 IP 显示在配对码边上，家长可直观告知儿童端手动连接地址
                    if (localIps.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            "本机 IP: ${localIps.joinToString(" / ")}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                // [TASK-OPT-12-P3] 二维码展示（儿童端扫码配对）
                if (qrBitmap != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "配对二维码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .padding(horizontal = 32.dp)
                    )
                }
                Text("有效期 5 分钟 | 指纹: ${fingerprint.take(16)}…", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

// ==================== 1. 设备管理 Tab ====================

@Composable
private fun DeviceTab(devices: List<ChildDeviceInfo>, isServiceRunning: Boolean) {
    val context = LocalContext.current
    var allDevices by remember { mutableStateOf(devices) }
    var showUnbindConfirm by remember { mutableStateOf<ChildDeviceInfo?>(null) }

    // 合并 DB 和内存数据
    LaunchedEffect(devices) {
        val dbDevices = ParentDao.getDevices(context)
        val merged = dbDevices.toMutableList()
        for (live in devices) {
            if (merged.none { it.deviceId == live.deviceId }) merged.add(live)
        }
        allDevices = merged
    }

    if (!isServiceRunning) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.PowerOff, contentDescription = null, modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))
                Text("P2P 监听未启动", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("启动服务后可管理已连接的儿童设备", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    } else if (allDevices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))
                Text("等待设备连接…", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("在儿童设备上输入上方配对码即可连接", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("已连接设备 (${allDevices.size})", fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 4.dp))
            }
            items(allDevices, key = { it.deviceId }) { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // [TASK-MILESTONE-V3] 需求 15 走查：状态灯补读屏语义
                                Icon(Icons.Filled.Circle, contentDescription = "在线", modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    // [TASK-MILESTONE-V3] 需求 15 走查：长设备名单行截断
                                    Text(device.deviceName, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("ID: ${device.deviceId.take(16)}…", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = { showUnbindConfirm = device },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text("解绑", fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("IP: ${device.ip} | 指纹: ${device.certFingerprint.take(12)}…",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("最后连接: ${formatLastSeen(device.lastSeen)}", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    showUnbindConfirm?.let { device ->
        AlertDialog(
            onDismissRequest = { showUnbindConfirm = null },
            title = { Text("解绑设备") },
            text = { Text("确定要解绑「${device.deviceName}」吗？解绑后需重新配对。") },
            confirmButton = {
                TextButton(onClick = {
                    ParentDao.unbindDevice(context, device.deviceId)
                    // [TASK-MILESTONE-V3] 需求 15 走查：解绑当前连接设备时断开连接，
                    // 避免 5 秒轮询把设备重新加回列表（「解绑成功」后设备依然显示）
                    val p2p = GuardianForegroundService.getP2PConnection()
                    if (p2p.getConnectedFingerprint() == device.certFingerprint) {
                        p2p.disconnect()
                    }
                    showUnbindConfirm = null
                    allDevices = ParentDao.getDevices(context)
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("确认解绑")
                }
            },
            dismissButton = { TextButton(onClick = { showUnbindConfirm = null }) { Text("取消") } }
        )
    }
}

// ==================== 2. 策略配置 Tab ====================

/**
 * [TASK-MILESTONE-V3] 需求 10：策略与 Web 双向同步（服务端为权威）
 * - 在线：按设备拉取 /api/policies，保存 PUT 带 expectedVersion（409 冲突采纳服务端最新版）；
 * - 离线：展示本地快照缓存并标注「离线数据」，保存禁用；
 * - 保存成功后同时写入本地镜像（LAN 直接连接设备握手下发，与服务端推送互补）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PolicyTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var online by remember { mutableStateOf(true) }
    var devices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedIdx by remember { mutableIntStateOf(-1) }
    var policy by remember { mutableStateOf<JSONObject?>(null) }
    var dailyLimit by remember { mutableIntStateOf(120) }
    var sleepStart by remember { mutableStateOf("21:00") }
    var sleepEnd by remember { mutableStateOf("07:00") }
    var stopMode by remember { mutableStateOf("full") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    var syncInfo by remember { mutableStateOf("") }

    fun applyPolicy(p: JSONObject) {
        policy = p
        dailyLimit = p.optInt("dailyLimitMinutes", 120)
        sleepStart = p.optString("bedtimeStart", "").takeIf { it.isNotBlank() } ?: "21:00"
        sleepEnd = p.optString("bedtimeEnd", "").takeIf { it.isNotBlank() } ?: "07:00"
        stopMode = ParentCloudSync.stopModeFromServer(p.optString("timeoutAction", ""))
    }

    fun loadPolicyOnline(serverId: Long) {
        scope.launch {
            when (val r = ParentCloudSync.fetchPolicy(context, serverId)) {
                is ParentCloudSync.Result.Ok -> { applyPolicy(r.data); syncInfo = "已同步最新策略" }
                is ParentCloudSync.Result.Err -> {
                    if (r.offline) {
                        online = false
                        ParentCloudSync.cachedPolicy(context, serverId)?.let { applyPolicy(it) }
                        syncInfo = "离线数据 · 本地缓存"
                    } else syncInfo = r.message
                }
            }
        }
    }

    fun reloadAll() {
        scope.launch {
            loading = true
            when (val r = ParentCloudSync.fetchDevices(context)) {
                is ParentCloudSync.Result.Ok -> {
                    online = true
                    devices = jsonArrayToList(r.data)
                    if (selectedIdx < 0 && devices.isNotEmpty()) selectedIdx = 0
                    val dev = devices.getOrNull(selectedIdx)
                    if (dev != null) loadPolicyOnline(dev.optLong("id"))
                    else { policy = null; syncInfo = "服务器上暂无绑定设备，请先在设备页配对" }
                }
                is ParentCloudSync.Result.Err -> {
                    if (r.offline) {
                        online = false
                        devices = ParentCloudSync.cachedDevices(context)?.let { jsonArrayToList(it) } ?: emptyList()
                        if (selectedIdx < 0 && devices.isNotEmpty()) selectedIdx = 0
                        val dev = devices.getOrNull(selectedIdx)
                        if (dev != null) {
                            ParentCloudSync.cachedPolicy(context, dev.optLong("id"))?.let { applyPolicy(it) }
                            syncInfo = "离线数据 · 本地缓存"
                        } else syncInfo = "离线且无缓存，请联网后查看"
                    } else {
                        online = true
                        syncInfo = r.message
                    }
                }
            }
            loading = false
        }
    }

    fun selectDevice(idx: Int) {
        selectedIdx = idx
        val dev = devices.getOrNull(idx) ?: return
        val serverId = dev.optLong("id", 0)
        if (online) loadPolicyOnline(serverId)
        else ParentCloudSync.cachedPolicy(context, serverId)?.let { applyPolicy(it) }
    }

    /** 本地镜像行（LAN 握手下发格式，与服务端 DTO 解耦；分类限额按需求 9 固定 -1） */
    fun localPolicyRows(): List<Triple<String, String, JSONObject>> = listOf(
        Triple("daily_limit", "每日限额", JSONObject().put("limitMinutes", dailyLimit)),
        Triple("sleep_time", "就寝时段", JSONObject().put("startTime", sleepStart).put("endTime", sleepEnd)),
        // [TASK-MILESTONE-V3] 需求 9：分类限额隐藏期间固定写入 -1（不限），后端保留分类字段能力
        Triple("category_limit", "游戏限额", JSONObject().put("category", "game").put("limitMinutes", -1)),
        Triple("category_limit", "社交限额", JSONObject().put("category", "social").put("limitMinutes", -1)),
        Triple("category_limit", "视频限额", JSONObject().put("category", "video").put("limitMinutes", -1)),
        Triple("stop_mode", "超时处理", JSONObject().put("mode", stopMode))
    )

    fun saveAll() {
        val p = policy ?: run { saveMsg = "策略尚未加载"; return }
        val dev = devices.getOrNull(selectedIdx) ?: run { saveMsg = "请先选择设备"; return }
        if (!online) { saveMsg = "离线状态不可保存，请联网后重试"; return }
        // [TASK-MILESTONE-V3] 需求 15 走查：就寝时间客户端校验（此前 "99:99" 可直接上云）
        val timeRe = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
        if (!timeRe.matches(sleepStart.trim()) || !timeRe.matches(sleepEnd.trim())) {
            saveMsg = "就寝时间格式无效（HH:mm，如 21:00）"
            return
        }
        scope.launch {
            isSaving = true
            saveMsg = null
            // 基于已拉取的服务端 DTO 修改（白名单/黑名单/版本原样保留）
            val updated = JSONObject(p.toString()).apply {
                put("dailyLimitMinutes", dailyLimit)
                put("bedtimeStart", sleepStart)
                put("bedtimeEnd", sleepEnd)
                put("timeoutAction", ParentCloudSync.stopModeToServer(stopMode))
            }
            when (val r = ParentCloudSync.savePolicy(context, dev.optLong("id"), updated)) {
                is ParentCloudSync.PolicySaveResult.Saved -> {
                    applyPolicy(r.policy)
                    ParentDao.replacePoliciesForDevice(context, dev.optString("deviceId", ""), localPolicyRows())
                    saveMsg = "已保存并同步到服务器（版本 v${r.policy.optInt("version")}）" +
                        if (ParentP2PListenerService.isRunning) "，已连接设备将自动同步" else ""
                }
                is ParentCloudSync.PolicySaveResult.Conflict -> {
                    applyPolicy(r.serverPolicy)
                    saveMsg = "策略已被其他端修改，已加载服务端最新版本，请确认后重新保存"
                }
                is ParentCloudSync.PolicySaveResult.Failed -> saveMsg = r.message
            }
            isSaving = false
        }
    }

    LaunchedEffect(Unit) { reloadAll() }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 同步状态行
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(syncInfo, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    if (!online) {
                        // [TASK-MILESTONE-V3] 需求 15 走查：不可点击徽标（AssistChip 空点击易误触）
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("离线数据", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    TextButton(onClick = { reloadAll() }, enabled = !loading) { Text("刷新", fontSize = 12.sp) }
                }
            }
            // 设备选择（服务端账号设备列表）
            if (devices.isNotEmpty()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = devices.getOrNull(selectedIdx)?.optString("name", "") ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("选择设备") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            devices.forEachIndexed { idx, d ->
                                DropdownMenuItem(
                                    text = {
                                        Text("${d.optString("name", "设备 ${d.optLong("id")}")}（${d.optString("deviceId", "").take(8)}…）")
                                    },
                                    onClick = { expanded = false; selectDevice(idx) }
                                )
                            }
                        }
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("策略配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { saveAll() }, enabled = !isSaving && policy != null && online,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("保存")
                        }
                    }
                }
                saveMsg?.let {
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                            containerColor = if (it.startsWith("已保存")) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer)) {
                            Text(it, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                        }
                    }
                }
                // 每日限额
                item { PolicyCard("每日使用限额", Icons.Filled.Timer) {
                    Text("$dailyLimit 分钟/天", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    // [TASK-MILESTONE-V3] 需求 15 走查：离线禁改——表单控件一并禁用，
                    // 防止恢复联网后误提交离线期间改动的值
                    Slider(value = dailyLimit.toFloat(), onValueChange = { dailyLimit = it.toInt() },
                        valueRange = 30f..480f, enabled = online)
                }}
                // 就寝时段
                item { PolicyCard("就寝时段", Icons.Filled.Bedtime) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(sleepStart, { sleepStart = it }, label = { Text("开始") },
                            modifier = Modifier.weight(1f), singleLine = true, enabled = online)
                        Text("—")
                        OutlinedTextField(sleepEnd, { sleepEnd = it }, label = { Text("结束") },
                            modifier = Modifier.weight(1f), singleLine = true, enabled = online)
                    }
                }}
                // [TASK-MILESTONE-V3] 需求 9：分类限额本期隐藏（A8：后端保留，前端不展示，默认 -1 不限）
                // 超时处理
                item { PolicyCard("超时处理方式", Icons.Filled.Block) {
                    listOf(
                        Triple("full","整机停用","非白名单应用全部不可用"),
                        Triple("partial","部分 APP 停用","仅娱乐类被停用，学习类继续可用"),
                        Triple("none","仅提醒","不强制停用")
                    ).forEach { (mode, title, desc) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            RadioButton(selected = stopMode == mode, onClick = { stopMode = mode }, enabled = online)
                            Spacer(Modifier.width(8.dp))
                            Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }}
                item { Spacer(Modifier.height(32.dp)) }
            } else {
                // 空态：服务器无设备（离线无缓存亦归此）
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Devices, null, Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text(if (online) "服务器上暂无绑定设备" else "离线且无本地缓存", fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (online) "请先让儿童端完成配对绑定" else "请联网后刷新查看", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ==================== 3. 公告管理 Tab ====================

/**
 * [TASK-MILESTONE-V3] 需求 10：公告与 Web 双向同步（服务端为权威，B13 账号隔离）
 * - 在线：拉取 /api/announcements 全量覆盖本地镜像；新建/编辑/发布/撤回/删除全部走服务端；
 * - 离线：本地镜像只读并标注「离线数据」，操作按钮禁用；
 * - 发布成功后补充 LAN 直连设备推送（id 与服务端一致，终端按 id 去重）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AnnouncementTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var announcements by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var filterStatus by remember { mutableStateOf("all") }
    var online by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editingAnnouncement by remember { mutableStateOf<JSONObject?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    fun loadLocal(filter: String) {
        val all = mutableListOf<JSONObject>()
        val arr = ParentDao.getAnnouncements(context)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // 仅展示服务端镜像（web- 前缀）；历史本地自建公告由在线同步全量覆盖清除
            if (!o.optString("announcementId").startsWith("web-")) continue
            if (filter == "all" || o.optString("status") == filter) all.add(o)
        }
        announcements = all
    }

    fun syncFromServer() {
        scope.launch {
            syncing = true
            when (val r = ParentCloudSync.fetchAnnouncements(context)) {
                is ParentCloudSync.Result.Ok -> { online = true; errMsg = null }
                is ParentCloudSync.Result.Err -> {
                    if (r.offline) { online = false; errMsg = null }
                    else { online = true; errMsg = r.message }
                }
            }
            loadLocal(filterStatus)
            syncing = false
        }
    }

    fun serverIdOf(o: JSONObject): Long =
        o.optString("announcementId").removePrefix("web-").toLongOrNull() ?: 0L

    fun handleErr(r: ParentCloudSync.Result.Err) {
        if (r.offline) {
            online = false
            loadLocal(filterStatus)
        } else {
            online = true
            errMsg = r.message
        }
    }

    fun doPublish(o: JSONObject) {
        scope.launch {
            when (val r = ParentCloudSync.publishAnnouncement(context, serverIdOf(o))) {
                is ParentCloudSync.Result.Ok -> {
                    errMsg = null
                    // 服务端已推中继；LAN 直连设备补充推送（id 一致，终端去重）
                    forwardAnnouncementToDevices(context,
                        r.data.optLong("id").toString(),
                        r.data.optString("title"),
                        r.data.optString("content"),
                        ParentCloudSync.priorityFromServer(r.data.optString("priority", "normal")))
                    syncFromServer()
                }
                is ParentCloudSync.Result.Err -> handleErr(r)
            }
        }
    }

    fun doRevoke(o: JSONObject) {
        scope.launch {
            when (val r = ParentCloudSync.revokeAnnouncement(context, serverIdOf(o))) {
                is ParentCloudSync.Result.Ok -> { errMsg = null; syncFromServer() }
                is ParentCloudSync.Result.Err -> handleErr(r)
            }
        }
    }

    fun doDelete(id: String) {
        scope.launch {
            when (val r = ParentCloudSync.deleteAnnouncement(context, id.removePrefix("web-").toLongOrNull() ?: 0L)) {
                is ParentCloudSync.Result.Ok -> { errMsg = null; syncFromServer() }
                is ParentCloudSync.Result.Err -> handleErr(r)
            }
        }
    }

    LaunchedEffect(Unit) { syncFromServer() }
    LaunchedEffect(filterStatus) { loadLocal(filterStatus) }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = listOf("all","draft","published","revoked").indexOf(filterStatus),
            modifier = Modifier.fillMaxWidth(), edgePadding = 12.dp) {
            listOf("全部","草稿","已发布","已撤回").forEachIndexed { i, label ->
                Tab(selected = filterStatus == listOf("all","draft","published","revoked")[i],
                    onClick = { filterStatus = listOf("all","draft","published","revoked")[i] },
                    text = { Text(label, fontSize = 12.sp) })
            }
        }

        // 同步状态行
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (syncing) "正在同步…" else if (online) "已与服务器同步" else "离线数据 · 本地缓存",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (!online) {
                // [TASK-MILESTONE-V3] 需求 15 走查：不可点击徽标
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("离线数据", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            TextButton(onClick = { syncFromServer() }, enabled = !syncing) { Text("刷新", fontSize = 12.sp) }
        }
        errMsg?.let {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp)
            }
        }

        if (announcements.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // [TASK-MILESTONE-V3] 需求 15 走查：首屏同步中显示加载指示，避免误判为空公告
                    if (syncing) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在同步公告…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(Icons.Filled.Campaign, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Text(if (online) "暂无公告" else "离线且无本地缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { showEditor = true; editingAnnouncement = null },
                            enabled = online) { Text("+ 新建公告") }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("公告 (${announcements.size})", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { showEditor = true; editingAnnouncement = null },
                            enabled = online) { Text("+ 新建") }
                    }
                }
                items(announcements, key = { it.optString("announcementId") }) { a ->
                    val status = a.optString("status")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                // [TASK-MILESTONE-V3] 需求 15 走查：长标题单行截断 + 状态改不可点击徽标
                                Text(a.optString("title", "无标题"), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(statusLabel(status), fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                            val priority = a.optInt("priority")
                            Text(listOf("普通","重要","紧急")[priority.coerceIn(0,2)], fontSize = 12.sp,
                                color = listOf(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error)[priority.coerceIn(0,2)])
                            Text(a.optString("content","").take(80) + if(a.optString("content","").length>80)"…" else "", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                when(status) {
                                    // 服务端语义：撤回后编辑仍保持 revoked，可再次发布（发布接口不限前置状态）
                                    "draft", "revoked" -> { TextButton(onClick = { doPublish(a) }, enabled = online) { Text("发布", fontSize = 12.sp) } }
                                    "published" -> { TextButton(onClick = { doRevoke(a) }, enabled = online) { Text("撤回", fontSize = 12.sp) } }
                                }
                                if (status in listOf("draft","revoked"))
                                    TextButton(onClick = { editingAnnouncement = a; showEditor = true }, enabled = online) { Text("编辑", fontSize = 12.sp) }
                                TextButton(onClick = { showDeleteConfirm = a.optString("announcementId") }, enabled = online,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                    Text("删除", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 编辑/新建对话框（保存走服务端；离线时不可达，错误展示于状态行）
    if (showEditor) {
        var title by remember { mutableStateOf(editingAnnouncement?.optString("title","") ?: "") }
        var content by remember { mutableStateOf(editingAnnouncement?.optString("content","") ?: "") }
        var priority by remember { mutableIntStateOf(editingAnnouncement?.optInt("priority",0) ?: 0) }
        var titleErr by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if(editingAnnouncement != null) "编辑公告" else "新建公告") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; titleErr = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("标题") },
                        singleLine = true,
                        isError = titleErr
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        label = { Text("正文") },
                        maxLines = 6
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("普通","重要","紧急").forEachIndexed { i, l ->
                            FilterChip(selected = priority==i, onClick = { priority=i }, label = { Text(l, fontSize = 12.sp) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    if(title.isBlank()) { titleErr = true; return@TextButton }
                    val serverId = editingAnnouncement?.let { serverIdOf(it) } ?: 0L
                    scope.launch {
                        saving = true
                        val r = if (serverId > 0)
                            ParentCloudSync.updateAnnouncement(context, serverId, title.trim(), content.trim(), priority)
                        else
                            ParentCloudSync.createAnnouncement(context, title.trim(), content.trim(), priority)
                        when (r) {
                            is ParentCloudSync.Result.Ok -> { errMsg = null; showEditor = false; syncFromServer() }
                            is ParentCloudSync.Result.Err -> handleErr(r)
                        }
                        saving = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("取消") } }
        )
    }

    showDeleteConfirm?.let { id ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除公告") },
            text = { Text("确定永久删除？删除后将同步清除儿童端本地记录。") },
            confirmButton = { TextButton(onClick = { doDelete(id); showDeleteConfirm = null },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") } }
        )
    }
}

/**
 * [TASK-MILESTONE-V3] 需求 10：公告发布后向 LAN 直连设备补充推送（服务端中继之外的通道）。
 * id 使用服务端公告 id（与中继推送一致），紧急公告 requiresAck（儿童端确认回执）。
 */
private fun forwardAnnouncementToDevices(
    context: android.content.Context,
    serverAnnouncementId: String,
    title: String,
    content: String,
    priority: Int
) {
    val service = com.xiaopacai.child.p2p.ParentP2PListenerService.instance ?: return
    for (device in service.getConnectedDevices()) {
        service.sendAnnouncementToDevice(
            deviceId = device.deviceId,
            announcementId = serverAnnouncementId,
            title = title,
            content = content,
            priority = priority,
            requiresAck = priority >= 2
        )
    }
}

private fun statusLabel(s: String) = when(s) { "draft"->"草稿"; "published"->"已发布"; "revoked"->"已撤回"; else->s }

// ==================== 4. 使用报告 Tab ====================

/**
 * [TASK-MILESTONE-V3] 需求 11：报告与 Web 同步（口径完全一致）
 * - 在线：实时拉取 /api/reports（今天=日报 / 7天=周报 / 30天=导出聚合），
 *   总时长为原始累计口径；分类名与占比由服务端计算；另按设备列表合计展示
 *   「今日已用（调整后）」+ 重置偏移（与 Web 设备页口径一致）；
 * - 离线：本地快照缓存并标注「离线数据」，恢复联网刷新即更新。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReportTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableIntStateOf(0) }
    val periods = listOf("今天","7天","30天"); val days = listOf(1,7,30)
    var report by remember { mutableStateOf<JSONObject?>(null) }
    var adjusted by remember { mutableStateOf<JSONObject?>(null) }
    var online by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var errMsg by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            when (val r = ParentCloudSync.fetchReport(context, days[period])) {
                is ParentCloudSync.Result.Ok -> { online = true; errMsg = null; report = r.data }
                is ParentCloudSync.Result.Err -> {
                    if (r.offline) {
                        online = false; errMsg = null
                        report = ParentCloudSync.cachedReport(context, days[period])
                    } else { online = true; errMsg = r.message; report = null }
                }
            }
            // 调整后口径：优先在线设备列表，离线用设备缓存
            adjusted = aggregateAdjusted(ParentCloudSync.cachedDevices(context))
            if (online) {
                when (val rd = ParentCloudSync.fetchDevices(context)) {
                    is ParentCloudSync.Result.Ok -> adjusted = aggregateAdjusted(rd.data)
                    else -> {}  // 保持缓存值
                }
            }
            loading = false
        }
    }

    LaunchedEffect(period) { load() }

    val dailyTotals = jsonArrayToList(report?.optJSONArray("dailyTotals"))
    val categories = jsonArrayToList(report?.optJSONArray("categories"))
    val totalMin = report?.optLong("totalMinutes", 0) ?: 0L

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            periods.forEachIndexed { i, l -> FilterChip(selected = period==i, onClick = { period=i }, label = { Text(l, fontSize = 13.sp) }, modifier = Modifier.weight(1f)) }
        }
        // 同步状态行
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (online) "已与服务器同步" else "离线数据 · 本地缓存",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (!online) {
                // [TASK-MILESTONE-V3] 需求 15 走查：不可点击徽标
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("离线数据", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            TextButton(onClick = { load() }, enabled = !loading) { Text("刷新", fontSize = 12.sp) }
        }
        errMsg?.let {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, modifier = Modifier.padding(10.dp), fontSize = 12.sp)
            }
        }

        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 今日已用（调整后）——与 Web 设备页同口径
                adjusted?.let { adj ->
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("今日已用（调整后）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                Text(formatMin(adj.optLong("todayUsageMinutes", 0)), fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                val limit = adj.optLong("todayLimitMinutes", 0)
                                Text(
                                    if (limit > 0) "今日限额 ${formatMin(limit)} · 剩余 ${formatMin(adj.optLong("todayRemainingMinutes", 0))}"
                                    else "尚未设置设备限额",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                val offset = adj.optLong("lastResetOffsetMinutes", 0)
                                if (offset > 0) {
                                    Text("已重置过当日限额：偏移 $offset 分钟（原始累计 ${formatMin(adj.optLong("rawTodayUsageMinutes", 0))}）",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
                // 总使用时长（原始累计口径，与 Web 报告一致）
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("总使用时长 · ${periods[period]}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMin(totalMin), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("原始累计口径（含重置前用量），与 Web 一致", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
                if (dailyTotals.isNotEmpty()) {
                    item { Text("每日趋势", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    items(dailyTotals.take(30), key = { it.optString("date") }) { d ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(d.optString("date").takeLast(5), fontSize = 14.sp)
                                val max = dailyTotals.maxOfOrNull { it.optLong("totalMinutes") } ?: 1L
                                LinearProgressIndicator(
                                    progress = (d.optLong("totalMinutes").toFloat()/max).coerceIn(0f,1f),
                                    modifier = Modifier.weight(1f).height(8.dp).padding(horizontal = 12.dp)
                                )
                                Text(formatMin(d.optLong("totalMinutes")), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                if (categories.isNotEmpty()) {
                    item { Text("分类占比", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    // [TASK-MILESTONE-V3] 需求 15 走查：重名分类（多条「其他」）键重复崩溃，改用索引键
                    itemsIndexed(categories, key = { index, _ -> index }) { _, c ->
                        val pct = c.optDouble("percent", 0.0)
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(c.optString("name", "其他"), fontSize = 14.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(formatMin(c.optLong("minutes", 0)), fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    LinearProgressIndicator(progress = (pct/100.0).toFloat().coerceIn(0f,1f), modifier = Modifier.width(80.dp).height(8.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("${"%.1f".format(pct)}%", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                if (report == null || (dailyTotals.isEmpty() && categories.isEmpty() && totalMin == 0L)) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Assessment, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                Text(if (online) "暂无数据" else "离线且无缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (online) "等待儿童端上报使用时长" else "请联网后刷新查看", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

/** 设备列表 → 「今日已用（调整后）」合计（与 Web 设备页字段同源） */
private fun aggregateAdjusted(arr: org.json.JSONArray?): JSONObject? {
    if (arr == null || arr.length() == 0) return null
    var used = 0L; var raw = 0L; var limit = 0L; var remaining = 0L; var offset = 0L
    for (i in 0 until arr.length()) {
        val d = arr.getJSONObject(i)
        used += d.optLong("todayUsageMinutes", 0)
        raw += d.optLong("rawTodayUsageMinutes", 0)
        limit += d.optLong("todayLimitMinutes", 0)
        remaining += d.optLong("todayRemainingMinutes", 0)
        offset += d.optLong("lastResetOffsetMinutes", 0)
    }
    return JSONObject().apply {
        put("todayUsageMinutes", used)
        put("rawTodayUsageMinutes", raw)
        put("todayLimitMinutes", limit)
        put("todayRemainingMinutes", remaining)
        put("lastResetOffsetMinutes", offset)
    }
}

private fun formatMin(m: Long): String { val h=m/60; val min=m%60; return if(h>0) "${h}h${min}m" else "${min}m" }

// ==================== 5. 设置 Tab ====================

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsTab(onLogout: () -> Unit) {
    // [TASK-OPT-12-P3] Web 云端中继设置页（ParentSettingsScreen）入口
    var showWebRelay by remember { mutableStateOf(false) }
    // [TASK-MILESTONE-V3] 需求 7：关于对话框（双端统一组件）
    var showAboutDialog by remember { mutableStateOf(false) }
    // [TASK-MILESTONE-V3] 需求 14：运行日志页（查看/复制/清空/上传）
    var showLogScreen by remember { mutableStateOf(false) }

    if (showWebRelay) {
        ParentSettingsScreen(
            onBack = { showWebRelay = false },
            // [TASK-PRELAUNCH-PARENT-RESET] 换账号清理完成 → 返回登录页（新账号绑定状态）
            onAccountReset = {
                showWebRelay = false
                onLogout()
            },
            // [TASK-ACCOUNT-V1] 退出登录（清除本地账号绑定后回到登录页）
            onLogout = {
                showWebRelay = false
                onLogout()
            }
        )
    } else if (showLogScreen) {
        // [TASK-MILESTONE-V3] 需求 14：运行日志页
        ParentLogScreen(onBack = { showLogScreen = false })
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp)) }

        item {
            Card(onClick = { showWebRelay = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cloud, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("账号与云端设置", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("账号信息、Web 中继、数据管理", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("安全信息", fontSize = 14.sp, fontWeight = FontWeight.Medium); Text("SQLCipher AES-256 加密 · 本地存储", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        item {
            // [TASK-MILESTONE-V3] 需求 14：运行日志菜单（查看/复制/清空/上传云端）
            Card(onClick = { showLogScreen = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("日志", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("本机运行日志 · 自动上传 Web（已脱敏）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                }
            }
        }

        item {
            // [TASK-MILESTONE-V3] 需求 7：关于卡点击打开统一关于对话框
            Card(onClick = { showAboutDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("关于", fontSize = 14.sp, fontWeight = FontWeight.Medium); Text("小趴菜 v${BuildConfig.VERSION_NAME} · Apache-2.0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLogout, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Logout, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("退出登录")
            }
        }
    }

    // [TASK-MILESTONE-V3] 需求 7：关于对话框（双端统一组件）
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

private fun formatLastSeen(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when { s < 60 -> "刚刚"; s < 3600 -> "${s/60}分钟前"; s < 86400 -> "${s/3600}小时前"; else -> "${s/86400}天前" }
}

/** JSONArray → List<JSONObject>（服务端数组解析用；异常条目跳过） */
private fun jsonArrayToList(arr: JSONArray?): List<JSONObject> {
    if (arr == null) return emptyList()
    val list = mutableListOf<JSONObject>()
    for (i in 0 until arr.length()) {
        try { list.add(arr.getJSONObject(i)) } catch (_: Exception) {}
    }
    return list
}
