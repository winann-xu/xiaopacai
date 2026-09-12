// [android-tv] 账号安全 Activity — AccountSecurityActivity
// 小趴菜 TVOS 版
//
// 对标手机版「账号与安全」页，电视端落这些：
//   1. 云端账号 / 绑定状态 / 本机设备号（只读）
//   2. 解绑本机（一次家长密码 → 登录+二次验证+删除，服务端硬删本机全部关联数据）
//   3. 【安全阀】解除设备所有者 / 停用设备管理员 —— 拿回「能卸载、能换包」的能力
//   4. 清除记忆的账号
//
// 为什么必须有安全阀：电视拿到 device owner 后本应用会 setUninstallBlocked(true)，
// 于是这个包（尤其 debug 包）既装不上新的、也卸不掉旧的，换包/清场只能恢复出厂。
// 家长可达的解除入口是必备出口。

package com.xiaopacai.tvos.ui.security

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.tvos.service.GuardianDeviceAdminReceiver
import com.xiaopacai.tvos.util.DeviceUnbindTV
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val BG = Color(0xFF121212)
private val CARD = Color(0xFF1E1E1E)
private val GREEN = Color(0xFF4CAF50)
private val GREY = Color(0xFF888888)
private val WARN = Color(0xFFFF7043)

class AccountSecurityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                AccountSecurityScreen(context = this@AccountSecurityActivity, onBack = { finish() })
            }
        }
    }
}

@Composable
fun AccountSecurityScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var accountInput by remember { mutableStateOf("") }
    var bound by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }
    var isOwner by remember { mutableStateOf(false) }
    var adminOn by remember { mutableStateOf(false) }
    var uninstallBlocked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var confirmTitle by remember { mutableStateOf<String?>(null) }
    var confirmMsg by remember { mutableStateOf("") }
    var confirmLabel by remember { mutableStateOf("确认") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val passwordFocus = remember { FocusRequester() }

    fun reload() {
        scope.launch {
            account = SharedPreferenceHelperTV.getParentAccount(context).first()
            if (accountInput.isBlank()) accountInput = account
            bound = SharedPreferenceHelperTV.getDeviceBound(context).first()
            deviceId = SharedPreferenceHelperTV.getDeviceId(context).first()
            deviceName = SharedPreferenceHelperTV.getDeviceName(context).first()
            isOwner = GuardianDeviceAdminReceiver.isDeviceOwner(context)
            adminOn = GuardianDeviceAdminReceiver.isActive(context)
            uninstallBlocked = GuardianDeviceAdminReceiver.isUninstallBlocked(context)
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun askConfirm(label: String, title: String, msg: String, action: () -> Unit) {
        confirmLabel = label
        confirmTitle = title
        confirmMsg = msg
        pendingAction = action
    }

    val title = confirmTitle
    if (title != null) {
        // 遥控器按返回 = 取消（不能因为取消确认框就把整个页面退掉）
        BackHandler(enabled = true) {
            confirmTitle = null
            pendingAction = null
        }
        ConfirmBox(
            title = title,
            message = confirmMsg,
            confirmLabel = confirmLabel,
            onCancel = { confirmTitle = null; pendingAction = null },
            onConfirm = {
                confirmTitle = null
                val a = pendingAction
                pendingAction = null
                a?.invoke()
            }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔐 账号安全", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = GREEN)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("← 返回", fontSize = 16.sp) }
        }
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1) 云端账号
            SecCard("☁ 云端账号", Modifier.weight(1f)) {
                Text(
                    if (account.isBlank()) "尚未登录过家长账号" else account,
                    fontSize = 15.sp, color = Color.White, maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (bound) "设备已绑定此账号 ✓" else "未绑定（管控不会下发）",
                    fontSize = 13.sp, color = if (bound) GREEN else Color(0xFFFF9800)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            SharedPreferenceHelperTV.clearParentAccount(context)
                            account = ""
                            accountInput = ""
                            status = "已清除记忆的账号"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清除记忆的账号", fontSize = 14.sp, maxLines = 1) }
            }

            // 2) 本机设备
            SecCard("📺 本机设备", Modifier.weight(1f)) {
                Text("设备号", fontSize = 12.sp, color = GREY)
                Text(deviceId.ifBlank { "（无）" }, fontSize = 13.sp, color = Color.White, maxLines = 1)
                Spacer(modifier = Modifier.height(8.dp))
                Text("防止卸载", fontSize = 12.sp, color = GREY)
                Text(
                    if (uninstallBlocked) "已生效 ✓" else "未生效",
                    fontSize = 13.sp, color = if (uninstallBlocked) GREEN else GREY
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("设备名", fontSize = 12.sp, color = GREY)
                Text(deviceName.ifBlank { "小趴菜TV" }, fontSize = 13.sp, color = Color.White, maxLines = 1)
            }

            // 3) 解绑本机
            SecCard("🔓 解绑本机", Modifier.weight(1f)) {
                Text("服务端会删除这台电视的策略与使用数据", fontSize = 12.sp, color = GREY)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = accountInput,
                    onValueChange = { accountInput = it },
                    label = { Text("家长账号", fontSize = 13.sp) },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown &&
                            (e.key == Key.DirectionDown || e.key == Key.DirectionRight)
                        ) {
                            passwordFocus.requestFocus()
                            true
                        } else false
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("家长密码", fontSize = 13.sp) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val pwd = password
                        val acc = accountInput
                        askConfirm(
                            "解绑",
                            "解绑本机？",
                            "将删除服务端这台电视的全部管控数据（策略/使用记录），电视回到未绑定状态；重绑会启用全新设备身份。"
                        ) {
                            scope.launch {
                                busy = true
                                status = "正在解绑…"
                                val r = DeviceUnbindTV.unbind(context, acc, pwd)
                                busy = false
                                status = when (r) {
                                    is DeviceUnbindTV.Result.Success -> "已解绑：服务端管控数据已删除"
                                    is DeviceUnbindTV.Result.NotBound -> "本地已清理（${r.reason}）"
                                    is DeviceUnbindTV.Result.Failed -> "解绑失败：${r.reason}"
                                }
                                password = ""
                                reload()
                            }
                        }
                    },
                    enabled = !busy && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "处理中…" else "解绑本机", fontSize = 15.sp) }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 4) 安全阀
            SecCard("🛡 安全阀（换包/卸载出口）", Modifier.weight(2f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设备所有者", fontSize = 14.sp, color = Color.White)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(if (isOwner) "是 ✓" else "否", fontSize = 14.sp, color = if (isOwner) GREEN else GREY)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设备管理员", fontSize = 14.sp, color = Color.White)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(if (adminOn) "已激活" else "未激活", fontSize = 14.sp, color = if (adminOn) GREEN else GREY)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("禁止卸载本应用", fontSize = 14.sp, color = Color.White)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(if (uninstallBlocked) "已生效" else "未生效", fontSize = 14.sp, color = if (uninstallBlocked) GREEN else GREY)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            askConfirm(
                                "解除设备所有者",
                                "解除设备所有者？",
                                "解除后本应用不再受系统级保护：可以被直接卸载，管控随之失效。仅在换包、清场、故障排查时使用。"
                            ) {
                                val ok = GuardianDeviceAdminReceiver.clearDeviceOwner(context)
                                status = if (ok) "已解除设备所有者（现在可卸载/换包）" else "解除失败，请查看应用日志"
                                reload()
                            }
                        },
                        enabled = isOwner || adminOn,
                        modifier = Modifier.weight(1f)
                    ) { Text("解除设备所有者", fontSize = 14.sp, maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            askConfirm(
                                "停用管理员",
                                "停用设备管理员？",
                                "停用后卸载本应用不再需要额外步骤（防卸载能力失效）。"
                            ) {
                                GuardianDeviceAdminReceiver.deactivateAdmin(context)
                                status = "已停用设备管理员"
                                reload()
                            }
                        },
                        enabled = adminOn,
                        modifier = Modifier.weight(1f)
                    ) { Text("停用管理员", fontSize = 14.sp, maxLines = 1) }
                }
            }

            // 5) 说明
            SecCard("⚠️ 说明", Modifier.weight(1f)) {
                Text("· 解绑 = 服务端删除这台电视的管控数据", fontSize = 12.sp, color = GREY)
                Spacer(modifier = Modifier.height(4.dp))
                Text("· 解除设备所有者 = 不再禁止卸载（换包/清场用）", fontSize = 12.sp, color = GREY)
                Spacer(modifier = Modifier.height(4.dp))
                Text("· 密码只用于本次操作，不写入存储", fontSize = 12.sp, color = GREY)
            }
        }

        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(status, fontSize = 15.sp, color = GREEN, maxLines = 1)
        }
    }
}

@Composable
private fun SecCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(CARD, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = GREEN, maxLines = 1)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ConfirmBox(
    title: String,
    message: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 700.dp)
                .background(Color(0xFF262626), RoundedCornerShape(14.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚠️ $title", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = WARN)
            Spacer(modifier = Modifier.height(12.dp))
            Text(message, fontSize = 15.sp, color = Color.White)
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // 取消声明在前 → 默认焦点落在取消，避免误确认
                OutlinedButton(onClick = onCancel) { Text("取消", fontSize = 16.sp) }
                Button(onClick = onConfirm) { Text(confirmLabel, fontSize = 16.sp) }
            }
        }
    }
}
