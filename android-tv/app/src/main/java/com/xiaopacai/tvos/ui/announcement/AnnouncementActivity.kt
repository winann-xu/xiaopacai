// [android-tv] 公告管理 Activity — AnnouncementActivity
// 小趴菜 TVOS 版 — 家长公告列表 / 新建 / 编辑 / 发布 / 撤回 / 删除
// 对接后端 /api/announcements（需家长 JWT，由家长验证后带入）

package com.xiaopacai.tvos.ui.announcement

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.tvos.util.NetworkHelper
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 公告管理 Activity（家长侧，进入前已完成家长验证）
 */
class AnnouncementActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ACCESS_TOKEN = "announcement_access_token"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                AnnouncementManageScreen(
                    context = this@AnnouncementActivity,
                    accessToken = token,
                    onBack = { finish() }
                )
            }
        }
    }
}

// ==================== 数据模型 ====================

data class AnnouncementItem(
    val id: Int,
    val title: String,
    val content: String,
    val priority: String,
    val status: String,
    val createdAt: String
)

// ==================== Compose 界面 ====================

@Composable
fun AnnouncementManageScreen(
    context: android.content.Context,
    accessToken: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<AnnouncementItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 编辑态：null = 列表态
    var editingId by remember { mutableStateOf<Int?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var editPriority by remember { mutableStateOf("normal") }

    suspend fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val host = SharedPreferenceHelperTV.getCloudHost(context).first()
        val port = SharedPreferenceHelperTV.getCloudPort(context).first()
        val url = NetworkHelper.buildBaseUrl(host, port) + path
        return suspendCancellableCoroutine { cont ->
            NetworkHelper.request(method, url, body, accessToken) { code, resp ->
                if (cont.isActive) cont.resume(code to resp)
            }
        }
    }

    fun reload() {
        loading = true
        message = null
        scope.launch {
            val (code, body) = call("GET", "/api/announcements")
            loading = false
            if (code in 200..299) {
                items = parseAnnouncements(body)
                if (items.isEmpty()) message = "暂无公告，点「➕ 新建」发布第一条"
            } else if (code == 401) {
                message = "登录态已失效，请重新验证家长身份"
            } else {
                message = "加载失败（HTTP $code）"
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("🔔 公告管理", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editingId == null) {
                    Button(
                        onClick = {
                            editTitle = ""
                            editContent = ""
                            editPriority = "normal"
                            editingId = NEW_ID
                        }
                    ) { Text("➕ 新建", fontSize = 16.sp) }
                }
                Button(
                    onClick = { reload() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
                ) { Text("↻ 刷新", fontSize = 16.sp) }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
                ) { Text("← 返回", fontSize = 16.sp) }
            }
        }

        message?.let {
            Text(it, fontSize = 16.sp, color = Color(0xFFFFB74D))
        }

        if (editingId != null) {
            // ===== 编辑 / 新建表单 =====
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (editingId == NEW_ID) "新建公告" else "编辑公告 #$editingId",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                    TextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        singleLine = true,
                        label = { Text("标题") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = tvFieldColors()
                    )
                    TextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("正文") },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors = tvFieldColors()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("优先级：${priorityLabel(editPriority)}", fontSize = 18.sp, color = Color.White)
                        OutlinedButton(onClick = {
                            editPriority = when (editPriority) {
                                "normal" -> "important"
                                "important" -> "urgent"
                                else -> "normal"
                            }
                        }) { Text("切换", fontSize = 16.sp) }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            enabled = !loading,
                            onClick = {
                                if (editTitle.isBlank() || editContent.isBlank()) {
                                    message = "标题和正文都不能为空"
                                    return@Button
                                }
                                loading = true
                                message = null
                                scope.launch {
                                    val payload = JSONObject().apply {
                                        put("Title", editTitle.trim())
                                        put("Content", editContent)
                                        put("Priority", editPriority)
                                    }.toString()
                                    val (code, _) = if (editingId == NEW_ID) {
                                        call("POST", "/api/announcements", payload)
                                    } else {
                                        call("PUT", "/api/announcements/$editingId", payload)
                                    }
                                    loading = false
                                    if (code in 200..299) {
                                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                        editingId = null
                                        reload()
                                    } else {
                                        message = "保存失败（HTTP $code）"
                                    }
                                }
                            }
                        ) { Text("💾 保存", fontSize = 16.sp) }
                        OutlinedButton(onClick = { editingId = null }) {
                            Text("取消", fontSize = 16.sp)
                        }
                    }
                }
            }
        } else {
            // ===== 公告列表 =====
            if (loading) {
                Text("加载中…", fontSize = 16.sp, color = Color(0xFF888888))
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items) { item ->
                    AnnouncementRow(
                        item = item,
                        onEdit = {
                            editingId = item.id
                            editTitle = item.title
                            editContent = item.content
                            editPriority = item.priority
                        },
                        onTogglePublish = {
                            scope.launch {
                                loading = true
                                val path = if (item.status == "published") {
                                    "/api/announcements/${item.id}/revoke"
                                } else {
                                    "/api/announcements/${item.id}/publish"
                                }
                                val (code, _) = call("POST", path)
                                loading = false
                                if (code in 200..299) {
                                    Toast.makeText(context, "操作成功", Toast.LENGTH_SHORT).show()
                                    reload()
                                } else {
                                    message = "操作失败（HTTP $code）"
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                loading = true
                                val (code, _) = call("DELETE", "/api/announcements/${item.id}")
                                loading = false
                                if (code in 200..299) {
                                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                    reload()
                                } else {
                                    message = "删除失败（HTTP $code）"
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 新建态哨兵 id */
private const val NEW_ID = -1

@Composable
private fun AnnouncementRow(
    item: AnnouncementItem,
    onEdit: () -> Unit,
    onTogglePublish: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(96.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                Text(
                    text = "${statusLabel(item.status)} · 优先级 ${priorityLabel(item.priority)} · ${item.createdAt}",
                    fontSize = 13.sp,
                    color = if (item.status == "published") Color(0xFF81C784) else Color(0xFF888888),
                    maxLines = 1
                )
            }
            Button(onClick = onTogglePublish) {
                Text(if (item.status == "published") "撤回" else "发布", fontSize = 15.sp)
            }
            OutlinedButton(onClick = onEdit) { Text("编辑", fontSize = 15.sp) }
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
            ) { Text("删除", fontSize = 15.sp) }
        }
    }
}

@Composable
private fun tvFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF1F1F1F),
    unfocusedContainerColor = Color(0xFF1F1F1F),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

private fun priorityLabel(priority: String): String = when (priority.lowercase()) {
    "urgent" -> "紧急"
    "important" -> "重要"
    else -> "普通"
}

private fun statusLabel(status: String): String = when (status.lowercase()) {
    "published" -> "已发布"
    "revoked" -> "已撤回"
    "expired" -> "已过期"
    else -> "草稿"
}

/** 容错解析公告列表（后端字段为 camelCase） */
private fun parseAnnouncements(body: String): List<AnnouncementItem> {
    val array = runCatching {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("items")
    }.getOrNull() ?: return emptyList()

    val result = mutableListOf<AnnouncementItem>()
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        result.add(
            AnnouncementItem(
                id = obj.optInt("id", 0),
                title = obj.optString("title", "(无标题)"),
                content = obj.optString("content", ""),
                priority = obj.optString("priority", "normal"),
                status = obj.optString("status", "draft"),
                createdAt = obj.optString("createdAt", obj.optString("created_at", "")).take(19).replace("T", " ")
            )
        )
    }
    return result
}
