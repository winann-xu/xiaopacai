package com.xiaopacai.child.ui.child

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.service.CloudSyncService
import com.xiaopacai.child.service.EmergencyReleaseService
import com.xiaopacai.child.util.CloudAccountManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EmergencyReleaseDialog(
    onDismiss: () -> Unit,
    onReleased: () -> Unit
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    // [TASK-V208-UNBIND-FIX] 解绑后无归属邮箱：显示邮箱输入框供家长完成云端验证
    var email by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(1) }
    var remainingMinutes by remember { mutableStateOf(0) }
    val boundEmail = CloudAccountManager.getBoundEmail(context)

    val isActive = EmergencyReleaseService.isActive(context)
    if (isActive) {
        remainingMinutes = EmergencyReleaseService.getRemainingMinutes(context)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                if (isActive) "紧急解除中" else "家长紧急解除",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (isActive) {
                Column {
                    Text(
                        "守护已临时解除，剩余 ${remainingMinutes} 分钟后自动恢复。",
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = remainingMinutes.toFloat() / EmergencyReleaseService.DEFAULT_DURATION_MINUTES,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color(0xFFE53935),
                        trackColor = Color(0xFFFFCDD2)
                    )
                }
            } else {
                Column {
                    Text(
                        "紧急解除将临时停止守护管控 ${EmergencyReleaseService.DEFAULT_DURATION_MINUTES} 分钟，" +
                            "超时后自动恢复。需要家长密码验证。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    if (boundEmail == null) {
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
            }
        },
        confirmButton = {
            if (!isActive) {
                TextButton(
                    enabled = !busy && password.isNotEmpty(),
                    onClick = {
                        if (boundEmail == null && email.isBlank()) {
                            error = "请输入账号邮箱"
                            return@TextButton
                        }
                        busy = true
                        error = null
                        GlobalScope.launch(Dispatchers.IO) {
                            val result = EmergencyReleaseService.verifyPassword(
                                context, password, email
                            )
                            withContext(Dispatchers.Main) {
                                busy = false
                                when (result) {
                                    is EmergencyReleaseService.PasswordResult.Success -> {
                                        EmergencyReleaseService.activate(context)
                                        onReleased()
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
                        Text("紧急解除", color = Color(0xFFE53935))
                    }
                }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(if (isActive) "关闭" else "取消")
            }
        }
    )
}
