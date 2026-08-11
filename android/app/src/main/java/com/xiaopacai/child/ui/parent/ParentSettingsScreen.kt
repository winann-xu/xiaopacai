package com.xiaopacai.child.ui.parent

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.data.database.ParentDao
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.role.RoleManager
import com.xiaopacai.child.XiaopacaiApp

/**
 * [TASK-ROLE-P2] 家长端设置页
 *
 * 功能：
 * - 修改家长密码
 * - P2P 监听端口配置
 * - 服务状态（启动/停止）
 * - 数据清除（重置所有数据）
 * - 关于信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 密码修改状态
    var showChangePassword by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    // 端口
    var port by remember { mutableIntStateOf(9527) }

    // P2P 服务状态
    var isServiceRunning by remember { mutableStateOf(ParentP2PListenerService.isRunning) }

    // 数据清除确认
    var showClearData by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === 家长密码 ===
            SettingsSection(title = "家长密码", icon = Icons.Filled.Lock) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("修改家长密码", fontSize = 14.sp)
                    TextButton(onClick = { showChangePassword = !showChangePassword }) {
                        Text(if (showChangePassword) "取消" else "修改")
                    }
                }

                if (showChangePassword) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = oldPassword,
                            onValueChange = { oldPassword = it; passwordError = null },
                            label = { Text("当前密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it; passwordError = null },
                            label = { Text("新密码（6-16位）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it; passwordError = null },
                            label = { Text("确认新密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            isError = passwordError != null
                        )
                        if (passwordError != null) {
                            Text(passwordError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                when {
                                    oldPassword.isEmpty() || newPassword.isEmpty() ->
                                        passwordError = "请填写所有字段"
                                    newPassword != confirmPassword ->
                                        passwordError = "两次密码不一致"
                                    !RoleManager.isValidPasswordFormat(newPassword) ->
                                        passwordError = "密码格式不正确（6-16位数字或字母）"
                                    !RoleManager.changeParentPassword(context, oldPassword, newPassword) ->
                                        passwordError = "当前密码错误"
                                    else -> {
                                        showChangePassword = false
                                        oldPassword = ""
                                        newPassword = ""
                                        confirmPassword = ""
                                        Toast.makeText(context, "密码已修改", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("确认修改")
                        }
                    }
                }
            }

            // === P2P 服务 ===
            SettingsSection(title = "P2P 监听服务", icon = Icons.Filled.Wifi) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("服务状态", fontSize = 14.sp)
                        Text(
                            text = if (isServiceRunning) "● 运行中（端口 $port）" else "○ 已停止",
                            fontSize = 12.sp,
                            color = if (isServiceRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = { enable ->
                            if (enable) {
                                ParentP2PListenerService.start(context)
                                isServiceRunning = true
                            } else {
                                ParentP2PListenerService.stop(context)
                                isServiceRunning = false
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { if (it in 1024..65535) port = it }
                    },
                    label = { Text("监听端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isServiceRunning
                )
                Text(
                    text = "证书指纹: ${try { ParentP2PListenerService.instance?.getCertificateFingerprint()?.take(40) ?: "不可用" } catch (_: Exception) { "不可用" }}…",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // === 数据管理 ===
            SettingsSection(title = "数据管理", icon = Icons.Filled.Storage) {
                Button(
                    onClick = { showClearData = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清除全部数据")
                }
                Text(
                    text = "清除所有策略、公告、使用记录和设备配对信息。此操作不可撤销。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // === 关于 ===
            SettingsSection(title = "关于", icon = Icons.Filled.Info) {
                InfoRow("应用版本", "小趴菜 2.1（双角色版）")
                InfoRow("数据库", "SQLCipher AES-256 加密")
                InfoRow("P2P 协议", "TLS 1.3/1.2 + JSON 帧")
                InfoRow("家长端角色", "支持设备管理/策略/公告/报告")
                InfoRow("开源协议", "Apache-2.0")
            }
        }
    }

    // 数据清除确认
    if (showClearData) {
        var confirmText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showClearData = false; confirmText = "" },
            title = { Text("清除全部数据") },
            text = {
                Column {
                    Text("此操作将永久删除所有家长端数据，包括：\n" +
                        "• 已配对设备\n• 策略配置\n• 公告\n• 使用报告\n\n" +
                        "此操作不可撤销！",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("请输入「确认删除」以继续：", fontSize = 13.sp)
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("确认删除") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (confirmText == "确认删除") {
                            try {
                                val db = XiaopacaiApp.instance.database.getWritable(
                                    com.xiaopacai.child.util.DbPassphraseProvider.getPassphrase(context)
                                )
                                db.execSQL("DELETE FROM device_registry")
                                db.execSQL("DELETE FROM parent_policies")
                                db.execSQL("DELETE FROM parent_announcements")
                                db.execSQL("DELETE FROM parent_usage_summary")
                                db.close()
                                Toast.makeText(context, "数据已清除", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            showClearData = false
                            confirmText = ""
                        }
                    },
                    enabled = confirmText == "确认删除",
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearData = false; confirmText = "" }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 设置区块容器
 */
@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp)
    }
}
