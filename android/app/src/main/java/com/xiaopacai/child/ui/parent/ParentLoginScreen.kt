package com.xiaopacai.child.ui.parent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.util.CloudAccountManager
import com.xiaopacai.child.util.ParentAccountReset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [TASK-ACCOUNT-V1] 家长登录页（云端账号，替换本地密码登录）
 *
 * 每次进入 / 切回 / 重启家长端都必须经云端邮箱+密码验证：
 * - 首次使用需先在 Web 3.0 家长控制面板注册账号（网页端两步注册）；
 * - 登录成功仅保存 JWT（KeyStore 加密）与账号邮箱，密码不落盘；
 * - 服务器地址首次填写后持久化，供后续门禁验证使用；
 * - [TASK-MILESTONE-V3] 未配置地址时预填生产 HTTPS 域名（xpc.winann.com:443）。
 */

/** [TASK-MILESTONE-V3] 132 信需求 3：未配置服务器地址时的预填值（生产 HTTPS 域名） */
private const val DEFAULT_WEB_HOST = "xpc.winann.com"
private const val DEFAULT_WEB_PORT = 443
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentLoginScreen(
    onLoginSuccess: () -> Unit,
    onSwitchToChild: () -> Unit,
    isFromSwitch: Boolean = false  // 是否从儿童端切换过来
) {
    val context = LocalContext.current

    // [TASK-MILESTONE-V3] 132 信需求 3：服务器地址预填——已保存配置优先；
    // 未配置时预填生产 HTTPS 域名 xpc.winann.com:443，降低首装配置门槛
    val savedHost = CloudAccountManager.getServerHost(context)
    var serverHost by remember { mutableStateOf(savedHost ?: DEFAULT_WEB_HOST) }
    // [TASK-MILESTONE-V3] 需求 15 走查：端口改为文本态，允许清空重输，提交时统一校验
    var portInput by remember {
        mutableStateOf(if (savedHost != null) CloudAccountManager.getServerPort(context).toString() else DEFAULT_WEB_PORT.toString())
    }

    // 账号输入（预填已绑定邮箱）
    var email by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context) ?: "") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // [TASK-MILESTONE-V3] 需求 15 走查：错误按字段归属展示（"host"/"port"/"email"/"password"）
    var errorField by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    val isBound = remember { CloudAccountManager.isBound(context) }

    // [TASK-MILESTONE-V3] 需求 3：登录新账号检测到旧账号残留时的确认（旧账号密码验证后清除）
    var showOldAccountDialog by remember { mutableStateOf(false) }
    var oldEmailInput by remember { mutableStateOf("") }
    var oldPassword by remember { mutableStateOf("") }
    var oldPasswordVisible by remember { mutableStateOf(false) }
    var oldDialogError by remember { mutableStateOf<String?>(null) }
    var oldDialogBusy by remember { mutableStateOf(false) }

    /** 云端登录（登录成功后进入家长端） */
    fun doCloudLogin() {
        // 持久化服务器地址（供后续门禁验证使用）
        val port = portInput.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_WEB_PORT
        CloudAccountManager.saveServerBase(context, serverHost.trim(), port)
        isProcessing = true
        errorMessage = null
        GlobalScope.launch(Dispatchers.IO) {
            val result = CloudAccountManager.login(context, email, password)
            withContext(Dispatchers.Main) {
                isProcessing = false
                when (result) {
                    is CloudAccountManager.LoginResult.Success -> {
                        password = ""
                        onLoginSuccess()
                    }
                    is CloudAccountManager.LoginResult.Failed ->
                        errorMessage = result.reason
                }
            }
        }
    }

    /** 表单校验 + 提交（登录按钮与键盘「完成」共用入口） */
    fun submitLogin() {
        if (isProcessing) return
        errorMessage = null
        errorField = null
        when {
            serverHost.isBlank() -> {
                errorMessage = "请填写 Web 服务地址"
                errorField = "host"
            }
            portInput.toIntOrNull()?.takeIf { it in 1..65535 } == null -> {
                errorMessage = "端口无效（1-65535）"
                errorField = "port"
            }
            email.isBlank() || !email.contains("@") -> {
                errorMessage = "请输入有效的账号邮箱"
                errorField = "email"
            }
            password.isEmpty() -> {
                errorMessage = "请输入登录密码"
                errorField = "password"
            }
            else -> {
                // [TASK-MILESTONE-V3] 需求 3：检测旧账号残留（旧邮箱与本次登录账号不同）
                val newEmail = email.trim().lowercase()
                val boundEmail = CloudAccountManager.getBoundEmail(context)?.lowercase()
                if (boundEmail != null && boundEmail != newEmail) {
                    oldEmailInput = boundEmail
                    oldPassword = ""
                    oldDialogError = null
                    showOldAccountDialog = true
                } else {
                    doCloudLogin()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("家长登录") },
                navigationIcon = {
                    if (isFromSwitch) {
                        IconButton(onClick = onSwitchToChild) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
                // [TASK-MILESTONE-V3] 需求 15 走查：edge-to-edge 下键盘遮挡，补 imePadding
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 图标
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 标题
            Text(
                text = "云端账号登录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isBound)
                    "登录 Web 3.0 账号进入家长端（密码不保存在设备上）"
                else
                    "首次使用请先在 Web 3.0 家长控制面板注册账号，\n再用同一邮箱在此登录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 服务器地址
            OutlinedTextField(
                value = serverHost,
                onValueChange = { serverHost = it; errorMessage = null; errorField = null },
                label = { Text("Web 服务地址") },
                placeholder = { Text("域名（如 xpc.winann.com）或 192.168.x.x") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                isError = errorField == "host"
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = portInput,
                // [TASK-MILESTONE-V3] 需求 15 走查：仅过滤非法字符，允许清空，提交时统一校验
                onValueChange = { v ->
                    portInput = v.filter { it.isDigit() }.take(5)
                    errorMessage = null
                    errorField = null
                },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                isError = errorField == "port"
            )
            Spacer(modifier = Modifier.height(16.dp))

            // [TASK-MILESTONE-V3] 132 信需求 1：已移除「测试期允许 HTTP」开关（HTTPS 已上线，
            // 公网仅 HTTPS；局域网 HTTP 回退保留在 CloudHttp 内，无需用户配置）

            // 账号邮箱
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; errorMessage = null; errorField = null },
                label = { Text("账号邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                isError = errorField == "email"
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 密码
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null; errorField = null },
                label = { Text("登录密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                // [TASK-MILESTONE-V3] 需求 15 走查：键盘「完成」触发登录
                keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                },
                isError = errorField == "password"
            )

            // 错误消息
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 登录按钮（云端验证）
            Button(
                onClick = { submitLogin() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("验证并进入家长端", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 忘记密码引导（云端邮箱验证码重置，见 Web 端登录页）
            Text(
                text = "忘记密码？请在 Web 3.0 登录页用邮箱验证码重置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 切换回儿童端（未登录状态下无家长数据暴露，允许直接返回）
            TextButton(onClick = onSwitchToChild) {
                Text("返回儿童端")
            }
        }
    }

    // [TASK-MILESTONE-V3] 需求 3：检测到旧账号数据，确认后才清除并继续登录新账号
    if (showOldAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!oldDialogBusy) showOldAccountDialog = false },
            title = { Text("检测到旧账号数据") },
            text = {
                Column {
                    Text(
                        "本机已绑定旧账号「$oldEmailInput」，本次登录的新账号与它不同。\n\n" +
                            "继续将清除旧账号数据并绑定新账号，清除范围：\n" +
                            "• 公告、策略、应用分类、使用记录与报告缓存\n" +
                            "• Web 登录凭据（令牌/邮箱）\n" +
                            "• 中继连接配置\n" +
                            "• 本机设备身份（重新生成，旧账号服务器端本机设备记录将同步解绑）\n\n" +
                            "服务器地址配置保留。需旧账号密码验证后继续。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = oldEmailInput,
                        onValueChange = { oldEmailInput = it; oldDialogError = null },
                        label = { Text("旧账号邮箱") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it; oldDialogError = null },
                        label = { Text("旧账号密码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (oldPasswordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        // [TASK-MILESTONE-V3] 需求 15 走查：与主密码框一致的可见性切换
                        trailingIcon = {
                            IconButton(onClick = { oldPasswordVisible = !oldPasswordVisible }) {
                                Icon(
                                    imageVector = if (oldPasswordVisible) Icons.Filled.VisibilityOff
                                        else Icons.Filled.Visibility,
                                    contentDescription = if (oldPasswordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        }
                    )
                    if (oldDialogError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(oldDialogError!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!oldEmailInput.contains("@")) { oldDialogError = "请输入有效的旧账号邮箱"; return@TextButton }
                        if (oldPassword.isEmpty()) { oldDialogError = "请输入旧账号密码"; return@TextButton }
                        oldDialogBusy = true
                        oldDialogError = null
                        GlobalScope.launch(Dispatchers.IO) {
                            // 旧账号密码验证 + 服务端本机解绑 + 本地全清（ParentAccountReset 内部把关）
                            val reset = ParentAccountReset.resetAccount(context, oldEmailInput, oldPassword)
                            withContext(Dispatchers.Main) {
                                when (reset) {
                                    is ParentAccountReset.ResetResult.Success -> {
                                        // 清除成功后继续登录新账号
                                        showOldAccountDialog = false
                                        doCloudLogin()
                                    }
                                    is ParentAccountReset.ResetResult.Failed -> {
                                        oldDialogBusy = false
                                        oldDialogError = reset.reason
                                    }
                                }
                            }
                        }
                    },
                    enabled = !oldDialogBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(if (oldDialogBusy) "验证并清除中…" else "验证并清除，继续登录") }
            },
            dismissButton = {
                TextButton(onClick = { showOldAccountDialog = false }, enabled = !oldDialogBusy) {
                    Text("取消")
                }
            }
        )
    }
}
