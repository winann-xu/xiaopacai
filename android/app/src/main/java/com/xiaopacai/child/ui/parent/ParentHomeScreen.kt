package com.xiaopacai.child.ui.parent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.p2p.ChildDeviceInfo
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.role.RoleManager
import kotlinx.coroutines.delay

/**
 * [TASK-ROLE-P2] 家长端主页 — 完整功能实现
 *
 * 五大板块：
 * - 设备管理：P2P 监听状态、已连接设备列表、配对码生成
 * - 策略配置：每日限额/就寝时段/分类限额/黑白名单/超时处理骨架
 * - 公告管理：发布/编辑/撤回公告骨架
 * - 使用报告：日报/周报/趋势骨架
 * - 设置：密码修改/端口配置/日志骨架
 *
 * 与 ParentP2PListenerService 状态联动：
 * - 监听服务启动/停止
 * - 实时设备连接数
 * - 配对码生成与展示
 * - 设备在线/离线状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    onSwitchToChild: (String) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current

    // ===== 导航状态 =====
    var selectedTab by remember { mutableIntStateOf(0) }

    // ===== 角色切换对话框 =====
    var showSwitchDialog by remember { mutableStateOf(false) }
    var switchPassword by remember { mutableStateOf("") }
    var switchError by remember { mutableStateOf<String?>(null) }

    // ===== P2P 状态（定时轮询，30 秒间隔）=====
    var isServiceRunning by remember { mutableStateOf(ParentP2PListenerService.isRunning) }
    var connectedDevices by remember { mutableStateOf(emptyList<ChildDeviceInfo>()) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var showPairingCode by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf("未初始化") }

    // 轮询 P2P 服务状态
    LaunchedEffect(Unit) {
        while (true) {
            isServiceRunning = ParentP2PListenerService.isRunning
            try {
                val instance = ParentP2PListenerService.Companion
                if (isServiceRunning) {
                    // 通过反射安全获取实例（避免直接访问 private instance）
                    connectedDevices = try {
                        val f = ParentP2PListenerService::class.java.getDeclaredField("instance")
                        f.isAccessible = true
                        val svc = f.get(null) as? ParentP2PListenerService
                        svc?.getConnectedDevices() ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    connectedDevices = emptyList()
                }
            } catch (_: Exception) {
                connectedDevices = emptyList()
            }
            delay(30_000L)
        }
    }

    // ===== 标签页 =====
    val tabs = listOf("设备", "策略", "公告", "报告", "设置")
    val tabIcons = listOf(
        Icons.Filled.Devices,
        Icons.Filled.Tune,
        Icons.Filled.Campaign,
        Icons.Filled.Assessment,
        Icons.Filled.Settings,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("家长端") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // 角色切换
                    IconButton(onClick = { showSwitchDialog = true }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "切换角色")
                    }
                    // 退出登录
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
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // === P2P 状态条（始终可见） ===
            P2pStatusBar(
                isRunning = isServiceRunning,
                deviceCount = connectedDevices.size,
                pairingCode = pairingCode,
                showPairingCode = showPairingCode,
                fingerprint = fingerprint,
                onStartStop = {
                    if (isServiceRunning) {
                        ParentP2PListenerService.stop(context)
                        isServiceRunning = false
                        connectedDevices = emptyList()
                        pairingCode = null
                    } else {
                        ParentP2PListenerService.start(context)
                        isServiceRunning = true
                        // 获取证书指纹
                        try {
                            val f = ParentP2PListenerService::class.java.getDeclaredField("instance")
                            f.isAccessible = true
                            val svc = f.get(null) as? ParentP2PListenerService
                            fingerprint = svc?.getCertificateFingerprint() ?: "未知"
                        } catch (_: Exception) {
                            fingerprint = "获取失败"
                        }
                    }
                },
                onGeneratePairingCode = {
                    try {
                        val f = ParentP2PListenerService::class.java.getDeclaredField("instance")
                        f.isAccessible = true
                        val svc = f.get(null) as? ParentP2PListenerService
                        pairingCode = svc.generatePairingCode()
                        showPairingCode = true
                    } catch (_: Exception) {
                        pairingCode = "ERROR"
                    }
                }
            )

            // === 内容区 ===
            when (selectedTab) {
                0 -> DeviceManagementTab(devices = connectedDevices, isServiceRunning = isServiceRunning)
                1 -> PolicyConfigTab()
                2 -> AnnouncementsTab()
                3 -> ReportsTab()
                4 -> SettingsTab(onLogout = onLogout)
            }
        }
    }

    // ===== 角色切换对话框 =====
    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = {
                showSwitchDialog = false
                switchPassword = ""
                switchError = null
            },
            title = { Text("切换到儿童端") },
            text = {
                Column {
                    Text(
                        text = "请输入家长密码以切换角色",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = switchPassword,
                        onValueChange = {
                            switchPassword = it
                            switchError = null
                        },
                        label = { Text("家长密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = switchError != null
                    )
                    if (switchError != null) {
                        Text(
                            text = switchError!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (switchPassword.isEmpty()) {
                        switchError = "请输入密码"
                    } else if (RoleManager.verifyParentPassword(context, switchPassword)) {
                        showSwitchDialog = false
                        onSwitchToChild(switchPassword)
                    } else {
                        switchError = "密码错误"
                    }
                }) { Text("确认切换") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSwitchDialog = false
                    switchPassword = ""
                    switchError = null
                }) { Text("取消") }
            }
        )
    }
}

// ============================================================================
// P2P 状态条
// ============================================================================

@Composable
private fun P2pStatusBar(
    isRunning: Boolean,
    deviceCount: Int,
    pairingCode: String?,
    showPairingCode: Boolean,
    fingerprint: String,
    onStartStop: () -> Unit,
    onGeneratePairingCode: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "P2P 监听服务",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isRunning)
                            "端口 9527 | 已连接 $deviceCount 台设备"
                        else
                            "已停止",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 配对码按钮（仅运行时可用）
                    if (isRunning) {
                        FilledTonalButton(
                            onClick = onGeneratePairingCode,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.QrCode,
                                contentDescription = "生成配对码",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("配对码", fontSize = 12.sp)
                        }
                    }

                    // 启动/停止按钮
                    Button(
                        onClick = onStartStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (isRunning) "停止" else "启动",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isRunning) "停止" else "启动",
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 配对码展示区
            if (showPairingCode && pairingCode != null && isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "配对码:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pairingCode,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 4.sp
                    )
                }

                Text(
                    text = "有效期 5 分钟，单次使用 | 指纹: ${fingerprint.take(16)}...",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ============================================================================
// 1. 设备管理 Tab
// ============================================================================

@Composable
private fun DeviceManagementTab(
    devices: List<ChildDeviceInfo>,
    isServiceRunning: Boolean
) {
    if (!isServiceRunning) {
        // 未启动监听
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.PowerOff,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "P2P 监听未启动",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "启动服务后可管理已连接的儿童设备",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    } else if (devices.isEmpty()) {
        // 已启动但无设备
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "等待设备连接...",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "在儿童设备上输入上方配对码即可连接",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    } else {
        // 设备列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "已连接设备 (${devices.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(devices, key = { it.deviceId }) { device ->
                DeviceCard(device)
            }
        }
    }
}

@Composable
private fun DeviceCard(device: ChildDeviceInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 在线指示器
            Icon(
                Icons.Filled.Circle,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "ID: ${device.deviceId.take(16)}...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "IP: ${device.ip} | 证书: ${device.certFingerprint.take(12)}...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 最后在线
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "● 在线",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatLastSeen(device.lastSeen),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatLastSeen(timestamp: Long): String {
    val seconds = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86400} 天前"
    }
}

// ============================================================================
// 2. 策略配置 Tab（P2 骨架 → 可交互占位）
// ============================================================================

@Composable
private fun PolicyConfigTab() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "策略配置",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        item { PolicySettingCard("每日使用限额", "120 分钟", Icons.Filled.Timer, enabled = false) }
        item { PolicySettingCard("就寝时段", "21:00 - 07:00", Icons.Filled.Bedtime, enabled = false) }
        item { PolicySettingCard("游戏限额", "60 分钟/天", Icons.Filled.SportsEsports, enabled = false) }
        item { PolicySettingCard("社交限额", "30 分钟/天", Icons.Filled.Chat, enabled = false) }
        item { PolicySettingCard("视频限额", "90 分钟/天", Icons.Filled.Videocam, enabled = false) }
        item { PolicySettingCard("学习应用", "不限时", Icons.Filled.School, enabled = false) }
        item { PolicySettingCard("应用白名单", "2 个应用", Icons.Filled.Checklist, enabled = false) }
        item { PolicySettingCard("应用黑名单", "1 个应用", Icons.Filled.Block, enabled = false) }
        item { PolicySettingCard("超时处理方式", "完全锁定", Icons.Filled.Lock, enabled = false) }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🔧 策略配置联调准备",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "策略通过 P2P 握手/心跳下发到儿童端。" +
                                "当前展示预置默认值，编辑功能在下一阶段实现。" +
                                "\n\n家长端修改策略后，儿童端下次心跳/连接时自动拉取最新策略。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PolicySettingCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        onClick = { /* P3 实现编辑 */ }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = if (enabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = value,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

// ============================================================================
// 3. 公告管理 Tab（P2 骨架）
// ============================================================================

@Composable
private fun AnnouncementsTab() {
    val sampleAnnouncements = listOf(
        Triple("周末使用提醒", "normal", "记得按时休息，保护眼睛哦。"),
        Triple("学习任务更新", "important", "本周学习计划已由家长端更新，请查看。"),
        Triple("紧急通知", "urgent", "今晚 21:00 前需要完成在线课程签到。"),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "公告管理",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                FilledTonalButton(
                    onClick = { /* P3: 新建公告 */ },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新建", fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        items(sampleAnnouncements.size) { index ->
            val (title, priority, content) = sampleAnnouncements[index]
            AnnouncementCard(title, priority, content)
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🔧 公告管理联调准备",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "公告通过 P2P announcement_push 消息主动推送到已连接设备。" +
                                "\n\n- 支持 normal / important / urgent 三级优先级\n" +
                                "- 支持定向推送（单设备）和广播（所有设备）\n" +
                                "- 已读状态通过 announcement_push 回执\n" +
                                "- API: POST /api/announcements",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnouncementCard(title: String, priority: String, content: String) {
    val priorityColor = when (priority) {
        "urgent" -> MaterialTheme.colorScheme.error
        "important" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val priorityLabel = when (priority) {
        "urgent" -> "紧急"
        "important" -> "重要"
        else -> "普通"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = priorityColor.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = priorityLabel,
                        fontSize = 11.sp,
                        color = priorityColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = content,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "草稿 · 未发布",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ============================================================================
// 4. 使用报告 Tab（P2 骨架）
// ============================================================================

@Composable
private fun ReportsTab() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "使用报告",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // 今日概览
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📊 今日概览", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatTile(label = "使用时长", value = "-- min", icon = "⏱️")
                        StatTile(label = "解锁次数", value = "-- 次", icon = "🔓")
                        StatTile(label = "拦截次数", value = "-- 次", icon = "🛡️")
                    }
                }
            }
        }

        // 应用使用排名
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📱 应用使用排名", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "设备连接后将自动同步使用记录\nP2P usage_report → 入库 → 报表生成",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 周趋势
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📈 七日趋势", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "图表功能将在 P3 阶段通过 ECharts/Vue 实现\n数据来源: daily_summary 表",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 导出说明
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🔧 报告系统联调准备",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "儿童端定期上报 usage_report → 服务端入库 usage_records " +
                                "+ daily_summary 实时汇总。" +
                                "\n\n- 数据表: usage_records (原始), daily_summary (每日聚合)\n" +
                                "- 查询 API: GET /api/reports/daily, GET /api/reports/weekly\n" +
                                "- 导出格式: JSON / CSV / TXT\n" +
                                "- Web 端 ECharts 趋势图（Vue 前端）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, icon: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 24.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// 5. 设置 Tab（P2 骨架 — 可交互的密码修改 + 端口配置占位）
// ============================================================================

@Composable
private fun SettingsTab(onLogout: () -> Unit) {
    val context = LocalContext.current
    var showChangePassword by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "设置",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // 账号安全
        item {
            Card(modifier = Modifier.fillMaxWidth(), onClick = { showChangePassword = true }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("修改家长密码", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "修改后所有设备需重新验证",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // P2P 端口配置
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("P2P 监听端口", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("当前: 9527", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // 数据库
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("数据库管理", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "SQLCipher 加密 · 本地存储",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // 关于
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("关于", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "小趴菜 3.0 · P2 阶段",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 退出登录
        item {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("退出登录")
            }
        }

        // 联调说明
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🔧 设置页联调准备：密码修改通过 RoleManager API，" +
                            "端口配置在 P2P 监听启动时读取 SharedPreferences。" +
                            "\n\n双模拟器端到端联调由 Codex@50.20 执行（Android SDK 环境）。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    // 修改密码对话框
    if (showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            context = context
        )
    }
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    context: android.content.Context
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改家长密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (success) {
                    Text(
                        "密码已修改成功！下次登录请使用新密码。",
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it; error = null },
                        label = { Text("旧密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; error = null },
                        label = { Text("新密码（6-16位）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; error = null },
                        label = { Text("确认新密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (success) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = {
                    when {
                        oldPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty() ->
                            error = "请填写所有字段"
                        newPassword != confirmPassword ->
                            error = "两次输入的新密码不一致"
                        !RoleManager.isValidPasswordFormat(newPassword) ->
                            error = "密码格式不符合要求（6-16位数字或字母）"
                        !RoleManager.verifyParentPassword(context, oldPassword) ->
                            error = "旧密码错误"
                        else -> {
                            val ok = RoleManager.changeParentPassword(context, oldPassword, newPassword)
                            if (ok) {
                                success = true
                            } else {
                                error = "密码修改失败"
                            }
                        }
                    }
                }) { Text("确认修改") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
