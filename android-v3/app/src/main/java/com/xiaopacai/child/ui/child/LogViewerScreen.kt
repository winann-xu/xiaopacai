package com.xiaopacai.child.ui.child

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<AppLog.Entry>>(emptyList()) }
    var filterLevel by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            entries = AppLog.entries()
            kotlinx.coroutines.delay(2000)
        }
    }

    val filtered = entries.filter { e ->
        (filterLevel == null || e.level == filterLevel) &&
        (searchText.isBlank() || e.msg.contains(searchText, ignoreCase = true) ||
            e.tag.contains(searchText, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("E" to "错误", "W" to "警告", "I" to "信息", "D" to "调试").forEach { (level, label) ->
                    FilterChip(
                        selected = filterLevel == level,
                        onClick = { filterLevel = if (filterLevel == level) null else level },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // [TASK-V2.0.6-LOG-CRASH] 修复真机崩溃：同一毫秒多条日志 ts 相同，
                // 用 ts 作 key 会抛 "Key xxx was already used"；改用 AppLog 会话内唯一 seq
                items(filtered, key = { it.seq }) { entry ->
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val color = when (entry.level) {
                        "E" -> Color(0xFFD32F2F)
                        "W" -> Color(0xFFF57C00)
                        "D" -> Color(0xFF9E9E9E)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(fmt.format(Date(entry.ts)), fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
                        Text(entry.level, fontSize = 10.sp, color = color,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(16.dp))
                        Text(entry.tag, fontSize = 10.sp, color = color,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(72.dp))
                        Text(entry.msg.take(120), fontSize = 10.sp, color = color,
                            fontFamily = FontFamily.Monospace, maxLines = 2)
                    }
                }
            }
        }
    }
}
