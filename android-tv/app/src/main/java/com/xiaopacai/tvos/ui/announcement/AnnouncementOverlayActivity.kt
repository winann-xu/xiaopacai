// [android-tv] 公告弹窗 — AnnouncementOverlayActivity
// 小趴菜 TVOS 版 — 展示云端下发的公告（家长在 web/手机发布的），展示后回执给服务端
//
// 数据来源：CloudSyncServiceTV 心跳里发现 announcementSignature 变化 → GET /api/v1/device/announcements
//          → 过滤掉已回执的 → 用 EXTRA_JSON 传进来弹出（见 syncAnnouncements）
// 回执：展示并关闭后调 CloudSyncServiceTV.reportAnnouncementAck(id) → POST /api/v1/device/announcement-ack
//
// 优先级：normal=普通（30s 自动关）；important/urgent=重要/紧急（必须按「我知道了」）

package com.xiaopacai.tvos.ui.announcement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.tvos.service.CloudSyncServiceTV
import com.xiaopacai.tvos.util.AppLogTV
import kotlinx.coroutines.delay
import org.json.JSONArray

/** 一条云端公告（id 用字符串，后端可能给数字或字符串） */
data class OverlayAnnouncement(
    val id: String,
    val title: String,
    val content: String,
    val priority: String
) {
    val isImportant: Boolean get() = priority != "normal"
}

class AnnouncementOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AnnouncementOverlayTV"
        const val EXTRA_JSON = "announcements_json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = parseOverlayAnnouncements(intent.getStringExtra(EXTRA_JSON).orEmpty())
        if (items.isEmpty()) {
            AppLogTV.w(TAG, "公告弹窗无有效数据，直接关闭")
            finish()
            return
        }
        AppLogTV.i(TAG, "展示云端公告 ${items.size} 条")
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D1B2A)) {
                AnnouncementOverlayScreen(items) { shownIds ->
                    // 展示过即回执（服务端 AnnouncementDeliveries 记录 ack）
                    shownIds.forEach { CloudSyncServiceTV.reportAnnouncementAck(this, it) }
                    finish()
                }
            }
        }
    }
}

@Composable
fun AnnouncementOverlayScreen(items: List<OverlayAnnouncement>, onDone: (List<String>) -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(listOf(items[0].id)) }
    val current = items[index]

    // 电视上按返回键 = 我知道了（不要卡在公告页出不去）
    BackHandler(enabled = true) { onDone(seen) }

    // 普通公告 30 秒自动关闭；重要/紧急必须家长或孩子手动确认
    var countdown by remember(current.id) { mutableIntStateOf(30) }
    if (!current.isImportant) {
        LaunchedEffect(current.id) {
            while (countdown > 0) {
                delay(1000)
                countdown -= 1
            }
            onDone(seen)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .fillMaxHeight(0.78f)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(20.dp))
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (current.priority) {
                    "urgent" -> "🚨 紧急公告"
                    "important" -> "📢 重要公告"
                    else -> "📢 公告"
                },
                fontSize = 22.sp,
                color = if (current.isImportant) Color(0xFFFF7043) else Color(0xFF4CAF50),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = current.title,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = current.content,
                fontSize = 26.sp,
                color = Color(0xFFE0E0E0),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )

            if (items.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.indices.forEach { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == index) 20.dp else 10.dp)
                                .background(
                                    if (i == index) Color(0xFF4CAF50) else Color(0xFF424242),
                                    RoundedCornerShape(5.dp)
                                )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("第 ${index + 1} / ${items.size} 条", fontSize = 16.sp, color = Color(0xFF888888))
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!current.isImportant) {
                    Text("⏳ ${countdown}s 后自动关闭", fontSize = 18.sp, color = Color(0xFF888888))
                }
                if (index < items.lastIndex) {
                    OutlinedButton(onClick = {
                        index += 1
                        seen = seen + items[index].id
                    }) { Text("下一条 ▶", fontSize = 20.sp) }
                }
                Button(onClick = {
                    if (index < items.lastIndex) {
                        index += 1
                        seen = seen + items[index].id
                    } else {
                        onDone(seen)
                    }
                }) { Text("✓ 我知道了", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** 容错解析：id 兼容字符串/数字 */
fun parseOverlayAnnouncements(raw: String): List<OverlayAnnouncement> {
    if (raw.isBlank()) return emptyList()
    val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    val out = mutableListOf<OverlayAnnouncement>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.opt("id")?.toString() ?: continue
        if (id.isBlank() || id == "null") continue
        out.add(
            OverlayAnnouncement(
                id = id,
                title = o.optString("title", "(无标题)"),
                content = o.optString("content", ""),
                priority = o.optString("priority", "normal").lowercase()
            )
        )
    }
    return out
}
