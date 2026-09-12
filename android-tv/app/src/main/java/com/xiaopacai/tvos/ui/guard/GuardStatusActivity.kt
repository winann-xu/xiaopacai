// [android-tv] 守护状态 + 权限引导 —— A8 / A4
// 小趴菜 TVOS 版
//
// 一页解决两件事：
//   A8 守护状态页：逐项列出「守护是否真的在生效」（权限、服务、看门狗、心跳、策略、锁定态）
//   A4 权限引导：每一项带状态与「去开启」，直接跳系统设置对应页
// 为什么要合在一页：两件事的判据完全相同，分开做等于同样的检查写两遍。
//
// 入口：家长设置 →「防护增强」→ 守护状态与权限

package com.xiaopacai.tvos.ui.guard

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.tvos.service.CloudSyncServiceTV
import com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver
import com.xiaopacai.tvos.service.GuardianWatchdog
import com.xiaopacai.tvos.service.TVGuardianForegroundService
import com.xiaopacai.tvos.service.TVAccessibilityService
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import com.xiaopacai.tvos.util.UsageStatsHelperTV
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BG = Color(0xFF121212)
private val CARD = Color(0xFF1E1E1E)
private val GREEN = Color(0xFF4CAF50)
private val GREY = Color(0xFF888888)
private val ORANGE = Color(0xFFFF9800)

class GuardStatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                GuardStatusScreen(this@GuardStatusActivity) { finish() }
            }
        }
    }
}

/** 一条可检查项 */
private data class CheckItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    val required: Boolean = true
)

@Composable
fun GuardStatusScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ticks by remember { mutableIntStateOf(0) }
    var account by remember { mutableStateOf("") }
    var bound by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(0) }
    var lastSync by remember { mutableStateOf(0L) }
    var bedtimeStart by remember { mutableStateOf("") }
    var bedtimeEnd by remember { mutableStateOf("") }

    LaunchedEffect(ticks) {
        account = SharedPreferenceHelperTV.getParentAccount(context).first()
        bound = SharedPreferenceHelperTV.getDeviceBound(context).first()
        limit = SharedPreferenceHelperTV.getDailyLimit(context).first()
        lastSync = SharedPreferenceHelperTV.getLastSyncTime(context).first()
        bedtimeStart = SharedPreferenceHelperTV.getBedtimeStart(context).first()
        bedtimeEnd = SharedPreferenceHelperTV.getBedtimeEnd(context).first()
    }

    val usagePermission = UsageStatsHelperTV.isUsageStatsPermissionGranted(context)
    val accessibility = isAccessibilityOn(context)
    val admin = GuardianDeviceAdminReceiver.isActive(context)
    val owner = GuardianDeviceAdminReceiver.isDeviceOwner(context)
    // ⚠️ canDrawOverlays 是 API 23+；Android 5.1 上直接调会 NoSuchMethodError（本次真机崩过）
    val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true // 旧系统悬浮窗是安装期权限，装上了就有
    }
    val battery = isIgnoringBattery(context)
    val guardianService = isServiceRunning(context, TVGuardianForegroundService::class.java)
    val lockReason = TimeoutExecutorTV.lockReason

    val protectItems = listOf(
        CheckItem(
            "用量统计权限", usagePermission,
            if (usagePermission) "可用（限额统计生效）" else "缺失 → 用量恒为 0，限额永不触发",
            actionLabel = "去开启",
            action = { UsageStatsHelperTV.openUsageStatsSettings(context) }
        ),
        CheckItem(
            "无障碍拦截", accessibility,
            if (accessibility) "已连接（换台/退出会被拉回）" else "未连接 → 逃逸拦截失效",
            actionLabel = "去开启",
            action = { openAccessibilitySettings(context) }
        ),
        CheckItem(
            "设备管理员", admin,
            if (admin) "已激活（卸载需先停用）" else "未激活 → 可被直接卸载",
            actionLabel = "去激活",
            action = { GuardianDeviceAdminReceiver.requestActivate(context) }
        ),
        CheckItem(
            "设备所有者", owner,
            if (owner) "已就位（可整机拦截 / 禁恢复出厂）" else "无 → 整机拦截降级为覆盖层",
            actionLabel = null, action = null, required = false
        ),
        CheckItem(
            "悬浮窗权限", overlay,
            if (overlay) "已允许" else "未允许（非必需：锁屏已改走 Activity，不依赖悬浮窗）",
            actionLabel = "去允许",
            action = { openOverlaySettings(context) },
            required = false
        ),
        CheckItem(
            "忽略电池优化", battery,
            if (battery) "已忽略（守护不易被系统杀）" else "未忽略 → 可能被省电策略杀后台",
            actionLabel = "去设置",
            action = { openBatterySettings(context) }
        )
    )

    val runItems = listOf(
        CheckItem(
            "守护前台服务", guardianService,
            if (guardianService) "在跑" else "未在跑 → 正在无守护状态！",
            actionLabel = "拉起",
            action = {
                GuardianWatchdog.ensureGuardsRunning(context)
                ticks++
            }
        ),
        CheckItem(
            "设备已绑定", bound,
            if (bound) "已绑定 ${account.ifBlank { "-" }}" else "未绑定 → 策略/公告下发不到",
            actionLabel = null, action = null, required = false
        ),
        CheckItem(
            "心跳同步", lastSync > 0,
            if (lastSync > 0) "最后成功：" + formatTime(lastSync) else "尚无成功心跳",
            actionLabel = "立即同步",
            action = {
                runCatching {
                    context.startService(Intent(context, CloudSyncServiceTV::class.java))
                }
                ticks++
            }
        ),
        CheckItem(
            "云端策略", limit > 0 || bedtimeStart.isNotBlank(),
            "每日限额 ${limit} 分钟" + if (bedtimeStart.isNotBlank() && bedtimeEnd.isNotBlank())
                " · 就寝 $bedtimeStart–$bedtimeEnd" else "",
            actionLabel = null, action = null, required = false
        ),
        CheckItem(
            "限时状态", !TimeoutExecutorTV.isLocked,
            if (TimeoutExecutorTV.isLocked) "已锁屏（$lockReason）" else "正常使用中（今日已用 ${TimeoutExecutorTV.usedMinutesToday} 分钟）",
            actionLabel = null, action = null, required = false
        )
    )

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🛡 守护状态与权限", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ticks++ }) { Text("↻ 重新检查", fontSize = 15.sp) }
            Spacer(modifier = Modifier.width(10.dp))
            OutlinedButton(onClick = onBack) { Text("← 返回", fontSize = 15.sp) }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = CARD),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Text("① 防护项（缺一项就会被绕过）", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = GREEN)
                    Spacer(modifier = Modifier.height(8.dp))
                    protectItems.forEach { CheckRow(item = it, compact = false) }
                }
            }
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = CARD),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Text("② 运行状态", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = GREEN)
                    Spacer(modifier = Modifier.height(8.dp))
                    runItems.forEach { CheckRow(item = it, compact = true) }
                }
            }
        }
    }
}

/** 一行检查项：名称 + 状态 + 可选动作按钮 */
@Composable
private fun CheckRow(item: CheckItem, compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (item.ok) "✓" else "✕", fontSize = 15.sp, color = if (item.ok) GREEN else ORANGE)
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, fontSize = if (compact) 14.sp else 15.sp, color = Color.White, maxLines = 1)
            Text(item.detail, fontSize = 11.sp, color = GREY, maxLines = 2)
        }
        if (!item.ok && item.action != null && item.actionLabel != null) {
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(onClick = item.action) { Text(item.actionLabel, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

// ==================== 检查工具 ====================

private fun isAccessibilityOn(context: Context): Boolean = runCatching {
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    val on = Settings.Secure.getInt(
        context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
    ) == 1
    on && enabled.contains(context.packageName) &&
        enabled.contains(TVAccessibilityService::class.java.simpleName)
}.getOrDefault(false)

private fun isIgnoringBattery(context: Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        true // 老系统没有电池优化白名单
    } else {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}.getOrDefault(true)

private fun isServiceRunning(context: Context, cls: Class<*>): Boolean = runCatching {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    am.getRunningServices(120).any { it.service.className == cls.name }
}.getOrDefault(false)

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ts))

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openOverlaySettings(context: Context) {
    runCatching {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Suppress("BatteryLife")
private fun openBatterySettings(context: Context) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
