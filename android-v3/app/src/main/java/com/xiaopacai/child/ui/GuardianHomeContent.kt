package com.xiaopacai.child.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.service.CloudSyncService
import com.xiaopacai.child.service.EmergencyReleaseService
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.service.UsageStatsCollector
import com.xiaopacai.child.service.UsageStatsCollector.Companion.formatHms
import com.xiaopacai.child.ui.child.EmergencyReleaseDialog
import com.xiaopacai.child.ui.components.ParentLoginBindCard
import com.xiaopacai.child.ui.components.ParentAuthDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Announcement(
    val id: String,
    val title: String,
    val content: String,
    val time: String,
    val priority: Int,
    val acknowledged: Boolean = false
)

@Composable
fun GuardianHomeContent(
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val collector = GuardianForegroundService.getCollector()

    var todayUsedMinutes by remember { mutableStateOf(collector?.todayAdjustedMinutes?.toInt() ?: 0) }
    var dailyLimitMinutes by remember { mutableStateOf(collector?.todayLimitMinutes?.toInt() ?: 120) }
    var stopMode by remember { mutableStateOf(collector?.stopMode ?: "none") }
    var isDeviceAdminActive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            isDeviceAdminActive = com.xiaopacai.child.service.GuardianDeviceAdminReceiver.isActive(context)
            kotlinx.coroutines.delay(5000)
        }
    }

    var isTimeoutActive by remember { mutableStateOf(collector?.isTimeoutActive ?: false) }
    var resetOffsetMinutes by remember { mutableStateOf(collector?.resetOffsetMinutes?.toInt() ?: 0) }

    val cloudState by CloudSyncService.connectionState.collectAsState()

    var showEmergencyRelease by remember { mutableStateOf(false) }
    var showParentAuth by remember { mutableStateOf(false) }
    var pendingProtectedAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var countdown by remember { mutableStateOf(UsageStatsCollector.CountdownSnapshot.EMPTY) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = GuardianForegroundService.getCollector()
            if (c != null) {
                countdown = c.countdownSnapshot()
                c.lockIfCountdownExpired()
                todayUsedMinutes = c.todayAdjustedMinutes.toInt()
                dailyLimitMinutes = c.todayLimitMinutes.toInt().coerceAtLeast(1)
                stopMode = c.stopMode
                isTimeoutActive = c.isTimeoutActive
                resetOffsetMinutes = c.resetOffsetMinutes.toInt()
            } else {
                countdown = UsageStatsCollector.CountdownSnapshot.EMPTY
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    val usagePercent = if (countdown.limitMillis > 0)
        (countdown.usedMillis.toFloat() / countdown.limitMillis).coerceIn(0f, 1f) else 0f
    val isNearLimit = countdown.healthy && countdown.limitMillis > 0 &&
        !countdown.isTimeoutActive &&
        countdown.remainingMillis in 1..(15 * 60_000L)

    var announcements by remember { mutableStateOf(emptyList<Announcement>()) }
    var announcementToShow by remember { mutableStateOf<Announcement?>(null) }
    var shownAnnouncementIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            try {
                val passphrase = com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(context)
                val db = com.xiaopacai.child.XiaopacaiApp.instance.database.getReadable(passphrase)
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
                            id = it.getString(0), title = it.getString(1),
                            content = it.getString(2), priority = it.getInt(3),
                            time = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(it.getLong(4) * 1000)),
                            acknowledged = it.getLong(5) > 0
                        ))
                    }
                }
                announcements = list
                if (announcementToShow == null) {
                    val candidate = list.firstOrNull { it.priority < 2 && it.id !in shownAnnouncementIds }
                    if (candidate != null) {
                        announcementToShow = candidate
                        shownAnnouncementIds = shownAnnouncementIds + candidate.id
                    }
                }
            } catch (_: Exception) {}
        }
    }

    announcementToShow?.let { announcement ->
        AlertDialog(
            onDismissRequest = {
                markAnnouncementRead(context, announcement.id)
                announcementToShow = null
            },
            title = { Text(announcement.title, fontWeight = FontWeight.Bold) },
            text = { Text(announcement.content, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    markAnnouncementRead(context, announcement.id)
                    announcementToShow = null
                }) { Text("知道了") }
            }
        )
    }

    if (showParentAuth) {
        ParentAuthDialog(
            title = "家长验证",
            description = "此操作涉及守护设置，请输入家长密码。",
            onDismiss = { showParentAuth = false; pendingProtectedAction = null },
            onVerified = {
                showParentAuth = false
                pendingProtectedAction?.invoke()
                pendingProtectedAction = null
            }
        )
    }

    if (showEmergencyRelease) {
        EmergencyReleaseDialog(
            onDismiss = { showEmergencyRelease = false },
            onReleased = { showEmergencyRelease = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CloudConnectionBar(cloudState)
        }

        // [V2.0.3] 首页绑定家长账号入口（未绑定显示登录表单；已绑定显示账号）——
        // 破除"进守护状态需家长密码、新用户无密码到不了登录框"的死路
        item {
            ParentLoginBindCard()
        }

        if (!isDeviceAdminActive) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VerifiedUser, null, tint = Color(0xFFE65100),
                            modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("需要授权设备管理员", fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp, color = Color(0xFFE65100))
                            Text("点击完成 DO 授权以启用完整守护能力", fontSize = 12.sp,
                                color = Color(0xFF795548))
                        }
                        Button(onClick = { onNavigate(com.xiaopacai.child.ui.Routes.DO_SETUP) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("去授权", fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        }

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

        if (isTimeoutActive) {
            item { TimeoutBanner(stopMode = stopMode) }
        }

        if (EmergencyReleaseService.isActive(context)) {
            item {
                val remaining = EmergencyReleaseService.getRemainingMinutes(context)
                val ctx = context
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LockOpen, null, tint = Color(0xFFE53935))
                            Spacer(Modifier.width(8.dp))
                            Text("紧急解除中，${remaining} 分钟后自动恢复",
                                fontSize = 13.sp, color = Color(0xFFE53935), fontWeight = FontWeight.Medium)
                        }
                        // [V2.0.5] 紧急解除期间可手动恢复守护
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                EmergencyReleaseService.deactivate(ctx)
                                Toast.makeText(ctx, "守护已手动恢复", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("立即恢复守护")
                        }
                    }
                }
            }
        }

        item {
            Text("快捷功能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

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
                        pendingProtectedAction = { onNavigate(com.xiaopacai.child.ui.Routes.APP_CATEGORY) }
                        showParentAuth = true
                    }
                )
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FactCheck,
                    label = "守护状态",
                    onClick = {
                        pendingProtectedAction = { onNavigate(com.xiaopacai.child.ui.Routes.GUARD_STATUS) }
                        showParentAuth = true
                    }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.VerifiedUser,
                    label = "DO 授权",
                    onClick = { onNavigate(com.xiaopacai.child.ui.Routes.DO_SETUP) }
                )
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Warning,
                    label = "紧急停用",
                    onClick = { showEmergencyRelease = true }
                )
            }
        }

        if (announcements.isNotEmpty()) {
            item {
                Text("家长公告", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            }
            items(announcements, key = { it.id }) { announcement ->
                AnnouncementCard(announcement)
            }
        }

        item {
            val syncInfo = CloudSyncService.getLastSyncInfo(context)
            Text(
                text = "云端: $syncInfo · v${com.xiaopacai.child.BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun CloudConnectionBar(state: CloudSyncService.CloudSyncState) {
    val (text, color) = when (state) {
        CloudSyncService.CloudSyncState.CONNECTED -> "● 云端已连接" to Color(0xFF4CAF50)
        CloudSyncService.CloudSyncState.CONNECTING -> "◉ 连接中..." to Color(0xFFFF9800)
        CloudSyncService.CloudSyncState.ERROR -> "○ 连接异常" to Color(0xFFE53935)
        CloudSyncService.CloudSyncState.DISCONNECTED -> "○ 未连接" to Color(0xFF9E9E9E)
    }
    Text(text = text, color = color, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
fun RemainingTimeCard(
    usedMinutes: Int, limitMinutes: Int, usagePercent: Float,
    isNearLimit: Boolean, isTimeoutActive: Boolean, stopMode: String,
    resetOffsetMinutes: Int = 0,
    countdown: UsageStatsCollector.CountdownSnapshot = UsageStatsCollector.CountdownSnapshot.EMPTY
) {
    val guardDown = !countdown.healthy
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                guardDown -> Color(0xFF546E7A)
                isTimeoutActive -> Color(0xFFE53935)
                isNearLimit -> Color(0xFFFF9800)
                else -> MaterialTheme.colorScheme.primaryContainer
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val titleText = when {
                guardDown -> "守护失效"
                isTimeoutActive && stopMode == "full" -> "设备已停用"
                isTimeoutActive && stopMode == "partial" -> "娱乐应用已停用"
                isNearLimit -> "即将超时"
                else -> "今日使用时长"
            }
            val brightBg = guardDown || isTimeoutActive || isNearLimit
            Text(titleText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = if (brightBg) Color.White else MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(modifier = Modifier.height(12.dp))
            val bigText = when {
                guardDown -> "守护失效"
                isTimeoutActive -> "00:00:00"
                countdown.limitMillis <= 0 -> "未设置限额"
                else -> formatHms(countdown.remainingMillis)
            }
            Text(bigText, fontSize = if (guardDown || countdown.limitMillis <= 0) 34.sp else 56.sp,
                fontWeight = FontWeight.Bold,
                color = if (brightBg) Color.White else MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                when {
                    guardDown -> "时长采集已中断"
                    isTimeoutActive -> "已超时"
                    countdown.limitMillis <= 0 -> "家长端尚未设置每日限额"
                    else -> "剩余时长"
                },
                fontSize = 14.sp,
                color = if (brightBg) Color.White.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            if (guardDown) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("使用情况访问权限可能被关闭或采集服务中断。\n请到守护状态页检查并重新授权。",
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = when {
                    guardDown -> 0f; isTimeoutActive -> 1f
                    countdown.limitMillis > 0 -> usagePercent.coerceIn(0f, 1f)
                    else -> 0f
                },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = when {
                    guardDown -> Color.White.copy(alpha = 0.6f)
                    isTimeoutActive -> Color.White; isNearLimit -> Color.White
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = if (brightBg) Color.White.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.primaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (guardDown) "采集恢复后自动继续计时"
                else "今日限额 $limitMinutes 分钟 · 已用 $usedMinutes 分钟",
                fontSize = 12.sp,
                color = if (brightBg) Color.White.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
            if (!guardDown && resetOffsetMinutes > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("今日限额已重置（重置前 $resetOffsetMinutes 分钟不计入）",
                    fontSize = 11.sp,
                    color = if (brightBg) Color.White.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun TimeoutBanner(stopMode: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = Color(0xFFE65100), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(if (stopMode == "full") "整机停用模式" else "部分应用停用模式",
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFFE65100))
                Spacer(Modifier.height(4.dp))
                Text("学习类应用可继续使用。如需恢复，请联系家长。紧急电话始终可用。",
                    fontSize = 12.sp, color = Color(0xFF795548))
            }
        }
    }
}

@Composable
fun AnnouncementCard(announcement: Announcement) {
    val priorityColor = when (announcement.priority) {
        2 -> Color(0xFFE53935); 1 -> Color(0xFFFF9800); else -> Color(0xFF2196F3)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(priorityColor))
                Spacer(Modifier.width(8.dp))
                if (announcement.priority >= 2) {
                    Surface(color = Color(0xFFE53935), shape = RoundedCornerShape(4.dp)) {
                        Text("紧急", fontSize = 11.sp, color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(announcement.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f))
                Text(announcement.time, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(announcement.content, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        }
    }
}

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
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

private fun markAnnouncementRead(context: android.content.Context, announcementId: String) {
    try {
        val passphrase = com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(context)
        com.xiaopacai.child.data.database.AnnouncementDao(
            com.xiaopacai.child.XiaopacaiApp.instance.database
        ).markAsRead(announcementId, passphrase)
    } catch (_: Exception) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<com.xiaopacai.child.model.DiagnosticRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            records = com.xiaopacai.child.service.DiagnosticsService.runHealthCheck(context)
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("健康诊断", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        } else {
            val readyCount = records.count { it.ready }
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (readyCount == records.size) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (readyCount == records.size) "守护一切就绪" else "有 ${records.size - readyCount} 项需要修复",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = if (readyCount == records.size) Color(0xFF2E7D32) else Color(0xFFE65100)
                        )
                        Text("就绪 $readyCount / ${records.size}", fontSize = 12.sp, color = Color(0xFF795548))
                    }
                }
                records.forEach { record ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (record.ready) Icons.Default.Check else Icons.Default.Warning,
                                    contentDescription = if (record.ready) "已就绪" else "未就绪",
                                    tint = if (record.ready) Color(0xFF2E7D32) else Color(0xFFE65100)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(record.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(record.description, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                            if (!record.ready) {
                                val fixAction = com.xiaopacai.child.service.DiagnosticsService.getFixAction(context, record.checkKey)
                                if (fixAction != null) {
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { fixAction() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                        Text("修复", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
