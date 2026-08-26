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
import com.xiaopacai.child.service.EmergencyReleaseService
import com.xiaopacai.child.util.CloudAccountManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ParentAuthDialog(
    title: String = "家长验证",
    description: String = "此操作需要家长密码验证",
    confirmText: String = "验证",
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var useCloud by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context) != null) }
    var email by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context) ?: "") }

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
                    description + if (useCloud) "\n验证需要联网。" else "",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))
                if (useCloud) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = null },
                        label = { Text("账号邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = error != null
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("家长密码") },
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
                    if (password.isEmpty()) { error = "请输入密码"; return@TextButton }
                    if (useCloud && email.isBlank()) { error = "请输入邮箱"; return@TextButton }
                    busy = true
                    error = null
                    GlobalScope.launch(Dispatchers.IO) {
                        val result = if (useCloud) {
                            val loginResult = CloudAccountManager.login(context, email, password)
                            when (loginResult) {
                                is CloudAccountManager.LoginResult.Success ->
                                    EmergencyReleaseService.PasswordResult.Success
                                is CloudAccountManager.LoginResult.Failed ->
                                    EmergencyReleaseService.PasswordResult.Incorrect(loginResult.reason)
                            }
                        } else {
                            EmergencyReleaseService.verifyPassword(context, password)
                        }
                        withContext(Dispatchers.Main) {
                            busy = false
                            when (result) {
                                is EmergencyReleaseService.PasswordResult.Success -> {
                                    password = ""
                                    onVerified()
                                }
                                is EmergencyReleaseService.PasswordResult.Incorrect ->
                                    error = result.reason
                                is EmergencyReleaseService.PasswordResult.LockedOut ->
                                    error = result.message
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
