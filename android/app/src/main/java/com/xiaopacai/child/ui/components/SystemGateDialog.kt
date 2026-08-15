package com.xiaopacai.child.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.util.CloudAccountManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [TASK-ACCOUNT-V1] 系统级家长验证门禁（统一对话框）
 *
 * 所有涉及守护设置的敏感操作（儿童端守护设置/权限管理/应用分类/解除保护、
 * 家长端切换到儿童端/换账号清理）统一经此对话框：
 * - 每次输入账号邮箱 + 登录密码，云端验证（POST /api/auth/login）；
 * - 密码不落盘，仅刷新本地 JWT 与账号邮箱；
 * - 离线时验证失败并提示「需要联网」（离线语义见 ADR 0009）。
 */
@Composable
fun SystemGateDialog(
    title: String,
    description: String,
    confirmText: String = "验证",
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context) ?: "") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun close() {
        if (!busy) {
            password = ""
            error = null
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { close() },
        title = { Text(title) },
        text = {
            Column {
                Text(
                    description + "\n验证需要联网，验证通过后 5 分钟内有效（会话内）。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    label = { Text("账号邮箱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("登录密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (email.isBlank() || !email.contains("@")) { error = "请输入有效的账号邮箱"; return@TextButton }
                    if (password.isEmpty()) { error = "请输入登录密码"; return@TextButton }
                    busy = true
                    error = null
                    // 云端验证（网络 IO）
                    GlobalScope.launch(Dispatchers.IO) {
                        val result = CloudAccountManager.login(context, email, password)
                        withContext(Dispatchers.Main) {
                            busy = false
                            when (result) {
                                is CloudAccountManager.LoginResult.Success -> {
                                    password = ""
                                    onVerified()
                                }
                                is CloudAccountManager.LoginResult.Failed -> error = result.reason
                            }
                        }
                    }
                }
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("验证中…")
                } else {
                    Text(confirmText)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { close() }, enabled = !busy) { Text("取消") }
        }
    )
}
