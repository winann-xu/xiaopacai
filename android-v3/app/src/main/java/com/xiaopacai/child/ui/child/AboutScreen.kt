package com.xiaopacai.child.ui.child

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xiaopacai.child.BuildConfig
import com.xiaopacai.child.ui.components.openOfficialSite

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val year = java.time.Year.now().value

    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("关于小趴菜", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text("小趴菜亲子守护 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "开源家长监控软件，帮助家长管理儿童设备使用时长，" +
                        "拦截不适宜内容，守护儿童健康成长。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text("© $year 小趴菜开源社区 · Apache-2.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("官网：xpc.winann.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openOfficialSite(context) })
            }
        },
        confirmButton = {
            TextButton(onClick = onBack) { Text("知道了") }
        }
    )
}
