package com.xiaopacai.child.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.xiaopacai.child.util.CloudAccountManager
import com.xiaopacai.child.ui.scan.QrScannerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// V2.0.3/V2.0.5: 首页绑定家长账号卡片（未绑定显示登录表单 + 扫码/配对码入口；已绑定显示账号）
@Composable
fun ParentLoginBindCard() {
    val context = LocalContext.current
    // [TASK-V2.0.6-UNBIND-SYNC] 订阅绑定版本号：Web 端解绑清除本地状态后卡片自动回到「未绑定」
    val bindingRevision by CloudAccountManager.bindingRevision.collectAsState()
    var boundEmail by remember(bindingRevision) { mutableStateOf(CloudAccountManager.getBoundEmail(context)) }
    var loginEmail by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var pairCode by remember { mutableStateOf("") }
    var bindBusy by remember { mutableStateOf(false) }
    var bindError by remember { mutableStateOf<String?>(null) }

    // [V2.0.5] 扫码绑定：家长 Web 生成二维码（JSON 含 pairingCode），儿童端扫码解析后调用 bind-with-code
    val qrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val qrText = result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
            val code = try {
                JSONObject(qrText).optString("pairingCode", "")
            } catch (e: Exception) {
                null
            } ?: run {
                bindError = "二维码内容无法识别，请使用家长端 Web 生成的绑定二维码"
                null
            }
            if (!code.isNullOrBlank()) {
                bindBusy = true
                bindError = null
                GlobalScope.launch(Dispatchers.Main) {
                    val result2 = withContext(Dispatchers.IO) { bindWithPairCode(context, code) }
                    bindBusy = false
                    if (result2) {
                        boundEmail = CloudAccountManager.getBoundEmail(context)
                        CloudSyncService.ensureRegistered(context)
                        Toast.makeText(context, "扫码绑定成功", Toast.LENGTH_SHORT).show()
                    } else if (bindError == null) {
                        bindError = "扫码绑定失败"
                    }
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
            if (boundEmail != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "已绑定：$boundEmail",
                        fontSize = 14.sp,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "家长管理请在 Web 端操作（xpc.winann.com）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("绑定家长账号", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                                    CloudSyncService.ensureRegistered(context)
                                    Toast.makeText(context, "登录并绑定成功", Toast.LENGTH_SHORT).show()
                                }
                                is BindResult.Failed -> loginError = result.reason
                            }
                        }
                    },
                    enabled = !loginBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loginBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("登录并绑定")
                }

                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))
                Text("或使用配对码/扫码绑定（家长端 Web → 设备管理 → 扫码绑定）", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pairCode,
                    onValueChange = { pairCode = it; bindError = null },
                    label = { Text("6 位配对码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                bindError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (pairCode.isBlank()) { bindError = "请输入配对码"; return@Button }
                            bindBusy = true
                            bindError = null
                            GlobalScope.launch(Dispatchers.Main) {
                                val ok = withContext(Dispatchers.IO) { bindWithPairCode(context, pairCode.trim()) }
                                bindBusy = false
                                if (ok) {
                                    boundEmail = CloudAccountManager.getBoundEmail(context)
                                    CloudSyncService.ensureRegistered(context)
                                    pairCode = ""
                                    Toast.makeText(context, "配对码绑定成功", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !bindBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("输入配对码绑定")
                    }
                    OutlinedButton(
                        onClick = { bindError = null; qrLauncher.launch(Intent(context, QrScannerActivity::class.java)) },
                        enabled = !bindBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("扫码绑定")
                    }
                }
            }
        }
    }
}

sealed class BindResult {
    data class Success(val email: String) : BindResult()
    data class Failed(val reason: String) : BindResult()
}

/** 家长登录 + 设备绑定：login → generate-code → verify（服务端 ParentOrAdmin 鉴权） */
fun loginAndBindDevice(context: Context, email: String, password: String): BindResult {
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
    // [V2.0.5] 先注册设备拿令牌（未绑定设备匿名注册），再配对绑定，避免绑定后云同步 401
    CloudSyncService.ensureRegistered(context)

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

// [V2.0.5] 儿童端扫码/配对码绑定（设备令牌 + Web 生成的配对码），成功时刷新本地账号状态
private fun bindWithPairCode(context: Context, pairCode: String): Boolean {
    // [V2.0.5] 先注册设备拿令牌，再扫码/配对码绑定
    CloudSyncService.ensureRegistered(context)
    val result = CloudSyncService.bindWithCode(context, pairCode)
    return when (result) {
        is CloudSyncService.CloudResult.Success -> {
            val ownerEmail = result.data?.optString("ownerEmail", "") ?: ""
            if (ownerEmail.isNotBlank()) {
                CloudAccountManager.recordBoundEmail(context, ownerEmail)
            }
            true
        }
        is CloudSyncService.CloudResult.Failed -> {
            if (result.reason.contains("device_owned_by_other") || result.reason.contains("已绑定其它账号")) {
                // 已绑定其它账号：绑定本账号成功后覆盖本地状态
                val email = CloudAccountManager.getBoundEmail(context)
                if (email != null) {
                    // 已是本账号 → 视为成功
                    return true
                }
            }
            false
        }
    }
}
