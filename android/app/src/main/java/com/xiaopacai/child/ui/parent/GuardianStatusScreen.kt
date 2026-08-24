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
                        // [TASK-HARDENING-V1.1.1] Bug1-D/1-B：展示儿童端 P2P 上报的真实健康度与失守历史。
                        // 每 5 秒刷新（P2P 事件到达即更新；无数据设备如实显示「待上报」）
                        var refreshKey by remember { mutableIntStateOf(0) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                kotlinx.coroutines.delay(5000)
                                refreshKey++
                            }
                        }
                        val health = remember(refreshKey) {
                            com.xiaopacai.child.util.ParentGuardData.latestHealth(context, selectedDevice)
                        }
                        val events = remember(refreshKey) {
                            com.xiaopacai.child.util.ParentGuardData.events(context, selectedDevice, 20)
                        }
                        GuardianHealthSection(
                            deviceId = selectedDevice,
                            health = health,
                            events = events
                        )
                    }
                }
            }
        }
    }
}

/**
 * [TASK-HARDENING-V1.1.1] Bug1-D/1-B：守护健康度 + 失守历史展示区。
 *
 * health=null（儿童端尚未上报诊断/守护事件）→ 如实显示「待上报」；
 * 有数据 → 真实健康度（6 项权限逐项勾叉）+ Device Owner 检测 +
 * OPPO 保活引导项（无检测接口，仅引导）+ 失守事件历史。
 */
@Composable
private fun GuardianHealthSection(
    deviceId: String,
    health: JSONObject?,
    events: org.json.JSONArray
) {
    if (health == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("守护健康度", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(4.dp))
                Text("设备: ${deviceId.take(16)}… | 状态: 待上报",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Text("儿童端连接后会通过 P2P 上报健康度与失守事件（登录后转传云端），" +
                    "暂无数据时以儿童端「设置 → 权限管理」页面为准。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            }
        }
        return
    }

    val score = health.optInt("score", 0)
    val status = health.optString("status", "unknown")
    val statusColor = when (status) {
        "danger" -> MaterialTheme.colorScheme.error
        "attention" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val statusLabel = when (status) {
        "danger" -> "危险（核心守护缺失）"
        "attention" -> "需要注意"
        "good" -> "健康"
        else -> "未知"
    }
    val items = health.optJSONObject("items")

    // 概览
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("守护健康度", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("设备: ${deviceId.take(16)}… · ${health.optString("manufacturer", "?")} " +
                    health.optString("model", ""),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            }
            Text("$score/100", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = statusColor)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Text(statusLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = statusColor)
            if (health.optBoolean("guardDown", false)) {
                Text("⚠️ 当前守护失守中（等待儿童端恢复上报）", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // 6 项权限逐项真实状态
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("权限状态", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            HealthItemRow(items, "deviceAdmin", "设备管理器", "防卸载核心保护")
            HealthItemRow(items, "accessibility", "无障碍服务", "超时拦截核心通道")
            HealthItemRow(items, "usageStats", "使用情况访问", "时长采集")
            HealthItemRow(items, "bootAutoStart", "开机自启动", "重启后恢复守护")
            HealthItemRow(items, "batteryOptimizationDisabled", "电池优化已关闭", "防止后台被杀")
            HealthItemRow(items, "notification", "通知权限", "安全告警与公告推送")
        }
    }

    // Device Owner 检测（Bug1-C：仅检测说明，不落地激活）
    val deviceOwner = health.optJSONObject("deviceOwner")
    if (deviceOwner != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Device Owner（企业预置）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (deviceOwner.optBoolean("isActive", false))
                        "已激活（强管制）：小趴菜为设备所有者，防卸载最强，系统级管控。"
                    else
                        "未激活：可在儿童设备「守护状态 → 强管制模式」完成自授权预置（无需电脑；" +
                        "需 Android 11+ 且无账号/出厂重置状态）。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // OPPO 保活引导项（无公开检测接口，如实说明仅引导）
    val guides = health.optJSONObject("keepaliveGuides")
    if (guides != null && guides.optBoolean("colorOs", false)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("ColorOS 保活引导项", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("以下项目系统不提供检测接口（状态显示为引导）：自启动管理 / 后台冻结 / 最近任务锁定。" +
                    "请在儿童设备「设置 → 权限管理」按厂商引导逐项开启，可显著降低守护被系统结束的概率。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // 失守历史
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("失守历史（最近 ${events.length()} 条）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (events.length() == 0) {
                Text("暂无失守记录", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                for (i in 0 until events.length()) {
                    val ev = events.optJSONObject(i) ?: continue
                    GuardEventRow(ev)
                    if (i < events.length() - 1) {
                        Divider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }

    // 平台边界如实说明
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("ℹ️ 能力边界", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("无障碍服务被系统/OEM 移除后，第三方应用无法自动重新开启（平台硬限制），" +
                "只能检测 + 引导 + 告警；被强制停止的应用无法自恢复，打开应用即恢复。" +
                "本应用不引入黑科技保活。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/** 单条权限行（真实布尔值；未知按未就绪展示） */
@Composable
private fun HealthItemRow(items: JSONObject?, key: String, name: String, description: String) {
    val ready = items?.optBoolean(key, false) ?: false
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ready) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (ready) "正常" else "异常",
            modifier = Modifier.size(18.dp),
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            if (ready) "就绪" else "待修复",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
    if (description.isNotBlank()) {
        Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 26.dp))
    }
}

/** 单条失守事件行（guard_down=失守开始 / guard_restored=恢复） */
@Composable
private fun GuardEventRow(ev: JSONObject) {
    val isDown = ev.optString("event") == "guard_down"
    val time = formatTs(ev.optLong(if (isDown) "startTs" else "endTs", 0L))
    Row(verticalAlignment = Alignment.Top) {
        Text(if (isDown) "⚠️" else "✅", fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            if (isDown) {
                Text("失守开始 · ${ev.optString("reason", "原因未知")}",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error)
            } else {
                val durationSec = ev.optLong("durationSec", 0L)
                Text("已恢复 · ${ev.optString("restoredReason", "自动恢复")}" +
                    if (durationSec > 0) " · 失守 ${durationSec / 60} 分 ${durationSec % 60} 秒" else "",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(time, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Unix 秒时间戳 → 本地时间文案 */
private fun formatTs(unixSeconds: Long): String {
    if (unixSeconds <= 0) return ""
    return try {
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(unixSeconds * 1000))
    } catch (e: Exception) {
        ""
    }
}
