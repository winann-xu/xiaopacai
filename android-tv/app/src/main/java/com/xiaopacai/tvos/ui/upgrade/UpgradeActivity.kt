// [android-tv] 应用内升级页 —— B10
// 小趴菜 TVOS 版
// 显示当前/最新版本与更新说明，「立即升级」= 下载（后台线程）→ 安装（DO 静默 / 系统向导）
// 入口：① 主界面自动检查（有新版才弹）② 关于页「检查更新」

package com.xiaopacai.tvos.ui.upgrade

import android.content.Context
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
import com.xiaopacai.tvos.util.AppLogTV
import com.xiaopacai.tvos.util.UpgradeManagerTV
import kotlinx.coroutines.launch

private val BG = Color(0xFF121212)
private val GREEN = Color(0xFF4CAF50)
private val GREY = Color(0xFF888888)

class UpgradeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                UpgradeScreen(this@UpgradeActivity) { finish() }
            }
        }
    }
}

@Composable
fun UpgradeScreen(context: Context, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpgradeManagerTV.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    val current = remember { UpgradeManagerTV.currentVersion(context) }

    LaunchedEffect(Unit) {
        state = UpgradeManagerTV.check(context)
    }

    BackHandler(enabled = true) { if (!busy) onDone() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(18.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⬆ 应用升级", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Spacer(modifier = Modifier.height(14.dp))

            when (val s = state) {
                null -> Text("正在检查更新…", fontSize = 18.sp, color = GREY)
                is UpgradeManagerTV.State.UpToDate -> {
                    Text("当前版本：$current", fontSize = 18.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("已是最新版本 ✓", fontSize = 20.sp, color = GREEN)
                }
                is UpgradeManagerTV.State.Failed -> {
                    Text("检查更新失败", fontSize = 20.sp, color = Color(0xFFFF7043))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(s.reason, fontSize = 14.sp, color = GREY, textAlign = TextAlign.Center)
                }
                is UpgradeManagerTV.State.Available -> {
                    Text("当前版本：$current　→　最新版本：${s.version}", fontSize = 20.sp, color = Color.White)
                    if (s.forceUpdate) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("此版本为强制升级", fontSize = 15.sp, color = Color(0xFFFF7043))
                    }
                    if (s.changelog.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("更新内容：", fontSize = 15.sp, color = GREY, modifier = Modifier.fillMaxWidth())
                        Text(s.changelog, fontSize = 16.sp, color = Color(0xFFE0E0E0))
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                progress = "正在下载升级包…"
                                scope.launch {
                                    val file = UpgradeManagerTV.download(context, s.downloadUrl)
                                    if (file == null) {
                                        progress = "下载失败，请检查网络后重试"
                                        busy = false
                                        return@launch
                                    }
                                    progress = "下载完成（${file.length() / 1024} KB），正在安装…"
                                    val ok = UpgradeManagerTV.install(context, file)
                                    progress = if (ok) {
                                        "已提交安装；设备所有者会静默完成，稍后自动重启应用"
                                    } else {
                                        "安装提交失败，请看应用日志"
                                    }
                                    busy = false
                                    AppLogTV.i("UpgradeActivity", "升级流程结束 ok=$ok")
                                }
                            }
                        ) { Text("⬇ 立即升级", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        if (!s.forceUpdate) {
                            OutlinedButton(enabled = !busy, onClick = onDone) { Text("稍后", fontSize = 18.sp) }
                        }
                    }
                }
            }

            if (progress.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(progress, fontSize = 15.sp, color = GREEN, textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(enabled = !busy, onClick = onDone) { Text("← 返回", fontSize = 15.sp) }
        }
    }
}
