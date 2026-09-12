// [android-tv] 关于页 —— B13
// 小趴菜 TVOS 版：应用与设备信息 + 各功能页入口（守护状态 / 使用明细 / 账号安全 / 检查更新）
// 入口：家长设置底部「ⓘ 关于」

package com.xiaopacai.tvos.ui.about

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver
import com.xiaopacai.tvos.ui.guard.GuardStatusActivity
import com.xiaopacai.tvos.ui.security.AccountSecurityActivity
import com.xiaopacai.tvos.ui.upgrade.UpgradeActivity
import com.xiaopacai.tvos.ui.usage.UsageDetailActivity
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import com.xiaopacai.tvos.util.UpgradeManagerTV
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val BG = Color(0xFF121212)
private val CARD = Color(0xFF1E1E1E)
private val GREEN = Color(0xFF4CAF50)
private val GREY = Color(0xFF888888)

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                AboutScreen(this@AboutActivity) { finish() }
            }
        }
    }
}

@Composable
fun AboutScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var deviceName by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var bound by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        deviceName = SharedPreferenceHelperTV.getDeviceName(context).first()
        deviceId = SharedPreferenceHelperTV.getDeviceId(context).first()
        account = SharedPreferenceHelperTV.getParentAccount(context).first()
        bound = SharedPreferenceHelperTV.getDeviceBound(context).first()
        host = SharedPreferenceHelperTV.getCloudHost(context).first()
        port = SharedPreferenceHelperTV.getCloudPort(context).first()
    }

    fun open(cls: Class<*>) {
        runCatching { context.startActivity(Intent(context, cls)) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ⓘ 关于", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("← 返回", fontSize = 16.sp) }
        }
        Spacer(modifier = Modifier.height(14.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = CARD),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("小趴菜 · 电视守护", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoLine("版本", UpgradeManagerTV.currentVersion(context))
                    InfoLine("包名", context.packageName)
                    InfoLine("平台", "android-tv")
                    InfoLine("设备兜底名", deviceName.ifBlank { "小趴菜TV" })
                    InfoLine("设备号", deviceId.ifBlank { "（未注册）" })
                    InfoLine("服务器", if (host.isBlank()) "（未设置）" else "$host:$port")
                }
            }
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = CARD),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("账号与绑定", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoLine("家长账号", account.ifBlank { "（未登录过）" })
                    InfoLine("绑定状态", if (bound) "已绑定 ✓" else "未绑定")
                    InfoLine("设备所有者", if (GuardianDeviceAdminReceiver.isDeviceOwner(context)) "已就位 ✓" else "无")
                    InfoLine("设备管理员", if (GuardianDeviceAdminReceiver.isActive(context)) "已激活 ✓" else "未激活")
                    Spacer(modifier = Modifier.weight(1f))
                    Text("为孩子的健康使用习惯而做", fontSize = 12.sp, color = GREY)
                }
            }
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = CARD),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("快捷入口", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { open(GuardStatusActivity::class.java) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🛡 守护状态与权限", fontSize = 15.sp, maxLines = 1) }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { open(UsageDetailActivity::class.java) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📊 今日使用明细", fontSize = 15.sp, maxLines = 1) }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { open(AccountSecurityActivity::class.java) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🔐 账号安全与安全阀", fontSize = 15.sp, maxLines = 1) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { open(UpgradeActivity::class.java) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("⬆ 检查更新", fontSize = 15.sp, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 13.sp, color = GREY, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 13.sp, color = Color.White, maxLines = 1)
    }
}
