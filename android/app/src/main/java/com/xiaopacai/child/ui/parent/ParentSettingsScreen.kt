package com.xiaopacai.child.ui.parent

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.role.RoleManager
import com.xiaopacai.child.XiaopacaiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * [TASK-OPT-12-P3] 家长端设置页（含忘记密码/恢复码/Web中继）
 *
 * 功能：
 * - 修改家长密码
 * - 忘记密码恢复（8 位恢复码）
 * - Web 中继连接配置
 * - P2P 服务控制
 * - 关于信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 密码修改
    var showChangePwd by remember { mutableStateOf(false) }

    // 恢复码
    var showRecoveryCode by remember { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }

    // Web 中继
    var relayHost by remember { mutableStateOf("") }
    var relayPort by remember { mutableIntStateOf(5000) }
    var relayEnabled by remember { mutableStateOf(false) }
    var relayConnecting by remember { mutableStateOf(false) }

    // P2P
    var isServiceRunning by remember { mutableStateOf(ParentP2PListenerService.isRunning) }

    // 数据清除
    var showClearData by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
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
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // === 账号安全 ===
            SectionTitle("账号安全")

            SettingsCard(icon = Icons.Filled.Lock, title = "修改家长密码", subtitle = "修改后需重新验证所有设备",
                onClick = { showChangePwd = true })

            SettingsCard(icon = Icons.Filled.Key, title = "忘记密码 / 恢复码",
                subtitle = if (recoveryCode != null) "恢复码: $recoveryCode" else "生成 8 位恢复码用于找回密码",
                onClick = {
                    val code = generateRecoveryCode()
                    RoleManager.setParentPassword(context, code) // 暂存恢复码哈希
                    recoveryCode = code
                    showRecoveryCode = true
                })

            // === Web 云端中继（需求3）===
            SectionTitle("Web 云端中继")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("连接 Web 3.0 服务进行跨网络监控", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = relayHost,
                        onValueChange = { relayHost = it },
                        label = { Text("Web 服务地址") },
                        placeholder = { Text("192.168.x.x 或 域名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = relayPort.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1024..65535) relayPort = v } },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用中继连接", fontSize = 14.sp)
                        Switch(checked = relayEnabled, onCheckedChange = { relayEnabled = it })
                    }

                    Button(
                        onClick = {
                            relayConnecting = true
                            // 异步连接 Web 中继
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                try {
                                    val result = connectToWebRelay(context, relayHost, relayPort)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                                        relayConnecting = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                        relayConnecting = false
                                    }
                                }
                            }
                        },
                        enabled = relayHost.isNotBlank() && !relayConnecting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (relayConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (relayEnabled) "连接中继" else "测试连接")
                        }
                    }
                }
            }

            // === P2P 服务 ===
            SectionTitle("P2P 监听服务")

            SettingsCard(
                icon = if (isServiceRunning) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                title = "P2P 服务: ${if (isServiceRunning) "运行中" else "已停止"}",
                subtitle = "端口 9527",
                onClick = {
                    if (isServiceRunning) {
                        ParentP2PListenerService.stop(context)
                        isServiceRunning = false
                    } else {
                        ParentP2PListenerService.start(context)
                        isServiceRunning = true
                    }
                }
            )

            // === 数据管理 ===
            SectionTitle("数据管理")

            SettingsCard(
                icon = Icons.Filled.DeleteForever,
                title = "清除全部数据",
                subtitle = "策略、公告、报告、配对信息将被永久删除",
                onClick = { showClearData = true },
                contentColor = MaterialTheme.colorScheme.error
            )

            // === 关于 ===
            SectionTitle("关于")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("应用版本", "小趴菜 2.2（双角色 · OPT12）")
                    InfoRow("数据库", "SQLCipher AES-256 加密")
                    InfoRow("P2P 协议", "TLS 1.3/1.2 + JSON 帧")
                    InfoRow("开源协议", "Apache-2.0")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // === 修改密码对话框 ===
    if (showChangePwd) {
        var oldPwd by remember { mutableStateOf("") }
        var newPwd by remember { mutableStateOf("") }
        var confirmPwd by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        var ok by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showChangePwd = false },
            title = { Text("修改家长密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ok) Text("密码已修改成功！", color = MaterialTheme.colorScheme.primary)
                    else {
                        OutlinedTextField(oldPwd, { oldPwd = it; err = null }, label = { Text("当前密码") }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(newPwd, { newPwd = it; err = null }, label = { Text("新密码（6-16位）") }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(confirmPwd, { confirmPwd = it; err = null }, label = { Text("确认新密码") }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                        if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                if (ok) TextButton(onClick = { showChangePwd = false }) { Text("关闭") }
                else TextButton(onClick = {
                    when {
                        oldPwd.isEmpty() || newPwd.isEmpty() -> err = "请填写所有字段"
                        newPwd != confirmPwd -> err = "两次密码不一致"
                        !RoleManager.isValidPasswordFormat(newPwd) -> err = "密码格式不正确"
                        !RoleManager.changeParentPassword(context, oldPwd, newPwd) -> err = "当前密码错误"
                        else -> ok = true
                    }
                }) { Text("确认修改") }
            },
            dismissButton = { TextButton(onClick = { showChangePwd = false }) { Text("取消") } }
        )
    }

    // === 恢复码展示对话框 ===
    if (showRecoveryCode && recoveryCode != null) {
        AlertDialog(
            onDismissRequest = { showRecoveryCode = false },
            title = { Text("恢复码") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("请妥善保存以下恢复码，用于忘记密码时找回账号。", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = recoveryCode!!,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 4.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("⚠️ 此恢复码仅显示一次，请立即截图或抄写保存", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecoveryCode = false }) { Text("我已保存") }
            }
        )
    }

    // === 数据清除确认 ===
    if (showClearData) {
        var confirmText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showClearData = false; confirmText = "" },
            title = { Text("清除全部数据") },
            text = {
                Column {
                    Text("此操作将永久删除所有家长端数据。请输入「确认删除」以继续：", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(confirmText, { confirmText = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("确认删除") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmText == "确认删除") {
                        try {
                            val db = XiaopacaiApp.instance.database.getWritable(
                                com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(context))
                            db.execSQL("DELETE FROM device_registry")
                            db.execSQL("DELETE FROM parent_policies")
                            db.execSQL("DELETE FROM parent_announcements")
                            db.execSQL("DELETE FROM parent_usage_summary")
                            db.close()
                            Toast.makeText(context, "数据已清除", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        showClearData = false; confirmText = ""
                    }
                }, enabled = confirmText == "确认删除",
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("确认删除")
                }
            },
            dismissButton = { TextButton(onClick = { showClearData = false; confirmText = "" }) { Text("取消") } }
        )
    }
}

// ==================== 辅助组件 ====================

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    contentColor: androidx.compose.ui.graphics.Color? = null
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(24.dp),
                tint = contentColor ?: MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = contentColor ?: MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 11.sp,
                    color = (contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f))
            }
            Icon(Icons.Filled.ChevronRight, null,
                tint = (contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp)
    }
}

// ==================== 工具函数 ====================

/**
 * 生成 8 位恢复码（需求12）
 */
private fun generateRecoveryCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  // 避免混淆字符
    val random = SecureRandom()
    return (0 until 8).map { chars[random.nextInt(chars.length)] }.joinToString("")
}

/**
 * 连接 Web 中继服务（需求3）
 */
private suspend fun connectToWebRelay(context: android.content.Context, host: String, port: Int): String {
    // 如果 P2P 服务未启动，先启动
    if (!ParentP2PListenerService.isRunning) {
        ParentP2PListenerService.start(context)
        kotlinx.coroutines.delay(500) // 等待监听启动
    }

    // 获取证书指纹
    val fingerprint = ParentP2PListenerService.instance?.getCertificateFingerprint() ?: ""

    // 生成配对码
    val pairingCode = ParentP2PListenerService.instance?.generatePairingCode() ?: ""

    // 向 Web 服务发起中继注册
    return try {
        val url = URL("http://$host:$port/api/pairing/relay-register")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val body = JSONObject().apply {
            put("deviceId", "parent-${android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).take(8)}")
            put("role", "parent")
            put("fingerprint", fingerprint)
            put("pairingCode", pairingCode)
            put("listenPort", 9527)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        if (conn.responseCode in 200..299) {
            val response = conn.inputStream.bufferedReader().readText()
            "中继连接成功: $response"
        } else {
            "中继注册失败: HTTP ${conn.responseCode}"
        }
    } catch (e: Exception) {
        "连接失败: ${e.message}。检查 Web 服务是否可访问。"
    }
}
