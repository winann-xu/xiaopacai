package com.xiaopacai.child.ui.child

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.xiaopacai.child.util.KeyStoreManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showChangePassword by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var passwordBusy by remember { mutableStateOf(false) }

    val bindCode = CloudSyncService.getBindCode(context) ?: "未绑定"
    val boundEmail = CloudAccountManager.getBoundEmail(context) ?: "未登录"

    if (showChangePassword) {
        AlertDialog(
            onDismissRequest = { if (!passwordBusy) showChangePassword = false },
            title = { Text("修改家长密码") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; passwordError = null },
                        label = { Text("新密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; passwordError = null },
                        label = { Text("确认密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    passwordError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !passwordBusy,
                    onClick = {
                        if (newPassword.length < 6) { passwordError = "密码至少6位"; return@TextButton }
                        if (newPassword != confirmPassword) { passwordError = "两次密码不一致"; return@TextButton }
                        passwordBusy = true
                        EmergencyReleaseService.setPassword(context, newPassword)
                        passwordBusy = false
                        showChangePassword = false
                        newPassword = ""
                        confirmPassword = ""
                        Toast.makeText(context, "密码已修改", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showChangePassword = false }, enabled = !passwordBusy) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号与安全", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("绑定码", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(bindCode, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = if (bindCode != "未绑定") Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                    Spacer(Modifier.height(4.dp))
                    Text("在 Web 管理控制台输入此绑定码绑定设备", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("云端账号", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(boundEmail, fontSize = 14.sp,
                        color = if (boundEmail != "未登录") Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showChangePassword = true }) {
                        Text("修改家长密码")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("通知设置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("安全告警通知", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = true, onCheckedChange = { /* always on */ })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("守护状态通知", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = true, onCheckedChange = { /* always on */ })
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
