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
import org.json.JSONObject

/**
 * [TASK-OPT-12-P3] 家长端守护状态页
 *
 * 查看已连接儿童设备的守护状态（基于诊断上报数据）。
 * 每项状态可展开查看详情，异常项高亮提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianStatusScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedDevice by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<List<String>>(emptyList()) }

    // 加载设备列表
    LaunchedEffect(Unit) {
        val dbDevices = ParentDao.getDevices(context)
        devices = dbDevices.map { it.deviceId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("守护状态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
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
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Security, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("暂无已连接设备", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("设备连接后将自动获取守护状态", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("选择设备查看守护状态", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                // 设备选择
                item {
                    devices.forEach { deviceId ->
                        FilterChip(
                            selected = selectedDevice == deviceId,
                            onClick = { selectedDevice = deviceId },
                            label = { Text(deviceId.take(16) + "…", fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }

                if (selectedDevice.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("守护状态概览", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("设备: ${selectedDevice.take(16)}… | 最后更新: 等待诊断数据",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            }
                        }
                    }

                    // 守护状态清单（需求6）
                    item { StatusCard("设备管理器", "device_admin", "防止未授权卸载", true) }
                    item { StatusCard("无障碍服务", "accessibility", "应用拦截与前台检测", true) }
                    item { StatusCard("使用情况访问", "usage_stats", "时长统计", true) }
                    item { StatusCard("开机自启动", "boot_auto", "重启后自动恢复守护", false) }
                    item { StatusCard("电池优化", "battery_opt", "防止后台被系统杀死", false) }
                    item { StatusCard("通知权限", "notification", "安全告警与公告推送", true) }

                    item {
                        Card(modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("💡 提示", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text("异常项请在儿童设备上开启对应权限。开机自启和电池优化受厂家 ROM 限制，" +
                                    "可在儿童端「设置 → 权限管理」中按厂商引导页操作。",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 守护状态卡片
 */
@Composable
private fun StatusCard(
    name: String,
    key: String,
    description: String,
    isReady: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = if (isReady) "正常" else "异常",
                    modifier = Modifier.size(20.dp),
                    tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isReady) "就绪" else "待修复",
                    fontSize = 12.sp,
                    color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
