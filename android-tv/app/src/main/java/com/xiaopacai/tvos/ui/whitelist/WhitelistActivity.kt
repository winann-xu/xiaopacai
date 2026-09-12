// [android-tv] 白名单 Activity — WhitelistActivity
// 小趴菜 TVOS 版 — 管理不受时长限制的应用

package com.xiaopacai.tvos.ui.whitelist

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 白名单管理 Activity
 * 
 * 白名单应用不受每日时长限制
 * 功能：
 * - 列出所有已安装 TV 应用
 * - 勾选/取消勾选白名单
 * - 添加/移除
 */
class WhitelistActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WhitelistActivityTV"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF121212)
            ) {
                WhitelistScreen(
                    onBack = { finish() },
                    onSave = {
                        Toast.makeText(this, "白名单已保存 ✓", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}

// ==================== Compose 白名单界面 ====================

@Composable
fun WhitelistScreen(
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    // 模拟已安装应用列表
    val allApps = remember {
        listOf(
            AppInfo("tv.danmaku.bili", "哔哩哔哩 TV", true),
            AppInfo("com.duokan.airplay.tvs", "小米投屏", true),
            AppInfo("com.dmtech.dmbdmb", "大芒果", false),
            AppInfo("com.huliao.video", "黄瓜视频", false),
            AppInfo("com.leju.lelive", "乐视听电视", false),
            AppInfo("com.xiaomi.mitv.appstore", "小米电视应用商店", true),
            AppInfo("com.xiaomi.mitv.videocache", "小米电视下载", true),
            AppInfo("com.xiaomi.mitv.upgrade", "小米电视升级", true)
        )
    }

    var filteredApps by remember { mutableStateOf(allApps) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📺 白名单应用",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
            ) {
                Text("← 返回", fontSize = 18.sp, color = Color.White)
            }
        }

        Text(
            text = "白名单应用不受每日时长限制",
            fontSize = 18.sp,
            color = Color(0xFF888888),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 应用网格（2 列）：用 weight 自适应，保证一屏显示、无滚动
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            filteredApps.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { app ->
                        AppListItem(
                            app = app,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onToggle = {
                                filteredApps = filteredApps.map {
                                    if (it.packageName == app.packageName) {
                                        it.copy(isWhitelisted = !it.isWhitelisted)
                                    } else it
                                }
                            }
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 保存按钮
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("💾 保存白名单", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AppListItem(app: AppInfo, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (app.isWhitelisted) Color(0xFF1B3A1B) else Color(0xFF2C2C2C)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = app.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = Color(0xFF666666),
                    maxLines = 1
                )
            }
            Text(
                text = if (app.isWhitelisted) "✅" else "❌",
                fontSize = 28.sp,
                modifier = Modifier.clickable(onClick = onToggle)
            )
        }
    }
}

data class AppInfo(
    val packageName: String,
    val name: String,
    var isWhitelisted: Boolean
)
