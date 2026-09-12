// [android-tv] 设置 Activity — SettingsActivity
// 小趴菜 TVOS 版 — 家长配置（每日限额、设备名称、云端服务器、家长账号、紧急解除）
// 布局：2×3 网格固定单屏，不使用垂直滚动

package com.xiaopacai.tvos.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.tvos.util.EmergencyReleaseServiceTV
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 家长设置 Activity
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF121212)
            ) {
                SettingsScreen(
                    context = this@SettingsActivity,
                    onBack = { finish() }
                )
            }
        }
    }
}

// ==================== Compose 设置界面 ====================

@Composable
fun SettingsScreen(context: Context, onBack: () -> Unit) {
    var dailyLimitText by remember { mutableStateOf("60") }
    var deviceName by remember { mutableStateOf("小趴菜TV") }
    var cloudHost by remember { mutableStateOf("xpc.winann.com") }
    var cloudPort by remember { mutableStateOf("443") }
    var parentAccount by remember { mutableStateOf("") }
    var emergencyPassword by remember { mutableStateOf("") }
    var hasEmergencyPassword by remember { mutableStateOf(EmergencyReleaseServiceTV.hasPassword(context)) }
    var releaseRemaining by remember { mutableStateOf(EmergencyReleaseServiceTV.getRemainingMinutes(context)) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        parentAccount = SharedPreferenceHelperTV.getParentAccount(context).first()
        hasEmergencyPassword = EmergencyReleaseServiceTV.hasPassword(context)
        releaseRemaining = EmergencyReleaseServiceTV.getRemainingMinutes(context)
        // 读取已保存的设置
        dailyLimitText = SharedPreferenceHelperTV.getDailyLimit(context).first().toString()
        deviceName = SharedPreferenceHelperTV.getDeviceName(context).first()
        cloudHost = SharedPreferenceHelperTV.getCloudHost(context).first()
        cloudPort = SharedPreferenceHelperTV.getCloudPort(context).first().toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚙ 家长设置", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
            ) {
                Text("← 返回", fontSize = 18.sp, color = Color.White)
            }
        }

        // 配置网格：2 行 × 3 列，weight 自适应，保证一屏显示、无滚动
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsCard("📊 每日限额（分钟）", Modifier.weight(1f).fillMaxHeight()) {
                    TextField(
                        value = dailyLimitText,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) dailyLimitText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors()
                    )
                    Text("0 = 完全禁用", fontSize = 14.sp, color = Color(0xFF888888))
                }

                SettingsCard("📺 设备名称", Modifier.weight(1f).fillMaxHeight()) {
                    TextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = fieldColors()
                    )
                }

                SettingsCard("☁ 云端服务器", Modifier.weight(1f).fillMaxHeight()) {
                    TextField(
                        value = cloudHost,
                        onValueChange = { cloudHost = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("地址") },
                        colors = fieldColors()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = cloudPort,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 5) cloudPort = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors()
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.3f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 家长账号（登录记忆 / 清除）
                SettingsCard("🔑 家长账号", Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        text = if (parentAccount.isBlank()) "尚未登录过家长账号" else parentAccount,
                        fontSize = 16.sp,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text("只记账号、不存密码", fontSize = 13.sp, color = Color(0xFF888888))
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { openAccountSecurity(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔐 账号安全", fontSize = 14.sp, maxLines = 1)
                        }
                        if (parentAccount.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        SharedPreferenceHelperTV.clearParentAccount(context)
                                        parentAccount = ""
                                        Toast.makeText(context, "已清除记忆的账号", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("清除账号", fontSize = 14.sp, maxLines = 1)
                            }
                        }
                    }
                }

                // 紧急解除（离线可用的家长紧急密码）
                SettingsCard("🆘 紧急解除", Modifier.weight(1f).fillMaxHeight()) {
                    // 状态行紧贴标题：卡片可用高度有限，避免内容溢出被裁（历史 bug：状态文字与恢复按钮曾被挤掉）
                    Text(
                        text = when {
                            releaseRemaining > 0 -> "紧急解除中，剩余 $releaseRemaining 分钟"
                            hasEmergencyPassword -> "已设紧急密码，断网也能解除"
                            else -> "未设：仅能联网用账号密码解除"
                        },
                        fontSize = 12.sp,
                        color = if (releaseRemaining > 0) Color(0xFFEF5350) else Color(0xFF888888),
                        maxLines = 1
                    )
                    TextField(
                        value = emergencyPassword,
                        onValueChange = { emergencyPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("紧急密码（离线可用）") },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (emergencyPassword.length < 6) {
                                    Toast.makeText(context, "紧急密码至少 6 位", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                EmergencyReleaseServiceTV.setPassword(context, emergencyPassword)
                                emergencyPassword = ""
                                hasEmergencyPassword = true
                                Toast.makeText(context, "紧急密码已保存（可离线解除）", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存", fontSize = 15.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                EmergencyReleaseServiceTV.clearPassword(context)
                                hasEmergencyPassword = false
                                Toast.makeText(context, "已清除紧急密码", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("清除", fontSize = 15.sp)
                        }
                        // 解除中时把「恢复守护」并入同一行，避免多占一行高度
                        if (releaseRemaining > 0) {
                            OutlinedButton(
                                onClick = {
                                    EmergencyReleaseServiceTV.deactivate(context)
                                    releaseRemaining = 0
                                    Toast.makeText(context, "守护已恢复", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("恢复守护", fontSize = 15.sp)
                            }
                        }
                    }
                }

                // 【项2】防护增强：无障碍拦截（防换台逃逸）+ 设备管理员（防卸载）
                // 这两项 Android 要求家长在系统设置里手动开启一次，这里只做一键跳转 + 状态显示
                SettingsCard("\uD83D\uDEE1 防护增强", Modifier.weight(1f).fillMaxHeight()) {
                    // 【A4/A8】状态摘要 + 统一入口（逐项检查与一键跳转都在「守护状态」页）
                    Text(
                        text = "无障碍 " + (if (isAccessibilityOn(context)) "\u2713" else "\u2715") +
                                "　管理员 " + (if (GuardianDeviceAdminReceiver.isActive(context)) "\u2713" else "\u2715") +
                                "　所有者 " + (if (GuardianDeviceAdminReceiver.isDeviceOwner(context)) "\u2713" else "\u2715"),
                        fontSize = 12.sp,
                        color = Color(0xFFB0B0B0),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { openGuardStatus(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("\uD83D\uDEE1 守护状态与权限", fontSize = 14.sp, maxLines = 1)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { openUsageDetail(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("\uD83D\uDCCA 今日使用明细", fontSize = 14.sp, maxLines = 1)
                    }

                    // 【DO】设备所有者状态（比普通管理员高一个量级：可真正禁止卸载/隐藏应用/锁任务）
                    Text(
                        text = if (GuardianDeviceAdminReceiver.isDeviceOwner(context))
                            "设备所有者：已获得 \u2713 可真正禁止卸载" else "设备所有者：未获得（仅普通管理员）",
                        fontSize = 11.sp,
                        color = if (GuardianDeviceAdminReceiver.isDeviceOwner(context))
                            Color(0xFF4CAF50) else Color(0xFF888888),
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // 底部操作
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        val limit = dailyLimitText.toIntOrNull() ?: 60
                        SharedPreferenceHelperTV.saveDailyLimit(context, limit)
                        SharedPreferenceHelperTV.saveDeviceName(context, deviceName.trim())
                        SharedPreferenceHelperTV.saveCloudHost(context, cloudHost.trim())
                        SharedPreferenceHelperTV.saveCloudPort(context, cloudPort.toIntOrNull() ?: 443)
                        // 立即让限额生效（不必等下一次刷新）
                        TimeoutExecutorTV.setDailyLimit(limit)
                        Toast.makeText(context, "设置已保存 ✓", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("💾 保存设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    dailyLimitText = "60"
                    deviceName = "小趴菜TV"
                    cloudHost = "xpc.winann.com"
                    cloudPort = "443"
                    Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("↩ 恢复默认", fontSize = 20.sp)
            }

            OutlinedButton(
                onClick = { openAbout(context) },
                modifier = Modifier
                    .weight(0.6f)
                    .height(60.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("ⓘ 关于", fontSize = 18.sp)
            }
        }
    }
}

/** 输入框统一配色 */
@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF1F1F1F),
    unfocusedContainerColor = Color(0xFF1F1F1F),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

/**
 * 设置卡片
 */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** 【项2】无障碍守护是否已由家长开启 */
private fun isAccessibilityOn(context: Context): Boolean = runCatching {
    @Suppress("DEPRECATION")
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    enabled.split(':').any { it.startsWith("${context.packageName}/") }
}.getOrDefault(false)

/** 打开「账号安全」页（解绑本机 / 解除设备所有者 等危险操作的唯一出口） */
private fun openAccountSecurity(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, com.xiaopacai.tvos.ui.security.AccountSecurityActivity::class.java)
        )
    }.onFailure { android.util.Log.e("SettingsActivity", "打开账号安全页失败", it) }
}

/** 打开「守护状态与权限」页（A4 权限引导 + A8 守护状态） */
private fun openGuardStatus(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, com.xiaopacai.tvos.ui.guard.GuardStatusActivity::class.java)
        )
    }.onFailure { android.util.Log.e("SettingsActivity", "打开守护状态页失败", it) }
}

/** 打开「今日使用明细」页（A12） */
private fun openUsageDetail(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, com.xiaopacai.tvos.ui.usage.UsageDetailActivity::class.java)
        )
    }.onFailure { android.util.Log.e("SettingsActivity", "打开使用明细页失败", it) }
}

/** 打开「关于」页（B13） */
private fun openAbout(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, com.xiaopacai.tvos.ui.about.AboutActivity::class.java)
        )
    }.onFailure { android.util.Log.e("SettingsActivity", "打开关于页失败", it) }
}
