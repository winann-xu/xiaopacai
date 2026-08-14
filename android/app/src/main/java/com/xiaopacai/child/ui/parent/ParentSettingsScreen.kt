@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xiaopacai.child.ui.parent

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.p2p.P2PConnectionService
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.BuildConfig
import com.xiaopacai.child.ui.scan.QrScannerActivity
import com.xiaopacai.child.role.RoleManager
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.util.KeyStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

// Web 中继 JWT Token 存储
private const val WEB_PREFS_NAME = "xiaopacai_web_prefs"
private const val KEY_WEB_TOKEN = "web_token"

/**
 * [SEC-P1] 判断主机是否为局域网/本机地址。
 * HTTP 明文仅限局域网（红线 R6.x）；公网主机一律强制 HTTPS。
 */
private fun isLanHost(host: String): Boolean {
    if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
    val parts = host.split(".")
    if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
    val a = parts[0].toInt()
    val b = parts[1].toInt()
    return a == 127 || a == 10 ||
        (a == 192 && b == 168) ||
        (a == 172 && b in 16..31) ||
        (a == 169 && b == 254)
}

/**
 * [SEC-P1] HTTPS 优先执行 HTTP 请求：
 * - 先尝试 https；
 * - 仅当主机是局域网地址且失败原因为 SSL 握手失败（对端为明文 HTTP 服务）时，
 *   回退到 http 重试一次（其他异常不回退，避免 POST 重复提交）；
 * - 公网主机仅 https，杜绝明文传输凭据（红线 R6.x）。
 */
private fun <T> httpWithHttpsFirst(host: String, port: Int, block: (base: String) -> T): T {
    val candidates = if (isLanHost(host))
        listOf("https://$host:$port", "http://$host:$port")
    else
        listOf("https://$host:$port")
    var sslFailed = false
    for (base in candidates) {
        try {
            return block(base)
        } catch (e: javax.net.ssl.SSLException) {
            sslFailed = true
            android.util.Log.w("WebRelay", "HTTPS 请求失败(SSL)，尝试下一候选: ${e.message}")
        }
    }
    throw IllegalStateException("HTTPS 连接失败" + if (sslFailed) "（服务端未启用 HTTPS）" else "")
}

/**
 * [SEC-P1] HTTPS 优先 + 局域网回退的 JSON POST。
 * @return Triple(状态码, 响应体, 错误体)
 */
private fun httpPostJson(
    host: String, port: Int, path: String, body: String, token: String?
): Triple<Int, String, String> {
    return httpWithHttpsFirst(host, port) { base ->
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val code = conn.responseCode
        val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
        val err = if (code in 200..299) ""
            else try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        Triple(code, resp, err)
    }
}

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

    // Web 账号登录（用于中继鉴权）
    var webUsername by remember { mutableStateOf("") }
    var webPassword by remember { mutableStateOf("") }
    var webLoggingIn by remember { mutableStateOf(false) }
    // [REQ] Web 中继配对码/二维码（儿童端扫码经 Web 连接）
    var relayPairingCode by remember { mutableStateOf<String?>(null) }
    var relayQrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showRelayQr by remember { mutableStateOf(false) }
    var webTokenSaved by remember {
        mutableStateOf(
            context.getSharedPreferences(WEB_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_WEB_TOKEN, null)?.isNotBlank() == true
        )
    }
    // [REQ] 扫码登录 Web（家长端扫 Web 登录二维码 → 确认授权）
    var scanLoginMessage by remember { mutableStateOf<String?>(null) }

    fun handleWebLoginQr(text: String) {
        try {
            val obj = JSONObject(text)
            if (obj.optString("type") != "login_ticket") {
                scanLoginMessage = "该二维码不是 Web 登录二维码"
                return
            }
            val ticketUrl = obj.optString("ticketUrl", "")
            val ticket = ticketUrl.substringAfterLast('/').trim()
            val origin = ticketUrl.substringBefore("/auth/")
            if (ticket.isBlank() || origin.isBlank()) {
                scanLoginMessage = "二维码缺少有效的登录 Ticket"
                return
            }
            // 顺带把 Web 服务地址填入中继配置，方便后续连接
            if (relayHost.isBlank()) {
                try {
                    val u = java.net.URI(origin)
                    relayHost = u.host ?: ""
                    u.port.takeIf { it > 0 }?.let { relayPort = it }
                } catch (_: Exception) {}
            }
            val prefs = context.getSharedPreferences(WEB_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            // [SEC-P1] JWT 以 KeyStore 加密存储（"enc:" 前缀），读取时解密
            val token = prefs.getString(KEY_WEB_TOKEN, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { KeyStoreManager.decryptPrefsValue(it) }
                ?.takeIf { it.isNotBlank() }
            if (token == null) {
                scanLoginMessage = "请先用账号密码登录获取 Token，再扫码确认 Web 登录"
                return
            }
            scanLoginMessage = "正在确认 Web 登录…"
            GlobalScope.launch(Dispatchers.IO) {
                val result = confirmWebLogin(origin, ticket, token)
                withContext(Dispatchers.Main) {
                    scanLoginMessage = result
                }
            }
        } catch (e: Exception) {
            scanLoginMessage = "二维码解析失败：${e.message}"
        }
    }

    val webQrScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
        if (text.isNullOrBlank()) {
            scanLoginMessage = "未识别到二维码，请重试"
            return@rememberLauncherForActivityResult
        }
        handleWebLoginQr(text)
    }

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

            // === Web 账号登录（中继鉴权）===
            SectionTitle("Web 账号")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("登录 Web 3.0 服务以获取中继鉴权 Token", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (webTokenSaved) {
                        Text("Token 已保存 ✓", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = webUsername,
                        onValueChange = { webUsername = it },
                        label = { Text("用户名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webPassword,
                        onValueChange = { webPassword = it },
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                webLoggingIn = true
                                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                    try {
                                        val result = loginToWeb(context, relayHost, relayPort, webUsername, webPassword)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                                            if (result.startsWith("登录成功")) {
                                                webTokenSaved = true
                                                webPassword = ""  // 清空密码
                                            }
                                            webLoggingIn = false
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "登录失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                            webLoggingIn = false
                                        }
                                    }
                                }
                            },
                            enabled = webUsername.isNotBlank() && webPassword.isNotBlank() && relayHost.isNotBlank() && !webLoggingIn,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (webLoggingIn) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("登录获取 Token")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val prefs = context.getSharedPreferences(WEB_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                                prefs.edit().remove(KEY_WEB_TOKEN).apply()
                                webTokenSaved = false
                                Toast.makeText(context, "Token 已清除", Toast.LENGTH_SHORT).show()
                            },
                            enabled = webTokenSaved
                        ) {
                            Text("清除")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // [REQ] 扫码登录 Web：扫 Web 端登录二维码，用已保存 Token 确认授权
                    OutlinedButton(
                        onClick = {
                            try {
                                webQrScanLauncher.launch(
                                    android.content.Intent(context, QrScannerActivity::class.java)
                                )
                            } catch (e: Exception) {
                                scanLoginMessage = "无法打开相机：${e.message}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = webTokenSaved
                    ) {
                        Icon(Icons.Filled.QrCode, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (webTokenSaved) "扫码登录 Web（网页端被扫后自动登录）"
                        else "先登录获取 Token 后即可扫码确认")
                    }
                    // [DEBUG] 模拟器无真实相机，调试构建提供扫码结果注入入口
                    if (BuildConfig.DEBUG) {
                        TextButton(
                            onClick = {
                                val origin = if (relayHost.isNotBlank()) {
                                    "http://${relayHost}:${relayPort}"
                                } else {
                                    "http://192.168.50.11:5000"
                                }
                                GlobalScope.launch(Dispatchers.IO) {
                                    val testQr = createDebugLoginQr(origin)
                                    withContext(Dispatchers.Main) {
                                        if (testQr == null) {
                                            scanLoginMessage = "调试：向 Web 获取登录 Ticket 失败，请检查地址与网络"
                                        } else {
                                            webQrScanLauncher.launch(
                                                android.content.Intent(context, QrScannerActivity::class.java)
                                                    .putExtra(QrScannerActivity.EXTRA_TEST_RESULT, testQr)
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("调试：生成真实 Ticket 模拟扫码登录", fontSize = 12.sp) }
                    }
                }
            }

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
                                    val (result, pairCode) = connectToWebRelay(context, relayHost, relayPort)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                                        relayPairingCode = pairCode
                                        relayQrBitmap = null
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
                    // [REQ] 配对码与二维码：儿童端扫码后经 Web 中继连接本家长端
                    if (relayPairingCode != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("儿童端配对码：", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(relayPairingCode!!, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 4.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        relayQrBitmap = QrCodeGenerator.generateWebRelayQrCode(
                                            host = relayHost,
                                            port = 9527,
                                            pairingCode = relayPairingCode!!,
                                            fingerprint = ""  // Web 自签名证书，儿童端首次信任
                                        )
                                        showRelayQr = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.QrCode, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("生成二维码，儿童端扫码经 Web 连接")
                                }
                            }
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

    // === 扫码登录 Web 结果对话框 ===
    scanLoginMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { scanLoginMessage = null },
            title = { Text("扫码登录 Web") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { scanLoginMessage = null }) { Text("知道了") }
            }
        )
    }

    // === 儿童端经 Web 中继连接二维码 ===
    if (showRelayQr && relayQrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showRelayQr = false },
            title = { Text("儿童端扫码连接（经 Web 中继）") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        bitmap = relayQrBitmap!!.asImageBitmap(),
                        contentDescription = "Web 中继配对二维码",
                        modifier = Modifier.size(280.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "儿童端打开「连接家长端 → 扫码家长端」，扫描此二维码，即可经 Web 中继自动连接。",
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRelayQr = false }) { Text("关闭") }
            }
        )
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
                        OutlinedTextField(
                            value = oldPwd,
                            onValueChange = { oldPwd = it; err = null },
                            label = { Text("当前密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = newPwd,
                            onValueChange = { newPwd = it; err = null },
                            label = { Text("新密码（6-16位）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = confirmPwd,
                            onValueChange = { confirmPwd = it; err = null },
                            label = { Text("确认新密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
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
 *
 * [REQ] 流程修正：先从 Web 获取真实配对码（/api/pairing/generate-code），
 * 该配对码用于：① relay/register 绑定儿童设备到家长账号；② 家长端中继 P2P 连接；
 * ③ 展示给儿童端扫码（经 Web 中继连接）。
 *
 * @return (提示信息, 配对码)
 */
internal suspend fun connectToWebRelay(
    context: android.content.Context,
    host: String,
    port: Int
): Pair<String, String?> {
    android.util.Log.i("WebRelay", "connectToWebRelay start: $host:$port")
    // 读取已保存的 Web JWT Token（[SEC-P1] KeyStore 加密存储，读取时解密）
    val prefs = context.getSharedPreferences(WEB_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val webToken = prefs.getString(KEY_WEB_TOKEN, null)
        ?.takeIf { it.isNotBlank() }
        ?.let { KeyStoreManager.decryptPrefsValue(it) }
        ?.takeIf { it.isNotBlank() }
    if (webToken == null) {
        android.util.Log.w("WebRelay", "web_token 为空")
        return "请先在「Web 账号」中登录获取 Token" to null
    }
    android.util.Log.i("WebRelay", "web_token 存在，长度 ${webToken.length}")

    // 如果 P2P 服务未启动，先启动
    if (!ParentP2PListenerService.isRunning) {
        ParentP2PListenerService.start(context)
        kotlinx.coroutines.delay(500) // 等待监听启动
    }

    // [SEC-K2] 获取客户端身份证书指纹（TLS 握手提交的 mTLS 证书，服务端以此绑定家长端身份）
    val fingerprint = P2PConnectionService.getClientCertificateFingerprint()

    // [REQ] 从 Web 获取真实配对码（5 分钟有效，用于绑定儿童设备 + 中继连接）
    val pairingCode: String
    try {
        // [SEC-P1] HTTPS 优先（局域网可回退明文），凭据不再明文走公网
        val (code, respBody, errBody) = httpPostJson(host, port, "/api/pairing/generate-code", "{}", webToken)
        android.util.Log.i("WebRelay", "pairing generate-code HTTP $code")

        if (code in 200..299) {
            val json = JSONObject(respBody)
            pairingCode = json.optString("pairCode", "")
            if (pairingCode.isBlank()) {
                return "Web 未返回配对码" to null
            }
        } else {
            return "获取配对码失败: HTTP $code $errBody" to null
        }
    } catch (e: Exception) {
        return "获取配对码请求失败: ${e.message}" to null
    }

    val parentDeviceId = "parent-${android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).take(8)}"

    // 向 Web 服务发起中继注册（使用 /api/relay/register + JWT Authorization）
    // [SEC-K2] 注册成功后解析会话令牌，P2P 握手必须携带（服务端与 relay_sessions 比对）
    val registerResult: String
    var sessionToken: String? = null
    // [SEC-P1] 服务端经 JWT 通道下发的 P2P 指纹：固定中继 TLS 证书比对（红线 R3.x）
    var serverFingerprint: String? = null
    try {
        val body = JSONObject().apply {
            put("deviceId", parentDeviceId)
            put("role", "parent")
            put("fingerprint", fingerprint)
            put("pairingCode", pairingCode)
            put("listenPort", 9527L)
        }

        // [SEC-P1] HTTPS 优先（局域网可回退明文）
        val (code, respBody, errBody) = httpPostJson(host, port, "/api/relay/register", body.toString(), webToken)

        if (code in 200..299) {
            val response = JSONObject(respBody)
            sessionToken = response.optString("sessionToken", "").takeIf { it.isNotBlank() }
            serverFingerprint = response.optString("serverFingerprint", "").takeIf { it.isNotBlank() }
            registerResult = "中继注册成功: $respBody"
            if (sessionToken == null) {
                return "中继注册响应缺少会话令牌（服务端版本过旧？）" to pairingCode
            }
        } else {
            return "中继注册失败: HTTP $code $errBody" to pairingCode
        }
    } catch (e: Exception) {
        return "注册请求失败: ${e.message}。检查 Web 服务是否可访问。" to pairingCode
    }

    // 注册成功 → 连接 Web P2P 9527 端口（携带 relay=true + parent 设备 ID + 会话令牌）
    try {
        val p2pConnection = com.xiaopacai.child.service.GuardianForegroundService.getP2PConnection()
        if (p2pConnection != null) {
            // [SEC-P1] 固定 Web 服务端证书指纹（红线 R3.x）：
            // 优先用注册响应（JWT 鉴权通道）下发的指纹；无则回退本地持久化指纹；
            // 两者皆无 → 拒绝首连（禁止 TOFU），提示升级服务端
            val knownServerFingerprint = context
                .getSharedPreferences("guardian_prefs", android.content.Context.MODE_PRIVATE)
                .getString("relay_fingerprint", "")?.takeIf { it.isNotBlank() }
            val expectedFingerprint = serverFingerprint ?: knownServerFingerprint
            if (expectedFingerprint == null) {
                return "$registerResult\n服务端未提供 P2P 指纹，已拒绝首连（请升级 Web 服务端）" to pairingCode
            }
            p2pConnection.connect(
                host = host,
                port = 9527,  // Web P2P TLS 监听端口
                expectedFingerprint = expectedFingerprint,
                deviceId = parentDeviceId,
                deviceName = "家长端-${android.os.Build.MODEL}",
                pairingCode = pairingCode,
                isRelay = true,  // 中继模式
                sessionToken = sessionToken,
                allowTofu = false,  // [SEC-P1] 非扫码路径禁止 TOFU
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            )
            return "$registerResult\nP2P 中继连接已发起（端口 9527）\n配对码: $pairingCode" to pairingCode
        } else {
            return "$registerResult\nP2P 连接服务未就绪" to pairingCode
        }
    } catch (e: Exception) {
        return "$registerResult\nP2P 连接失败: ${e.message}" to pairingCode
    }
}

/**
 * 登录 Web 3.0 服务获取 JWT Token 并保存到 SharedPreferences
 */
private suspend fun loginToWeb(context: android.content.Context, host: String, port: Int, username: String, password: String): String {
    return try {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }

        // [SEC-P1] HTTPS 优先（局域网可回退明文），登录凭据不落明文链路
        val (code, respBody, errBody) = httpPostJson(host, port, "/api/auth/login", body.toString(), null)

        if (code in 200..299) {
            val json = JSONObject(respBody)
            val accessToken = json.optString("accessToken", "")
            if (accessToken.isNotBlank()) {
                // [SEC-P1] Token 以 KeyStore AES-GCM 加密后落盘（红线 R4.1）
                val prefs = context.getSharedPreferences(WEB_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_WEB_TOKEN, KeyStoreManager.encryptPrefsValue(accessToken)).apply()
                "登录成功，Token 已保存"
            } else {
                "登录响应缺少 accessToken"
            }
        } else if (code == 401) {
            "登录失败: 用户名或密码错误"
        } else {
            "登录失败: HTTP $code $errBody"
        }
    } catch (e: Exception) {
        "登录请求失败: ${e.message}"
    }
}

/**
 * [REQ] 确认 Web 扫码登录：家长端扫 Web 登录二维码后，
 * 从 ticketUrl 提取 ticket 并调用 POST /api/auth/login-ticket/{ticket}/confirm
 * （需已保存的 web_token 作为登录态）。
 */
private suspend fun confirmWebLogin(origin: String, ticket: String, token: String): String {
    return try {
        val url = URL("$origin/api/auth/login-ticket/$ticket/confirm")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use { it.write("{}") }

        val code = conn.responseCode
        val body = try {
            if (code in 200..299) conn.inputStream?.bufferedReader()?.readText()
            else conn.errorStream?.bufferedReader()?.readText()
        } catch (_: Exception) { "" }

        if (code in 200..299) {
            "扫码登录已确认 ✓ 网页端将自动登录"
        } else {
            val err = try { JSONObject(body ?: "").optString("error", body ?: "") } catch (_: Exception) { body ?: "" }
            "确认失败($code): ${err.take(120)}"
        }
    } catch (e: Exception) {
        "确认请求失败: ${e.message}"
    }
}

/**
 * [DEBUG] 向 Web 服务生成一个真实登录 Ticket，并包装成二维码内容
 * （仅调试构建使用：模拟器无相机，用于全链路验证扫码登录）
 */
private suspend fun createDebugLoginQr(origin: String): String? {
    return try {
        val url = URL("$origin/api/auth/login-ticket")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use { it.write("{\"clientId\":\"android-debug\"}") }

        if (conn.responseCode in 200..299) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val ticket = json.optString("ticket", "")
            if (ticket.isBlank()) return null
            val expiresAt = json.optLong("expiresAt", System.currentTimeMillis() / 1000 + 90)
            JSONObject().apply {
                put("type", "login_ticket")
                put("ticketUrl", "$origin/auth/login-ticket/$ticket")
                put("expiresAt", expiresAt)
                put("action", "scan_to_login")
            }.toString()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
