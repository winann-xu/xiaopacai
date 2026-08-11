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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.data.database.ParentDao
import com.xiaopacai.child.p2p.ChildDeviceInfo
import com.xiaopacai.child.p2p.ParentP2PListenerService
import kotlinx.coroutines.delay

/**
 * [TASK-ROLE-P2] 家长端设备管理页
 *
 * 功能：
 * - 显示已配对儿童端设备列表（在线/离线、证书指纹、最后连接时间）
 * - 生成配对码/显示二维码，供儿童端连接
 * - 解绑设备
 * - 刷新状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDeviceScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<ChildDeviceInfo>>(emptyList()) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var certFingerprint by remember { mutableStateOf("") }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showUnbindConfirm by remember { mutableStateOf<ChildDeviceInfo?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // 初始加载
    LaunchedEffect(Unit) {
        refreshData(context) { devices = it; certFingerprint = ParentP2PListenerService.instance?.getCertificateFingerprint() ?: "" }
    }

    // 定时刷新（5 秒）
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            refreshData(context) { devices = it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 刷新按钮
                    IconButton(onClick = {
                        isRefreshing = true
                        refreshData(context) { devices = it }
                        isRefreshing = false
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    // 添加设备（生成配对码）
                    IconButton(onClick = { showPairingDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加设备")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (devices.isEmpty()) {
            // 空态
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Devices,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无已连接设备",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击右上角 + 生成配对码，\n让儿童端输入本机 IP 和配对码连接",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { showPairingDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始配对")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 在线设备数量
                item {
                    val onlineCount = devices.count { isDeviceOnline(it) }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已连接 $onlineCount / ${devices.size} 台设备",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            TextButton(onClick = { showPairingDialog = true }) {
                                Text("+ 添加设备")
                            }
                        }
                    }
                }

                // 设备列表
                items(devices, key = { it.deviceId }) { device ->
                    DeviceCard(
                        device = device,
                        isOnline = isDeviceOnline(device),
                        onUnbind = { showUnbindConfirm = device }
                    )
                }
            }
        }
    }

    // 配对对话框
    if (showPairingDialog) {
        val code = remember {
            val service = ParentP2PListenerService.instance
            service?.generatePairingCode() ?: "------"
        }
        PairingDialog(
            pairingCode = code,
            certFingerprint = certFingerprint,
            onDismiss = {
                showPairingDialog = false
                pairingCode = null
            }
        )
    }

    // 解绑确认对话框
    showUnbindConfirm?.let { device ->
        AlertDialog(
            onDismissRequest = { showUnbindConfirm = null },
            title = { Text("解绑设备") },
            text = {
                Text("确定要解绑「${device.deviceName}」吗？\n\n解绑后该设备将无法连接，需重新配对。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ParentDao.unbindDevice(context, device.deviceId)
                        showUnbindConfirm = null
                        refreshData(context) { devices = it }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确认解绑")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnbindConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 设备卡片
 */
@Composable
private fun DeviceCard(
    device: ChildDeviceInfo,
    isOnline: Boolean,
    onUnbind: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 在线状态指示灯
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = if (isOnline) "在线" else "离线",
                        modifier = Modifier.size(12.dp),
                        tint = if (isOnline) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = device.deviceName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isOnline) "● 在线" else "○ 离线",
                            fontSize = 12.sp,
                            color = if (isOnline) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = onUnbind,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("解绑")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 设备信息
            Text(
                text = "设备ID: ${device.deviceId.take(16)}…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "证书指纹: ${device.certFingerprint.take(32)}…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "最后连接: ${formatTimestamp(device.lastSeen)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 配对码对话框
 */
@Composable
private fun PairingDialog(
    pairingCode: String,
    certFingerprint: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配对码") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "让儿童端输入以下信息完成配对",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 配对码（大字体）
                Text(
                    text = pairingCode,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 8.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "有效期 5 分钟",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "证书指纹（核对用）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = certFingerprint.take(40) + "…",
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

// ==================== 工具函数 ====================

/** 判断设备是否在线（5 分钟内有心跳） */
private fun isDeviceOnline(device: ChildDeviceInfo): Boolean {
    return System.currentTimeMillis() - device.lastSeen < 5 * 60 * 1000L
}

/** 刷新设备数据（合并数据库与内存数据） */
private fun refreshData(context: android.content.Context, onResult: (List<ChildDeviceInfo>) -> Unit) {
    val dbDevices = ParentDao.getDevices(context)
    val liveDevices = ParentP2PListenerService.instance?.getConnectedDevices() ?: emptyList()

    // 合并：内存中的数据优先（有实时 IP 和 lastSeen）
    val merged = dbDevices.map { db ->
        liveDevices.find { it.deviceId == db.deviceId } ?: db
    }.toMutableList()

    // 添加仅在内存中的设备
    for (live in liveDevices) {
        if (merged.none { it.deviceId == live.deviceId }) {
            merged.add(live)
        }
    }

    onResult(merged)
}

/** 格式化时间戳 */
private fun formatTimestamp(millis: Long): String {
    if (millis <= 0) return "从未连接"
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}
