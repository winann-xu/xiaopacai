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
 * [TASK-ROLE-P2] 家长端公告管理页
 *
 * 功能：
 * - 查看已发布/草稿/已撤回公告列表
 * - 新建公告（标题/正文/优先级/有效期）
 * - 编辑草稿
 * - 发布/撤回公告
 * - 删除公告
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentAnnouncementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var announcements by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var showEditor by remember { mutableStateOf<JSONObject?>(null) }  // 非null=编辑已有公告
    var showNewEditor by remember { mutableStateOf(false) }  // true=新建公告
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var filterStatus by remember { mutableStateOf("all") }  // all / draft / published / revoked

    // 加载公告列表
    LaunchedEffect(filterStatus) {
        val all = mutableListOf<JSONObject>()
        val arr = ParentDao.getAnnouncements(context)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (filterStatus == "all" || obj.optString("status") == filterStatus) {
                all.add(obj)
            }
        }
        announcements = all
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("公告管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showNewEditor = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建公告")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 筛选标签
            ScrollableTabRow(
                selectedTabIndex = listOf("all", "draft", "published", "revoked").indexOf(filterStatus),
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 16.dp
            ) {
                listOf("全部" to "all", "草稿" to "draft", "已发布" to "published", "已撤回" to "revoked").forEach { (label, status) ->
                    Tab(
                        selected = filterStatus == status,
                        onClick = { filterStatus = status },
                        text = { Text(label, fontSize = 13.sp) }
                    )
                }
            }

            if (announcements.isEmpty()) {
                // 空态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Campaign,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无${statusLabel(filterStatus)}公告",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(announcements, key = { it.optString("announcementId") }) { announcement ->
                        AnnouncementCard(
                            announcement = announcement,
                            onEdit = { showEditor = announcement },
                            onPublish = {
                                ParentDao.publishAnnouncement(context, announcement.optString("announcementId"))
                                refreshAnnouncements(context, filterStatus) { announcements = it }
                            },
                            onRevoke = {
                                ParentDao.revokeAnnouncement(context, announcement.optString("announcementId"))
                                refreshAnnouncements(context, filterStatus) { announcements = it }
                            },
                            onDelete = { showDeleteConfirm = announcement.optString("announcementId") }
                        )
                    }
                }
            }
        }
    }

    // 编辑已有公告对话框
    showEditor?.let { editing ->
        AnnouncementEditorDialog(
            announcement = editing,
            onSave = { title, content, priority, validUntil ->
                ParentDao.saveAnnouncement(
                    context, editing.optString("announcementId"), title, content, priority,
                    editing.optString("status", "draft"), validUntil = validUntil
                )
                showEditor = null
                refreshAnnouncements(context, filterStatus) { announcements = it }
            },
            onDismiss = { showEditor = null }
        )
    }

    // 新建公告对话框
    if (showNewEditor) {
        AnnouncementEditorDialog(
            announcement = null,
            onSave = { title, content, priority, validUntil ->
                ParentDao.saveAnnouncement(
                    context, null, title, content, priority, "draft",
                    validUntil = validUntil
                )
                showNewEditor = false
                refreshAnnouncements(context, filterStatus) { announcements = it }
            },
            onDismiss = { showNewEditor = false }
        )
    }

    // 删除确认
    showDeleteConfirm?.let { id ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除公告") },
            text = { Text("确定要永久删除这条公告吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        ParentDao.deleteAnnouncement(context, id)
                        showDeleteConfirm = null
                        refreshAnnouncements(context, filterStatus) { announcements = it }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 公告卡片
 */
@Composable
private fun AnnouncementCard(
    announcement: JSONObject,
    onEdit: () -> Unit,
    onPublish: () -> Unit,
    onRevoke: () -> Unit,
    onDelete: () -> Unit
) {
    val status = announcement.optString("status", "draft")
    val priority = announcement.optInt("priority", 0)
    val priorityLabel = listOf("普通", "重要", "紧急")[priority.coerceIn(0, 2)]
    val priorityColor = listOf(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error
    )[priority.coerceIn(0, 2)]

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = announcement.optString("title", "无标题"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                // 状态标签
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = statusLabel(status),
                            fontSize = 11.sp
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = priorityLabel,
                    fontSize = 12.sp,
                    color = priorityColor
                )
                Text(
                    text = formatTimestamp(announcement.optLong("createdAt", 0) * 1000),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = announcement.optString("content", "").take(100) +
                    if (announcement.optString("content", "").length > 100) "…" else "",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                when (status) {
                    "draft" -> {
                        TextButton(onClick = onPublish) { Text("发布", fontSize = 13.sp) }
                        TextButton(onClick = onEdit) { Text("编辑", fontSize = 13.sp) }
                    }
                    "published" -> {
                        TextButton(onClick = onRevoke) { Text("撤回", fontSize = 13.sp) }
                    }
                    "revoked" -> {
                        TextButton(onClick = onEdit) { Text("编辑", fontSize = 13.sp) }
                    }
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除", fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 公告编辑器对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementEditorDialog(
    announcement: JSONObject?,
    onSave: (title: String, content: String, priority: Int, validUntil: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(announcement?.optString("title", "") ?: "") }
    var content by remember { mutableStateOf(announcement?.optString("content", "") ?: "") }
    var priority by remember { mutableIntStateOf(announcement?.optInt("priority", 0) ?: 0) }
    var validDays by remember { mutableIntStateOf(
        if ((announcement?.optLong("validUntil", 0) ?: 0) > 0)
            (((announcement!!.optLong("validUntil") - System.currentTimeMillis() / 1000) / 86400).toInt()).coerceAtLeast(1)
        else 30
    )}
    var titleError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (announcement != null) "编辑公告" else "新建公告") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; titleError = false },
                    label = { Text("公告标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = titleError
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("公告正文") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 8
                )
                // 优先级选择
                Text("优先级", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("普通", "重要", "紧急").forEachIndexed { index, label ->
                        FilterChip(
                            selected = priority == index,
                            onClick = { priority = index },
                            label = { Text(label, fontSize = 13.sp) }
                        )
                    }
                }
                // 有效期
                Text("有效期（天）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Slider(
                    value = validDays.toFloat(),
                    onValueChange = { validDays = it.toInt() },
                    valueRange = 1f..90f,
                    steps = 88
                )
                Text("$validDays 天", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) {
                    titleError = true
                } else {
                    val validUntil = if (validDays > 0) System.currentTimeMillis() / 1000 + validDays * 86400L else 0L
                    onSave(title.trim(), content.trim(), priority, validUntil)
                }
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun statusLabel(status: String): String = when (status) {
    "draft" -> "草稿"
    "published" -> "已发布"
    "revoked" -> "已撤回"
    else -> status
}

/** 刷新公告列表 */
private fun refreshAnnouncements(
    context: android.content.Context,
    filterStatus: String,
    onResult: (List<JSONObject>) -> Unit
) {
    val all = mutableListOf<JSONObject>()
    val arr = ParentDao.getAnnouncements(context)
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        if (filterStatus == "all" || obj.optString("status") == filterStatus) all.add(obj)
    }
    onResult(all)
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0) return ""
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}
