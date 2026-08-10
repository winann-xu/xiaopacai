package com.xiaopacai.child.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.p2p.DiscoveredParent
import com.xiaopacai.child.p2p.P2PConnectionState
import com.xiaopacai.child.p2p.PairingManager
import com.xiaopacai.child.p2p.PairingState
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.service.UsageStatsCollector

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
 * 模拟公告数据（后续从 P2P 同步获取）
 */
data class Announcement(
    val id: String,
    val title: String,
    val content: String,
    val time: String,
    val priority: Int  // 0=普通 1=重要 2=紧急
)

@Composable
fun GuardianHomeContent(
    onOpenSettings: () -> Unit = {},
    onOpenPermissionGuide: () -> Unit = {}
) {
    val context = LocalContext.current

    // === 从时长采集器获取实时数据 ===
    val collector = GuardianForegroundService.getCollector()

    // 状态（从采集器初始化，Compose 重组时自动刷新）
    var todayUsedMinutes by remember { mutableStateOf(collector?.todayTotalMinutes?.toInt() ?: 0) }
    var dailyLimitMinutes by remember { mutableStateOf(collector?.todayLimitMinutes?.toInt() ?: 120) }
    var stopMode by remember { mutableStateOf(collector?.stopMode ?: "none") }
    var isTimeoutActive by remember { mutableStateOf(collector?.isTimeoutActive ?: false) }

    // [FIX-LEGACY-c] 使用共享 P2P 连接服务的实时状态，不再硬编码 DISCONNECTED
    val sharedConnection = remember { GuardianForegroundService.getP2PConnection() }
    val connectionState by sharedConnection.connectionState.collectAsState()

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

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            pairingManager.destroy()
        }
    }

    // 定时刷新数据（每 30 秒从采集器同步最新值）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            val c = GuardianForegroundService.getCollector()
            if (c != null) {
                todayUsedMinutes = c.todayTotalMinutes.toInt()
                dailyLimitMinutes = c.todayLimitMinutes.toInt().coerceAtLeast(1)
                stopMode = c.stopMode
                isTimeoutActive = c.isTimeoutActive
            }
        }
    }

    // 计算剩余时长
    val remainingMinutes = maxOf(0, dailyLimitMinutes - todayUsedMinutes)
    val usagePercent = if (dailyLimitMinutes > 0)
        (todayUsedMinutes.toFloat() / dailyLimitMinutes) else 0f
    val isNearLimit = remainingMinutes <= 15 && remainingMinutes > 0

    // 从数据库加载公告（30 秒刷新一次）
    var announcements by remember { mutableStateOf(emptyList<Announcement>()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            try {
                val passphrase = com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(context)
                val db = com.xiaopacai.child.XiaopacaiApp.instance.database.getReadable(passphrase)
                val cursor = db.rawQuery(
                    """SELECT announcement_id, title, content, priority, created_at
                       FROM announcements WHERE is_read = 0
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
                                .format(java.util.Date(it.getLong(4) * 1000))
                        ))
                    }
                }
                db.close()
                announcements = list
            } catch (_: Exception) {
                // 数据库未就绪时使用空列表
            }
        }
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
                remainingMinutes = remainingMinutes,
                usagePercent = usagePercent,
                isNearLimit = isNearLimit,
                isTimeoutActive = isTimeoutActive,
                stopMode = stopMode
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
        items(announcements) { announcement ->
            AnnouncementCard(announcement)
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
                            connectionState == P2PConnectionState.RECONNECTING -> "◉ 重连中..."
                            connectionState == P2PConnectionState.CONNECTING ||
                                connectionState == P2PConnectionState.HANDSHAKING -> "正在连接..."
                            pairingState == PairingState.SCANNING -> "正在扫描局域网..."
                            pairingState == PairingState.FOUND_PARENT -> "已发现 ${discoveredParents.size} 台家长端"
                            pairingState == PairingState.PAIRING -> "正在配对..."
                            pairingState == PairingState.ERROR -> "❌ 配对失败"
                            else -> "点击下方按钮开始扫描局域网中的家长端"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (pairingState == PairingState.SCANNING) {
                                    pairingManager.stopScanning()
                                } else {
                                    pairingManager.startScanning()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (pairingState == PairingState.SCANNING) "停止扫描" else "扫描家长端",
                                fontSize = 13.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { showPairingDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("手动连接", fontSize = 13.sp)
                        }
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
                    onClick = onOpenSettings
                )
                // 权限管理按钮
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Security,
                    label = "权限管理",
                    onClick = onOpenPermissionGuide
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

        // 底部间距
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    // BUG-0810-10: 关于对话框
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = com.xiaopacai.child.R.drawable.ic_logo),
                        contentDescription = "小趴菜 Logo",
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("关于小趴菜", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "小趴菜儿童守护 v1.0.0\n\n" +
                    "开源家长监控软件，帮助家长管理儿童设备使用时长，" +
                    "拦截不适宜内容，守护儿童健康成长。\n\n" +
                    "© 2024 小趴菜开源社区\n" +
                    "github.com/xiaopacai"
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("知道了")
                }
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
                                    pairingManager.connectToParent(parent, pairingCode.ifEmpty { "000000" })
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
                        label = { Text("配对码（6 位数字）") },
                        placeholder = { Text("000000") },
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
                            pairingManager.connectToParent(parent, pairingCode.ifEmpty { "000000" })
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
 * 显示今日使用进度、剩余分钟数、限额信息
 */
@Composable
fun RemainingTimeCard(
    usedMinutes: Int,
    limitMinutes: Int,
    remainingMinutes: Int,
    usagePercent: Float,
    isNearLimit: Boolean,
    isTimeoutActive: Boolean,
    stopMode: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isTimeoutActive)
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
            // 主 Logo（品牌标识）
            Image(
                painter = painterResource(id = com.xiaopacai.child.R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 状态标题
            val titleText = when {
                isTimeoutActive && stopMode == "full" -> "🔒 设备已停用"
                isTimeoutActive && stopMode == "partial" -> "⚠️ 娱乐应用已停用"
                isNearLimit -> "⏰ 即将超时"
                else -> "今日使用时长"
            }
            Text(
                text = titleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isTimeoutActive) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 剩余时长数字
            Text(
                text = if (isTimeoutActive) "00:00" else formatMinutes(remainingMinutes),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = if (isTimeoutActive) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = if (isTimeoutActive) "已超时" else "剩余分钟",
                fontSize = 14.sp,
                color = if (isTimeoutActive)
                    Color.White.copy(alpha = 0.8f)
                else
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 进度条
            LinearProgressIndicator(
                progress = if (isTimeoutActive) 1f else usagePercent.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    isTimeoutActive -> Color.White
                    isNearLimit -> Color.White
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = if (isTimeoutActive || isNearLimit)
                    Color.White.copy(alpha = 0.3f)
                else
                    MaterialTheme.colorScheme.primaryContainer,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 限额信息
            Text(
                text = "今日限额 $limitMinutes 分钟 · 已用 $usedMinutes 分钟",
                fontSize = 12.sp,
                color = if (isTimeoutActive)
                    Color.White.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
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
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
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
