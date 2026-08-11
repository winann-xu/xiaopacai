package com.xiaopacai.child.ui.parent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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
import com.xiaopacai.child.role.RoleManager

/**
 * [TASK-ROLE-P1] 家长登录页
 *
 * 三种模式：
 * 1. 首次设置密码：无密码时引导设置 6-16 位密码
 * 2. 密码登录：已有密码时输入密码登录
 * 3. 角色切换登录：从儿童端切到家长端时输入密码
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentLoginScreen(
    onLoginSuccess: () -> Unit,
    onSwitchToChild: () -> Unit,
    isFromSwitch: Boolean = false  // 是否从儿童端切换过来
) {
    val context = LocalContext.current
    val isPasswordSet = remember { RoleManager.isParentPasswordSet(context) }

    // 密码输入状态
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    // 是否在"设置新密码"模式
    val isSetupMode = !isPasswordSet

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isSetupMode) "设置家长密码" else "家长登录"
                    )
                },
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
                .padding(horizontal = 32.dp),
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
                text = if (isSetupMode) "请设置家长密码" else "请输入家长密码",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isSetupMode) "密码用于切换角色和修改设置\n6-16位数字或字母"
                else "请输入家长密码以进入家长端",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 密码输入框
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                label = { Text(if (isSetupMode) "密码" else "家长密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isSetupMode) ImeAction.Next else ImeAction.Done
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

            // 设置模式下需要确认密码
            if (isSetupMode) {
                Spacer(modifier = Modifier.height(16.dp))

                var confirmVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
                    },
                    label = { Text("确认密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (confirmVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { confirmVisible = !confirmVisible }) {
                            Icon(
                                imageVector = if (confirmVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                contentDescription = if (confirmVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    isError = errorMessage != null
                )
            }

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

            // 登录/设置按钮
            Button(
                onClick = {
                    isProcessing = true
                    errorMessage = null

                    if (isSetupMode) {
                        // 设置密码模式
                        when {
                            password.isEmpty() || confirmPassword.isEmpty() -> {
                                errorMessage = "请填写密码和确认密码"
                                isProcessing = false
                            }
                            password != confirmPassword -> {
                                errorMessage = "两次输入的密码不一致"
                                isProcessing = false
                            }
                            !RoleManager.isValidPasswordFormat(password) -> {
                                errorMessage = "密码格式不符合要求（6-16位数字或字母）"
                                isProcessing = false
                            }
                            else -> {
                                val success = RoleManager.setParentPassword(context, password)
                                if (success) {
                                    onLoginSuccess()
                                } else {
                                    errorMessage = "密码设置失败，请重试"
                                }
                                isProcessing = false
                            }
                        }
                    } else {
                        // 登录验证模式
                        if (password.isEmpty()) {
                            errorMessage = "请输入密码"
                            isProcessing = false
                        } else {
                            val success = RoleManager.verifyParentPassword(context, password)
                            if (success) {
                                onLoginSuccess()
                            } else {
                                errorMessage = "密码错误，请重试"
                            }
                            isProcessing = false
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
                    Text(
                        text = if (isSetupMode) "设置密码并进入" else "登录",
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 切换回儿童端（始终可用，避免因忘记密码而被锁在登录页）
            TextButton(onClick = onSwitchToChild) {
                Text("返回儿童端")
            }
        }
    }
}
