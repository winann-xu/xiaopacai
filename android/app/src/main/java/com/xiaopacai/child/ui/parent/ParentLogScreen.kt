package com.xiaopacai.child.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.util.AppLog
import com.xiaopacai.child.util.CloudAccountManager
import com.xiaopacai.child.util.LogUploader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [TASK-MILESTONE-V3] 需求 14：家长端「日志」菜单页
 *
 * - 展示本机运行详细日志（时间/级别/模块/内容，滚动查看）；
 * - 复制全部 / 清空（确认弹窗）/ 手动上传云端；
 * - 自动上传说明：已登录家长账号时每 6 小时自动上传，未登录时按钮禁用；
 * - 内容已由 AppLog 写入时打码（无密码/验证码/令牌/密钥明文）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf(AppLog.entries()) }
    var fileSize by remember { mutableStateOf(AppLog.fileSizeBytes(context)) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val bound = remember { CloudAccountManager.isBound(context) }
    val lastUploadTs = remember { LogUploader.lastUploadTs(context) }

    fun refresh() {
        entries = AppLog.entries()
        fileSize = AppLog.fileSizeBytes(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ===== 概览与操作 =====
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "共 ${entries.size} 条 · 文件 ${formatBytes(fileSize)} · 环形缓冲上限 ${AppLog.MAX_ENTRIES} 条 / 5MB",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (bound) {
                            "已登录家长账号，每 6 小时自动上传云端；Web 端保留最近 7 天" +
                                if (lastUploadTs > 0) " · 上次上传 ${formatTime(lastUploadTs)}" else ""
                        } else {
                            "未登录家长账号，登录后自动上传云端；当前可离线查看/复制/清空"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(AppLog.exportText()))
                            notice = "已复制全部日志到剪贴板"
                        }, modifier = Modifier.weight(1f)) { Text("复制全部", fontSize = 13.sp) }
                        OutlinedButton(onClick = { showClearConfirm = true }, modifier = Modifier.weight(1f)) {
                            Text("清空", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            onClick = {
                                if (uploading) return@Button
                                uploading = true
                                scope.launch {
                                    notice = when (val r = LogUploader.uploadNow(context)) {
                                        is LogUploader.UploadResult.Ok ->
                                            if (r.uploaded > 0) "已上传 ${r.uploaded} 条日志到 Web" else "无新日志可上传"
                                        is LogUploader.UploadResult.Err -> r.message
                                        LogUploader.UploadResult.Skipped -> "未登录家长账号，无法上传"
                                    }
                                    uploading = false
                                }
                            },
                            enabled = bound && !uploading,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (uploading) "上传中…" else "上传云端", fontSize = 13.sp) }
                    }
                    if (notice != null) {
                        Text(notice!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ===== 日志列表（最新在前，滚动查看） =====
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { "${it.ts}-${it.level}-${it.tag}-${it.msg}" }) { e ->
                    LogRow(e)
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空运行日志？") },
            text = { Text("将清除本机全部日志记录（不影响已上传 Web 端的数据），操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.clear()
                    refresh()
                    notice = "日志已清空"
                    showClearConfirm = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LogRow(e: AppLog.Entry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            formatTime(e.ts),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            e.level,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = levelColor(e.level),
            modifier = Modifier
                .background(levelColor(e.level).copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(e.tag, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Text(e.msg, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun levelColor(level: String) = when (level) {
    AppLog.LEVEL_ERROR -> androidx.compose.ui.graphics.Color(0xFFD32F2F)
    AppLog.LEVEL_WARN -> androidx.compose.ui.graphics.Color(0xFFF57C00)
    AppLog.LEVEL_DEBUG -> androidx.compose.ui.graphics.Color(0xFF757575)
    else -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(ts))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}
