@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xiaopacai.child.ui.parent

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.p2p.P2PConnectionService
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.BuildConfig
import com.xiaopacai.child.ui.components.AboutText
import com.xiaopacai.child.ui.scan.QrScannerActivity
import com.xiaopacai.child.util.CloudAccountManager
import com.xiaopacai.child.util.httpGetJson
import com.xiaopacai.child.util.httpPostJson
import com.xiaopacai.child.util.ParentAccountReset
import com.xiaopacai.child.data.database.ParentDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * [TASK-ACCOUNT-V1] 家长端设置页
 *
 * 功能（本地密码体系已退役，账号安全统一由云端账号承担）：
 * - Web 账号：展示绑定账号邮箱、退出登录、扫码确认 Web 登录
 * - Web 云端中继配置（需求3）
 * - P2P 服务控制
 * - 清除账号绑定与本地数据（云端验证，离线拒绝）
 * - 关于信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSettingsScreen(
    onBack: () -> Unit,
    // [TASK-PRELAUNCH-PARENT-RESET] 换账号清理完成后回调（返回登录页/新账号绑定状态）
    onAccountReset: () -> Unit,
    // [TASK-ACCOUNT-V1] 退出登录（清除本地账号绑定后回到家长登录页）
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Web 账号绑定状态
    var boundEmail by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context)) }
    var webTokenSaved by remember { mutableStateOf(CloudAccountManager.getToken(context) != null) }

    // Web 中继
    var relayHost by remember { mutableStateOf(CloudAccountManager.getServerHost(context) ?: "") }
    var relayPort by remember { mutableIntStateOf(CloudAccountManager.getServerPort(context)) }
    var relayEnabled by remember { mutableStateOf(false) }
    var relayConnecting by remember { mutableStateOf(false) }

    // [REQ] Web 中继配对码/二维码（儿童端扫码经 Web 连接）
    var relayPairingCode by remember { mutableStateOf<String?>(null) }
    var relayQrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showRelayQr by remember { mutableStateOf(false) }

    // [REQ] 扫码登录 Web（家长端扫 Web 登录二维码 → 确认授权）
    var scanLoginMessage by remember { mutableStateOf<String?>(null) }

    // [TASK-ACCOUNT-V1] 退出登录确认
    var showLogoutConfirm by remember { mutableStateOf(false) }

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
            // [TASK-ACCOUNT-V1] 已保存 JWT（KeyStore 加密存储，读取时解密）
            val token = CloudAccountManager.getToken(context)
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

    // [TASK-PRELAUNCH-PARENT-RESET] 换账号清理（云端验证，离线拒绝）
    var showAccountReset by remember { mutableStateOf(false) }

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
            // === Web 账号（[TASK-ACCOUNT-V1] 绑定账号展示；密码修改/找回在 Web 端完成）===
            SectionTitle("Web 账号")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (boundEmail != null) {
                        Text("已绑定账号", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(boundEmail!!, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        if (webTokenSaved) {
                            Spacer(Modifier.height(2.dp))
                            Text("登录凭据已加密保存在本设备（密码不落盘）", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("未绑定云端账号", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error)
                        Text("请退出后在家长登录页用 Web 3.0 账号登录", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                            modifier = Modifier.weight(1f),
                            enabled = webTokenSaved
                        ) {
                            Icon(Icons.Filled.QrCode, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (webTokenSaved) "扫码登录 Web" else "先登录后可扫码")
                        }
                        // [TASK-ACCOUNT-V1] 退出登录：清除本地账号绑定（不删除云端账号）
                        OutlinedButton(
                            onClick = { showLogoutConfirm = true },
                            modifier = Modifier.weight(1f),
                            enabled = boundEmail != null,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Logout, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("退出登录")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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

            // === 网页管理入口（IP + 域名双地址）===
            SectionTitle("网页管理")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("在浏览器中打开家长管理后台", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    WebConsoleLinkRow(
                        label = "IP 地址",
                        url = "http://8.217.165.122:5000",
                        display = "8.217.165.122:5000",
                        context = context
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    WebConsoleLinkRow(
                        label = "域名",
                        url = "https://xpc.winann.com",
                        display = "xpc.winann.com",
                        context = context
                    )
                }
            }

            // === Web 云端中继 ===
            // [TASK-MILESTONE-V3] 需求 13（D3 决策）：对普通家长隐藏，仅 admin 可见；
            // 服务端自动中继不受影响；非 admin 已保存的中继配置继续生效，仅隐藏配置入口
            if (CloudAccountManager.isAdmin(context)) {
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
                            // 持久化服务器地址（门禁验证/下次登录共用）
                            CloudAccountManager.saveServerBase(context, relayHost.trim(), relayPort)
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
            } // [TASK-MILESTONE-V3] 需求 13：中继设置 admin 门控结束

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

            // === [TASK-PRELAUNCH-PARENT-RESET] 账号与数据管理 ===
            SectionTitle("账号与数据")

            SettingsCard(
                icon = Icons.Filled.DeleteForever,
                title = "清除账号绑定与本地数据",
                subtitle = "需云端邮箱+密码验证（离线拒绝）；清除登录凭据、中继绑定与本地数据，回到未绑定状态",
                onClick = { showAccountReset = true },
                contentColor = MaterialTheme.colorScheme.error
            )

            // === 关于 ===
            // [TASK-MILESTONE-V3] 需求 7：关于统一组件（版本号跟随 Git，年份动态，官网可点击）
            SectionTitle("关于")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("应用版本", "v${BuildConfig.VERSION_NAME}（版本码 ${BuildConfig.VERSION_CODE}）")
                    InfoRow("数据库", "SQLCipher AES-256 加密")
                    InfoRow("P2P 协议", "TLS 1.3/1.2 + JSON 帧")
                    InfoRow("开源协议", "Apache-2.0")
                    Spacer(modifier = Modifier.height(8.dp))
                    // 双端统一关于内容（含动态年份与可点击官网）
                    AboutText()
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // === 退出登录确认对话框 ===
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("将清除本设备保存的账号登录凭据（不删除云端账号）。下次进入家长端需重新云端验证。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    CloudAccountManager.clearAccount(context)
                    onLogout()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            }
        )
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

    // === [TASK-ACCOUNT-V1] 换账号清理确认（云端邮箱+密码验证，离线拒绝）===
    if (showAccountReset) {
        var resetEmail by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context) ?: "") }
        var resetPassword by remember { mutableStateOf("") }
        var resetError by remember { mutableStateOf<String?>(null) }
        var resetBusy by remember { mutableStateOf(false) }
        var resetDone by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!resetBusy && !resetDone) showAccountReset = false },
            title = { Text("清除账号绑定与本地数据") },
            text = {
                Column {
                    if (resetDone) {
                        Text("已清除登录凭据、绑定关系与本地数据。将返回登录页，请绑定新账号。",
                            fontSize = 14.sp)
                    } else {
                        Text("此操作将清除：Web 登录凭据、中继绑定、设备注册、公告、策略与使用记录。需云端验证账号邮箱与密码（离线时无法清除）。",
                            fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = resetEmail,
                            onValueChange = { resetEmail = it; resetError = null },
                            label = { Text("账号邮箱") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = resetPassword,
                            onValueChange = { resetPassword = it; resetError = null },
                            label = { Text("登录密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        if (resetError != null) Text(resetError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                if (resetDone) {
                    TextButton(onClick = { showAccountReset = false; onAccountReset() }) { Text("返回登录") }
                } else {
                    TextButton(
                        onClick = {
                            if (!resetEmail.contains("@")) { resetError = "请输入有效的账号邮箱"; return@TextButton }
                            if (resetPassword.isEmpty()) { resetError = "请输入登录密码"; return@TextButton }
                            resetBusy = true
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                val result = ParentAccountReset.resetAccount(context, resetEmail, resetPassword)
                                withContext(Dispatchers.Main) {
                                    resetBusy = false
                                    when (result) {
                                        is ParentAccountReset.ResetResult.Success -> resetDone = true
                                        is ParentAccountReset.ResetResult.Failed -> resetError = result.reason
                                    }
                                }
                            }
                        },
                        enabled = !resetBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(if (resetBusy) "清除中…" else "验证并清除") }
                }
            },
            dismissButton = {
                if (!resetDone) {
                    TextButton(onClick = { showAccountReset = false }, enabled = !resetBusy) { Text("取消") }
                }
            }
        )
    }
}

// ==================== 辅助组件 ====================

/**
 * 网页管理入口行：标签 + 可点击地址，点击用系统浏览器打开
 */
@Composable
private fun WebConsoleLinkRow(
        label: String,
        url: String,
        display: String,
        context: android.content.Context
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .clickable { openBrowser(context, url) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp)
            )
            Text(
                display,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

/**
 * 用系统浏览器打开网页管理后台
 */
private fun openBrowser(context: android.content.Context, url: String) {
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开浏览器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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
    // [TASK-ACCOUNT-V1] 读取已保存的 Web JWT（KeyStore 加密存储，读取时解密）
    val webToken = CloudAccountManager.getToken(context)
    if (webToken == null) {
        android.util.Log.w("WebRelay", "web_token 为空")
        return "请先登录 Web 账号获取 Token" to null
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
 * [TASK-PRELAUNCH-PARENT-RESET] 新账号绑定后全量拉取公告并覆盖本地表：
 * GET /api/announcements（Bearer 新账号 JWT）→ 先清空 parent_announcements 再插入，
 * 杜绝旧账号公告残留。父端自建策略（parent_policies）属本地创作数据，
 * 换账号清理时已清空，由新账号重新建立，不做服务端拉取。
 *
 * @return 用户可见的结果提示
 */
internal suspend fun pullAccountAnnouncements(
    context: android.content.Context,
    host: String,
    port: Int
): String {
    val token = CloudAccountManager.getToken(context)
    if (token == null) return "公告同步跳过：未获取到 Token"

    return try {
        val (code, respBody, errBody) = httpGetJson(host, port, "/api/announcements", token)
        if (code in 200..299) {
            val arr = JSONArray(respBody)
            val count = ParentDao.replaceAllAnnouncements(context, arr)
            "已同步新账号公告 $count 条"
        } else {
            "公告同步失败: HTTP $code ${errBody.take(80)}"
        }
    } catch (e: Exception) {
        "公告同步失败: ${e.message}"
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
