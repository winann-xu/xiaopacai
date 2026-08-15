package com.xiaopacai.child.ui.components

// [TASK-MILESTONE-V3] 需求 7+12：关于页统一组件
// 儿童端与家长端共用同一组件，内容完全一致：
// - 版本号跟随 Git（构建脚本从 Git tag 注入 BuildConfig，见 docs/VERSIONING.md）
// - 年份动态取当前年（不再写死 2024）
// - 官网 xpc.winann.com 可点击打开 https://xpc.winann.com

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xiaopacai.child.BuildConfig

/** 官网地址（关于页可点击跳转） */
private const val OFFICIAL_SITE_URL = "https://xpc.winann.com"
private const val TAG = "AboutContent"

/**
 * 打开官网（系统浏览器）；无可用浏览器时 Toast 提示
 */
fun openOfficialSite(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Log.w(TAG, "无法打开官网: ${e.message}")
        android.widget.Toast.makeText(context, "无法打开浏览器", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 关于内容（统一文案，儿童端/家长端共用）：
 * 版本号来自 BuildConfig（Git tag 联动），年份动态，官网可点击。
 */
@Composable
fun AboutText(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 动态年份：跟随系统当前时间，不再写死
    val year = java.time.Year.now().value

    Column(modifier = modifier) {
        Text(
            "小趴菜亲子守护 v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "开源家长监控软件，帮助家长管理儿童设备使用时长，" +
                "拦截不适宜内容，守护儿童健康成长。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "© $year 小趴菜开源社区 · Apache-2.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        // 官网域名（可点击打开；Compose 1.5 无 Role.Link，读屏可正常播报文案本身）
        Text(
            "官网：xpc.winann.com",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { openOfficialSite(context) }
        )
    }
}

/**
 * 关于对话框（统一弹窗，儿童端/家长端共用）
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("关于小趴菜 🥬", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            AboutText()
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}
