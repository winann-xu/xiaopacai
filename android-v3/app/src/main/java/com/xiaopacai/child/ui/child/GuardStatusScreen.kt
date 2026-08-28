package com.xiaopacai.child.ui.child

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.model.DiagnosticRecord
import com.xiaopacai.child.service.DiagnosticsService
import com.xiaopacai.child.service.GuardianDeviceAdminReceiver
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.service.UsageStatsCollector
import com.xiaopacai.child.service.UsageStatsCollector.Companion.formatHms
import com.xiaopacai.child.ui.components.ParentAuthDialog
import com.xiaopacai.child.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardStatusScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenLogViewer: () -> Unit = {},
    onOpenUpgrade: () -> Unit = {},
    onOpenAccountSecurity: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenDoSetup: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showParentAuth by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showAbout by remember { mutableStateOf(false) }

    val collector = GuardianForegroundService.getCollector()
    var todayUsedMinutes by remember { mutableStateOf(collector?.todayAdjustedMinutes?.toInt() ?: 0) }
    var dailyLimitMinutes by remember { mutableStateOf(collector?.todayLimitMinutes?.toInt() ?: 120) }
    var stopMode by remember { mutableStateOf(collector?.stopMode ?: "none") }
    var isTimeoutActive by remember { mutableStateOf(collector?.isTimeoutActive ?: false) }

    var countdown by remember { mutableStateOf(UsageStatsCollector.CountdownSnapshot.EMPTY) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = GuardianForegroundService.getCollector()
            if (c != null) {
                countdown = c.countdownSnapshot()
                todayUsedMinutes = c.todayAdjustedMinutes.toInt()
                dailyLimitMinutes = c.todayLimitMinutes.toInt()
                stopMode = c.stopMode
                isTimeoutActive = c.isTimeoutActive
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    if (showParentAuth) {
        ParentAuthDialog(
            title = "家长验证",
            description = "查看守护详情需要家长密码验证。",
            onDismiss = { showParentAuth = false; pendingAction = null },
            onVerified = {
                showParentAuth = false
                pendingAction?.invoke()
                pendingAction = null
            }
        )
    }

    // [V2.0.5] 关于对话框（原「关于」菜单无响应，改为展示应用信息）
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于小趴菜") },
            text = {
                Column {
                    Text("儿童守护 · 家长监控（儿童端）", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("版本：v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("开源免费 · 本地优先 · 数据不上云", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("守护状态", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val guardDown = !countdown.healthy
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        guardDown -> Color(0xFF546E7A)
                        isTimeoutActive -> Color(0xFFE53935)
                        else -> Color(0xFF4CAF50)
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            guardDown -> "守护失效"
                            isTimeoutActive -> "已超时停用"
                            else -> "守护运行中"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            guardDown -> "时长采集已中断"
                            isTimeoutActive -> formatHms(0)
                            countdown.limitMillis > 0 -> formatHms(countdown.remainingMillis)
                            else -> "未设置限额"
                        },
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (dailyLimitMinutes > 0)
                            "今日限额 $dailyLimitMinutes 分钟 · 已用 $todayUsedMinutes 分钟"
                        else
                            "今日已用 $todayUsedMinutes 分钟",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            listOf(
                Triple(Icons.Default.FactCheck, "健康诊断") { onOpenDiagnostics() },
                Triple(Icons.Default.Description, "运行日志") { onOpenLogViewer() },
                Triple(Icons.Default.SystemUpdate, "自动升级") { onOpenUpgrade() },
                Triple(Icons.Default.Shield, "账号与安全") {
                    pendingAction = onOpenAccountSecurity
                    showParentAuth = true
                },
                Triple(Icons.Default.Info, "关于") { showAbout = true },
                Triple(Icons.Default.VerifiedUser, "DO 授权") { onOpenDoSetup() }
            ).forEach { (icon, label, action) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { action() }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
