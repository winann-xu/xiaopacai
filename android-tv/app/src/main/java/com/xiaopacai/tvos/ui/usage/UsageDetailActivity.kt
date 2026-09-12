// [android-tv] 使用明细 + 使用报告 —— A12
// 小趴菜 TVOS 版
//
// 明细：今日各应用使用分钟数（已剔除本应用自身，避免锁屏自我计时）
// 报告：把逐应用明细按后端契约上报 POST /api/v1/device/usage-report
//       { DeviceId, Date: "yyyy-MM-dd", Records: [ {AppPackage, AppName, Category, DurationSeconds, IsBlocked} ] }
//       分类取自云端下发的 appCategories 映射（未命中记 other）
// 入口：守护状态页 → 使用明细（也可从家长设置进入）

package com.xiaopacai.tvos.ui.usage

import android.content.Context
import android.os.Bundle
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
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import com.xiaopacai.tvos.util.UsageStatsHelperTV
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val BG = Color(0xFF121212)
private val CARD = Color(0xFF1E1E1E)
private val GREEN = Color(0xFF4CAF50)
private val GREY = Color(0xFF888888)

class UsageDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                UsageDetailScreen(this@UsageDetailActivity) { finish() }
            }
        }
    }
}

private data class AppUsageRow(val pkg: String, val label: String, val minutes: Int)

@Composable
fun UsageDetailScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AppUsageRow>>(emptyList()) }
    var limit by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        // 剔除本应用自身：锁屏/守护页长时间整屏常亮，算进来会把自己的守护时间当成孩子看电视
        //（汇总 queryTodayTotalMinutes 早就这么做了，明细这张表也要同口径）
        val map = UsageStatsHelperTV.queryTodayUsage(context)
            .filterKeys { it != context.packageName }
        val pm = context.packageManager
        rows = map.entries
            .sortedByDescending { it.value }
            .map { e ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(e.key, 0)).toString()
                }.getOrDefault(e.key)
                AppUsageRow(e.key, label, e.value)
            }
        total = UsageStatsHelperTV.queryTodayTotalMinutes(context)
        limit = SharedPreferenceHelperTV.getDailyLimit(context).first()
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📊 今日使用明细", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(enabled = !busy, onClick = { tick++ }) { Text("↻ 刷新", fontSize = 15.sp) }
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "正在上报…"
                    scope.launch {
                        val ok = CloudSyncServiceTV.reportUsageNow(context)
                        busy = false
                        status = if (ok) "使用明细已上报到云端" else "上报失败（看应用日志）"
                    }
                }
            ) { Text("☁ 上报明细", fontSize = 15.sp) }
            Spacer(modifier = Modifier.width(10.dp))
            OutlinedButton(onClick = onBack) { Text("← 返回", fontSize = 15.sp) }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "今日合计 $total 分钟 / 限额 ${if (limit > 0) "$limit" else "未设置"} 分钟" +
                "　剩余 ${if (limit > 0) maxOf(0, limit - total) else "-"} 分钟",
            fontSize = 18.sp, color = Color.White
        )
        if (status.isNotBlank()) {
            Text(status, fontSize = 14.sp, color = GREEN)
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (rows.isEmpty()) {
            Text(
                "没有读到任何应用用量。\n若是首次使用，请到「守护状态」页确认「用量统计权限」已开启。",
                fontSize = 16.sp, color = GREY
            )
        } else {
            // 不用 LazyColumn：一屏固定行列布局，超出部分按权重压缩（电视上不滚动）
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = CARD),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Text("应用", fontSize = 14.sp, color = GREY, modifier = Modifier.weight(1f))
                        Text("用时", fontSize = 14.sp, color = GREY, modifier = Modifier.width(90.dp))
                        Text("占比", fontSize = 14.sp, color = GREY, modifier = Modifier.width(90.dp))
                    }
                    rows.take(10).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(row.label, fontSize = 15.sp, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                            Text("${row.minutes} 分", fontSize = 15.sp, color = GREEN, modifier = Modifier.width(90.dp))
                            Text(
                                if (total > 0) "${row.minutes * 100 / total}%" else "-",
                                fontSize = 15.sp, color = GREY, modifier = Modifier.width(90.dp)
                            )
                        }
                    }
                    if (rows.size > 10) {
                        Text("（仅显示用时最多的 10 个，共 ${rows.size} 个应用）", fontSize = 12.sp, color = GREY)
                    }
                }
            }
        }
    }
}
