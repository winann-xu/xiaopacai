package com.xiaopacai.child.ui.child

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

    var boundEmail by remember { mutableStateOf(CloudAccountManager.getBoundEmail(context)) }
    var loginEmail by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }

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
                    Text("云端账号", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    if (boundEmail != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                            Spacer(Modifier.width(6.dp))
                            Text(boundEmail!!, fontSize = 14.sp, color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("设备已绑定此账号", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("未绑定", fontSize = 14.sp, color = Color(0xFF9E9E9E))
                    }
                }
            }

            if (boundEmail == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("登录家长账号并绑定设备", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = loginEmail,
                            onValueChange = { loginEmail = it; loginError = null },
                            label = { Text("邮箱") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = loginPassword,
                            onValueChange = { loginPassword = it; loginError = null },
                            label = { Text("密码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation()
                        )
                        loginError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (loginEmail.isBlank()) { loginError = "请输入邮箱"; return@Button }
                                if (loginPassword.isBlank()) { loginError = "请输入密码"; return@Button }
                                loginBusy = true
                                loginError = null
                                GlobalScope.launch(Dispatchers.Main) {
                                    val result = withContext(Dispatchers.IO) {
                                        loginAndBindDevice(context, loginEmail.trim(), loginPassword)
                                    }
                                    loginBusy = false
                                    when (result) {
                                        is BindResult.Success -> {
                                            boundEmail = result.email
                                            loginEmail = ""
                                            loginPassword = ""
                                            Toast.makeText(context, "登录并绑定成功", Toast.LENGTH_SHORT).show()
                                        }
                                        is BindResult.Failed -> {
                                            loginError = result.reason
                                        }
                                    }
                                }
                            },
                            enabled = !loginBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (loginBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("登录并绑定")
                        }
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

sealed class BindResult {
    data class Success(val email: String) : BindResult()
    data class Failed(val reason: String) : BindResult()
}

private fun loginAndBindDevice(context: Context, email: String, password: String): BindResult {
    val existing = CloudAccountManager.getBoundEmail(context)
    if (existing != null && existing == email.trim().lowercase()) {
        return BindResult.Failed("此设备已绑定该账号")
    }

    val loginResult = CloudAccountManager.login(context, email, password)
    when (loginResult) {
        is CloudAccountManager.LoginResult.Failed ->
            return BindResult.Failed(loginResult.reason)
        is CloudAccountManager.LoginResult.Success -> {}
    }

    val parentJwt = CloudAccountManager.getToken(context) ?: return BindResult.Failed("登录成功但获取令牌失败")
    val deviceId = CloudSyncService.getDeviceId(context)
    val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    val pairResult = CloudSyncService.generatePairCode(parentJwt)
    when (pairResult) {
        is CloudSyncService.CloudResult.Failed ->
            return BindResult.Failed("获取配对码失败: ${pairResult.reason}")
        is CloudSyncService.CloudResult.Success -> {}
    }
    val pairCode = pairResult.data?.optString("pairCode", "") ?: ""
    if (pairCode.isBlank()) return BindResult.Failed("配对码为空")

    val verifyResult = CloudSyncService.verifyPairCode(parentJwt, pairCode, deviceId, deviceName, "android")
    return when (verifyResult) {
        is CloudSyncService.CloudResult.Success -> BindResult.Success(loginResult.email)
        is CloudSyncService.CloudResult.Failed -> {
            if (verifyResult.reason.contains("403") || verifyResult.reason.contains("已绑定") ||
                verifyResult.reason.contains("device_owned_by_other")) {
                BindResult.Failed("该设备已绑定其它账号，请在 Web 端解绑后再绑定")
            } else {
                BindResult.Failed("绑定失败: ${verifyResult.reason}")
            }
        }
    }
}
