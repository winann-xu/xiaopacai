package com.xiaopacai.child.ui.parent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
 * - 服务器地址首次填写后持久化，供后续门禁验证使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentLoginScreen(
    onLoginSuccess: () -> Unit,
    onSwitchToChild: () -> Unit,
    isFromSwitch: Boolean = false  // 是否从儿童端切换过来
) {
    val context = LocalContext.current

    // 服务器地址（优先取已保存配置）
    var serverHost by remember { mutableStateOf(CloudAccountManager.getServerHost(context) ?: "") }
    var serverPort by remember { mutableIntStateOf(CloudAccountManager.getServerPort(context)) }

    // 账号输入（预填已绑定邮箱）
    var email by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context) ?: "") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    val isBound = remember { CloudAccountManager.isBound(context) }

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
                .verticalScroll(rememberScrollState()),
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
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isBound)
                    "登录 Web 3.0 账号进入家长端（密码不保存在设备上）"
                else
                    "首次使用请先在 Web 3.0 家长控制面板注册账号，\n再用同一邮箱在此登录",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 服务器地址
            OutlinedTextField(
                value = serverHost,
                onValueChange = { serverHost = it; errorMessage = null },
                label = { Text("Web 服务地址") },
                placeholder = { Text("192.168.x.x 或域名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = serverPort.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.takeIf { it in 1..65535 }?.let { serverPort = it }
                },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 账号邮箱
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; errorMessage = null },
                label = { Text("账号邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                isError = errorMessage != null
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 密码
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("登录密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                },
                isError = errorMessage != null
            )

            // 错误消息
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 登录按钮（云端验证）
            Button(
                onClick = {
                    isProcessing = true
                    errorMessage = null

                    when {
                        serverHost.isBlank() -> {
                            errorMessage = "请填写 Web 服务地址"
                            isProcessing = false
                        }
                        email.isBlank() || !email.contains("@") -> {
                            errorMessage = "请输入有效的账号邮箱"
                            isProcessing = false
                        }
                        password.isEmpty() -> {
                            errorMessage = "请输入登录密码"
                            isProcessing = false
                        }
                        else -> {
                            // 持久化服务器地址（供后续门禁验证使用）
                            CloudAccountManager.saveServerBase(context, serverHost.trim(), serverPort)
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
                    }
                },
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
                    Text("验证并进入家长端", fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 忘记密码引导（云端邮箱验证码重置，见 Web 端登录页）
            Text(
                text = "忘记密码？请在 Web 3.0 登录页用邮箱验证码重置",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 切换回儿童端（未登录状态下无家长数据暴露，允许直接返回）
            TextButton(onClick = onSwitchToChild) {
                Text("返回儿童端")
            }
        }
    }
}
