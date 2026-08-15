package com.xiaopacai.child.ui.parent

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.data.database.ParentDao
import com.xiaopacai.child.p2p.ChildDeviceInfo
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.ui.components.SystemGateDialog
import com.xiaopacai.child.BuildConfig
import com.xiaopacai.child.ui.scan.QrScannerActivity
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // [REQ] 首页快捷入口：孩子手机快速授权指南（电脑 ADB）
    var showAdbGuide by remember { mutableStateOf(false) }

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
                        if (host != null && !host.contains(':') && host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
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
        if (showAdbGuide) {
            ParentAdbGuideScreen(onBack = { showAdbGuide = false })
        } else Column(
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

            // [REQ] 首页快捷入口：孩子手机快速授权指南（家长最容易找到）
            Card(
                onClick = { showAdbGuide = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.School, null, Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("孩子手机快速授权指南",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("电脑 ADB 一条命令 · 约 30 秒 · 设置一次永久生效",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f))
                    }
                    Icon(Icons.Filled.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f))
                }
            }

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
                                Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(device.deviceName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
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

@Composable
private fun PolicyTab() {
    val context = LocalContext.current
    var dailyLimit by remember { mutableIntStateOf(120) }
    var sleepStart by remember { mutableStateOf("21:00") }
    var sleepEnd by remember { mutableStateOf("07:00") }
    var gameLimit by remember { mutableIntStateOf(60) }
    var socialLimit by remember { mutableIntStateOf(90) }
    var videoLimit by remember { mutableIntStateOf(120) }
    var stopMode by remember { mutableStateOf("full") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val arr = ParentDao.getPolicies(context)
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val data = p.optJSONObject("policyData") ?: continue
                when (p.optString("policyType")) {
                    "daily_limit" -> dailyLimit = data.optInt("limitMinutes", 120)
                    "sleep_time" -> { sleepStart = data.optString("startTime", "21:00"); sleepEnd = data.optString("endTime", "07:00") }
                    "category_limit" -> when (p.optString("policyName")) {
                        "游戏限额" -> gameLimit = data.optInt("limitMinutes", 60)
                        "社交限额" -> socialLimit = data.optInt("limitMinutes", 90)
                        "视频限额" -> videoLimit = data.optInt("limitMinutes", 120)
                    }
                    "stop_mode" -> stopMode = data.optString("mode", "full")
                }
            }
        } catch (_: Exception) {}
        isLoading = false
    }

    fun saveAll() {
        isSaving = true
        try {
            ParentDao.savePolicy(context, null, "daily_limit", "每日限额", JSONObject().put("limitMinutes", dailyLimit))
            ParentDao.savePolicy(context, null, "sleep_time", "就寝时段", JSONObject().put("startTime", sleepStart).put("endTime", sleepEnd))
            ParentDao.savePolicy(context, null, "category_limit", "游戏限额", JSONObject().put("category", "game").put("limitMinutes", gameLimit))
            ParentDao.savePolicy(context, null, "category_limit", "社交限额", JSONObject().put("category", "social").put("limitMinutes", socialLimit))
            ParentDao.savePolicy(context, null, "category_limit", "视频限额", JSONObject().put("category", "video").put("limitMinutes", videoLimit))
            ParentDao.savePolicy(context, null, "stop_mode", "超时处理", JSONObject().put("mode", stopMode))
            saveMsg = "策略已保存" + if (ParentP2PListenerService.isRunning) "（已连接设备将自动同步）" else ""
        } catch (e: Exception) { saveMsg = "保存失败: ${e.message}" }
        isSaving = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("策略配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = { saveAll() }, enabled = !isSaving,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("保存")
                    }
                }
            }
            saveMsg?.let {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = if (it.startsWith("策略已保存")) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer)) {
                        Text(it, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                    }
                }
            }
            // 每日限额
            item { PolicyCard("每日使用限额", Icons.Filled.Timer) {
                Text("$dailyLimit 分钟/天", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Slider(value = dailyLimit.toFloat(), onValueChange = { dailyLimit = it.toInt() }, valueRange = 30f..480f)
            }}
            // 就寝时段
            item { PolicyCard("就寝时段", Icons.Filled.Bedtime) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(sleepStart, { sleepStart = it }, label = { Text("开始") }, modifier = Modifier.weight(1f), singleLine = true)
                    Text("—")
                    OutlinedTextField(sleepEnd, { sleepEnd = it }, label = { Text("结束") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }}
            // 分类限额
            item { PolicyCard("分类限额", Icons.Filled.Category) {
                CategoryRow("🎮 游戏", gameLimit, 0..300) { gameLimit = it }
                CategoryRow("💬 社交", socialLimit, 0..300) { socialLimit = it }
                CategoryRow("🎬 视频", videoLimit, 0..300) { videoLimit = it }
            }}
            // 超时处理
            item { PolicyCard("超时处理方式", Icons.Filled.Block) {
                listOf(
                    Triple("full","整机停用","非白名单应用全部不可用"),
                    Triple("partial","部分 APP 停用","仅娱乐类被停用，学习类继续可用"),
                    Triple("none","仅提醒","不强制停用")
                ).forEach { (mode, title, desc) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(selected = stopMode == mode, onClick = { stopMode = mode })
                        Spacer(Modifier.width(8.dp))
                        Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }}
            item { Spacer(Modifier.height(32.dp)) }
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

@Composable
private fun CategoryRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp)
            Text("${value}分钟", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

// ==================== 3. 公告管理 Tab ====================

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AnnouncementTab() {
    val context = LocalContext.current
    var announcements by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var filterStatus by remember { mutableStateOf("all") }
    var showEditor by remember { mutableStateOf(false) }
    var editingAnnouncement by remember { mutableStateOf<JSONObject?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filterStatus) {
        val all = mutableListOf<JSONObject>()
        val arr = ParentDao.getAnnouncements(context)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (filterStatus == "all" || o.optString("status") == filterStatus) all.add(o)
        }
        announcements = all
    }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = listOf("all","draft","published","revoked").indexOf(filterStatus),
            modifier = Modifier.fillMaxWidth(), edgePadding = 12.dp) {
            listOf("全部","草稿","已发布","已撤回").forEachIndexed { i, label ->
                Tab(selected = filterStatus == listOf("all","draft","published","revoked")[i],
                    onClick = { filterStatus = listOf("all","draft","published","revoked")[i] },
                    text = { Text(label, fontSize = 12.sp) })
            }
        }

        if (announcements.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Campaign, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("暂无公告", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showEditor = true; editingAnnouncement = null }) { Text("+ 新建公告") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("公告 (${announcements.size})", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { showEditor = true; editingAnnouncement = null }) { Text("+ 新建") }
                    }
                }
                items(announcements, key = { it.optString("announcementId") }) { a ->
                    val status = a.optString("status")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(a.optString("title", "无标题"), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                AssistChip(onClick = {}, label = { Text(statusLabel(status), fontSize = 10.sp) })
                            }
                            val priority = a.optInt("priority")
                            Text(listOf("普通","重要","紧急")[priority.coerceIn(0,2)], fontSize = 12.sp,
                                color = listOf(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error)[priority.coerceIn(0,2)])
                            Text(a.optString("content","").take(80) + if(a.optString("content","").length>80)"…" else "", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                when(status) {
                                    "draft" -> { TextButton(onClick = {
                                        ParentDao.publishAnnouncement(context, a.optString("announcementId"))
                                        pushAnnouncementToDevices(context, a)
                                        refreshAnn(context, filterStatus) { announcements = it }
                                    }) { Text("发布", fontSize = 12.sp) } }
                                    "published" -> { TextButton(onClick = { ParentDao.revokeAnnouncement(context, a.optString("announcementId")); refreshAnn(context, filterStatus) { announcements = it } }) { Text("撤回", fontSize = 12.sp) } }
                                }
                                if (status in listOf("draft","revoked")) TextButton(onClick = { editingAnnouncement = a; showEditor = true }) { Text("编辑", fontSize = 12.sp) }
                                TextButton(onClick = { showDeleteConfirm = a.optString("announcementId") },
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

    // 编辑/新建对话框
    if (showEditor) {
        var title by remember { mutableStateOf(editingAnnouncement?.optString("title","") ?: "") }
        var content by remember { mutableStateOf(editingAnnouncement?.optString("content","") ?: "") }
        var priority by remember { mutableIntStateOf(editingAnnouncement?.optInt("priority",0) ?: 0) }
        var titleErr by remember { mutableStateOf(false) }

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
                TextButton(onClick = {
                    if(title.isBlank()) { titleErr = true; return@TextButton }
                    // [FIX] 撤回后的公告重新编辑保存时回到草稿状态，允许再次发布
                    val oldStatus = editingAnnouncement?.optString("status", "draft") ?: "draft"
                    val newStatus = if (oldStatus == "revoked") "draft" else oldStatus
                    ParentDao.saveAnnouncement(context, editingAnnouncement?.optString("announcementId"), title.trim(), content.trim(), priority,
                        newStatus)
                    showEditor = false
                    refreshAnn(context, filterStatus) { announcements = it }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("取消") } }
        )
    }

    showDeleteConfirm?.let { id ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除公告") },
            text = { Text("确定永久删除？") },
            confirmButton = { TextButton(onClick = { ParentDao.deleteAnnouncement(context, id); showDeleteConfirm = null; refreshAnn(context, filterStatus) { announcements = it } },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") } }
        )
    }
}

private fun refreshAnn(context: android.content.Context, filter: String, cb: (List<JSONObject>) -> Unit) {
    val all = mutableListOf<JSONObject>()
    val arr = ParentDao.getAnnouncements(context)
    for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if(filter=="all" || o.optString("status")==filter) all.add(o) }
    cb(all)
}

/**
 * 公告发布后推送到所有已连接的儿童端设备
 */
private fun pushAnnouncementToDevices(context: android.content.Context, a: JSONObject) {
    val service = com.xiaopacai.child.p2p.ParentP2PListenerService.instance ?: return
    for (device in service.getConnectedDevices()) {
        service.sendAnnouncementToDevice(
            deviceId = device.deviceId,
            announcementId = a.optString("announcementId"),
            title = a.optString("title"),
            content = a.optString("content"),
            priority = a.optInt("priority", 0)
        )
    }
}

private fun statusLabel(s: String) = when(s) { "draft"->"草稿"; "published"->"已发布"; "revoked"->"已撤回"; else->s }

// ==================== 4. 使用报告 Tab ====================

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReportTab() {
    val context = LocalContext.current
    var period by remember { mutableIntStateOf(0) }
    val periods = listOf("今天","7天","30天"); val days = listOf(1,7,30)
    var dailyTotals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var catBreakdown by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var totalMin by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(true) }

    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    val today = sdf.format(java.util.Date())
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, -days[period])
    val from = sdf.format(cal.time)

    LaunchedEffect(period) {
        loading = true
        withContext(Dispatchers.IO) {
            val tl = mutableListOf<JSONObject>()
            val ta = ParentDao.getDailyTotals(context, fromDate = from, toDate = today)
            for (i in 0 until ta.length()) tl.add(ta.getJSONObject(i))
            dailyTotals = tl
            val ca = ParentDao.getCategoryBreakdown(context, fromDate = from)
            val cl = mutableListOf<JSONObject>()
            var sum = 0L
            for (i in 0 until ca.length()) { val o = ca.getJSONObject(i); cl.add(o); sum += o.optLong("totalMinutes") }
            catBreakdown = cl; totalMin = sum
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            periods.forEachIndexed { i, l -> FilterChip(selected = period==i, onClick = { period=i }, label = { Text(l, fontSize = 13.sp) }, modifier = Modifier.weight(1f)) }
        }

        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("总使用时长", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            Text(formatMin(totalMin), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                if (dailyTotals.isNotEmpty()) {
                    item { Text("每日趋势", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    items(dailyTotals.take(10), key = { it.optString("date") }) { d ->
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
                if (catBreakdown.isNotEmpty()) {
                    item { Text("分类占比", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    items(catBreakdown) { c ->
                        val ct = c.optLong("totalMinutes"); val pct = if(totalMin>0) ct*100f/totalMin else 0f
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${catEmoji(c.optString("category"))} ${catName(c.optString("category"))}", fontSize = 14.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(progress = pct/100f, modifier = Modifier.width(80.dp).height(8.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("${"%.1f".format(pct)}%", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                if (dailyTotals.isEmpty() && catBreakdown.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Assessment, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                Text("暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("等待儿童端上报使用时长", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

private fun formatMin(m: Long): String { val h=m/60; val min=m%60; return if(h>0) "${h}h${min}m" else "${min}m" }
private fun catName(c: String) = when(c) { "game"->"游戏"; "social"->"社交"; "video"->"视频"; "study"->"学习"; else->"其他" }
private fun catEmoji(c: String) = when(c) { "game"->"🎮"; "social"->"💬"; "video"->"🎬"; "study"->"📚"; else->"📱" }

// ==================== 5. 设置 Tab ====================

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsTab(onLogout: () -> Unit) {
    // [TASK-OPT-12-P3] Web 云端中继设置页（ParentSettingsScreen）入口
    var showWebRelay by remember { mutableStateOf(false) }

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
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("关于", fontSize = 14.sp, fontWeight = FontWeight.Medium); Text("小趴菜 2.1（双角色版）· Apache-2.0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

}

private fun formatLastSeen(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when { s < 60 -> "刚刚"; s < 3600 -> "${s/60}分钟前"; s < 86400 -> "${s/3600}小时前"; else -> "${s/86400}天前" }
}
