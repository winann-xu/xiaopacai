package com.xiaopacai.child.ui.parent

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.core.content.FileProvider
import com.xiaopacai.child.data.database.ParentDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * [TASK-ROLE-P2] 家长端使用报告页
 *
 * 功能：
 * - 日报/周报切换（今天/7天/30天）
 * - 每日总时长趋势
 * - 分类占比
 * - 按设备筛选
 * - 导出 TXT/JSON
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentReportScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedPeriod by remember { mutableIntStateOf(0) }  // 0=今天, 1=7天, 2=30天
    val periods = listOf("今天", "7天", "30天")
    val periodDays = listOf(1, 7, 30)

    // 数据
    var dailyTotals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var categoryBreakdown by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var totalMinutes by remember { mutableLongStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }

    // 日期计算
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val today = sdf.format(Date())
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -periodDays[selectedPeriod])
    val fromDate = sdf.format(cal.time)

    // 加载数据
    LaunchedEffect(selectedPeriod) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val totals = mutableListOf<JSONObject>()
            val arr = ParentDao.getDailyTotals(context, fromDate = fromDate, toDate = today)
            for (i in 0 until arr.length()) totals.add(arr.getJSONObject(i))
            dailyTotals = totals

            val catArr = ParentDao.getCategoryBreakdown(context, fromDate = fromDate)
            val catList = mutableListOf<JSONObject>()
            totalMinutes = 0
            for (i in 0 until catArr.length()) {
                val obj = catArr.getJSONObject(i)
                catList.add(obj)
                totalMinutes += obj.optLong("totalMinutes", 0)
            }
            categoryBreakdown = catList
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用报告") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 导出按钮
                    IconButton(onClick = {
                        exportReport(context, dailyTotals, categoryBreakdown, totalMinutes, selectedPeriod)
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "导出报告")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 周期选择
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        periods.forEachIndexed { index, label ->
                            FilterChip(
                                selected = selectedPeriod == index,
                                onClick = { selectedPeriod = index },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 总览卡片
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "总使用时长",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = formatMinutes(totalMinutes),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "统计周期：${periods[selectedPeriod]}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                // 每日趋势
                if (dailyTotals.isNotEmpty()) {
                    item {
                        Text("每日趋势", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    items(dailyTotals.take(10), key = { it.optString("date") }) { day ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = day.optString("date").takeLast(5),  // MM-DD
                                    fontSize = 14.sp
                                )
                                // 简易进度条
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    val maxMin = dailyTotals.maxOfOrNull { it.optLong("totalMinutes") } ?: 1L
                                    val pct = (day.optLong("totalMinutes").toFloat() / maxMin).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { pct },
                                        modifier = Modifier.fillMaxWidth().height(8.dp)
                                    )
                                }
                                Text(
                                    text = formatMinutes(day.optLong("totalMinutes")),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // 分类占比
                if (categoryBreakdown.isNotEmpty()) {
                    item {
                        Text("分类占比", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    items(categoryBreakdown) { cat ->
                        val catTotal = cat.optLong("totalMinutes", 0)
                        val pct = if (totalMinutes > 0) (catTotal * 100f / totalMinutes) else 0f
                        val catName = categoryName(cat.optString("category"))
                        val catEmoji = categoryEmoji(cat.optString("category"))

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("$catEmoji $catName", fontSize = 14.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = { pct / 100f },
                                        modifier = Modifier.width(80.dp).height(8.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${"%.1f".format(pct)}%",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // 空态
                if (dailyTotals.isEmpty() && categoryBreakdown.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Filled.Assessment,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "暂无使用数据",
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "等待儿童端上报使用时长",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * 导出报告为 TXT 文件并分享
 */
private fun exportReport(
    context: Context,
    dailyTotals: List<JSONObject>,
    categoryBreakdown: List<JSONObject>,
    totalMinutes: Long,
    periodIndex: Int
) {
    try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val periodNames = listOf("今日", "近7天", "近30天")

        val sb = StringBuilder()
        sb.appendLine("小趴菜家长端 · 使用报告")
        sb.appendLine("生成时间: ${sdf.format(Date())}")
        sb.appendLine("统计周期: ${periodNames[periodIndex]}")
        sb.appendLine("总使用时长: ${formatMinutes(totalMinutes)}")
        sb.appendLine()
        sb.appendLine("=== 每日趋势 ===")
        for (day in dailyTotals.take(7)) {
            sb.appendLine("${day.optString("date")}: ${formatMinutes(day.optLong("totalMinutes"))}")
        }
        sb.appendLine()
        sb.appendLine("=== 分类占比 ===")
        for (cat in categoryBreakdown) {
            val catTotal = cat.optLong("totalMinutes", 0)
            val pct = if (totalMinutes > 0) (catTotal * 100f / totalMinutes) else 0f
            sb.appendLine("${categoryEmoji(cat.optString("category"))} ${categoryName(cat.optString("category"))}: ${formatMinutes(catTotal)} (${"%.1f".format(pct)}%)")
        }

        // 写入缓存文件
        val file = File(context.cacheDir, "xiaopacai_report.txt")
        file.writeText(sb.toString())

        // 分享
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出使用报告"))
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun formatMinutes(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}小时${mins}分钟" else "${mins}分钟"
}

private fun categoryName(cat: String): String = when (cat) {
    "game" -> "游戏"
    "social" -> "社交"
    "video" -> "视频"
    "study" -> "学习"
    else -> "其他"
}

private fun categoryEmoji(cat: String): String = when (cat) {
    "game" -> "🎮"
    "social" -> "💬"
    "video" -> "🎬"
    "study" -> "📚"
    else -> "📱"
}
