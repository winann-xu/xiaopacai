package com.xiaopacai.child.ui.parent

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.role.RoleManager

/**
 * [TASK-ROLE-P1] 家长端主页（P1 占位）
 *
 * P1 阶段：显示家长端功能入口和基本状态。
 * P2 阶段：实现完整的设备管理/策略配置/公告管理/使用报告/设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    onSwitchToChild: (String) -> Unit,  // 传入密码进行切换
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var showSwitchDialog by remember { mutableStateOf(false) }
    var switchPassword by remember { mutableStateOf("") }
    var switchError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("家长端") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // 切换到儿童端
                    IconButton(onClick = { showSwitchDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = "切换角色"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 欢迎区
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "家长控制面板",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "管理孩子的设备使用、查看报告、下发策略",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // 功能卡片网格
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 设备管理
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Devices,
                    title = "设备管理",
                    subtitle = "配对与监控",
                    enabled = true
                )

                // 策略配置
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Tune,
                    title = "策略配置",
                    subtitle = "限额与规则",
                    enabled = false
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 公告管理
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Campaign,
                    title = "公告管理",
                    subtitle = "发布与推送",
                    enabled = false
                )

                // 使用报告
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Assessment,
                    title = "使用报告",
                    subtitle = "日报与趋势",
                    enabled = false
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 设置
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Settings,
                    title = "设置",
                    subtitle = "密码与端口",
                    enabled = false
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 底部状态
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "P2P 监听服务",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "端口 9527 | 等待儿童端连接...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // 角色切换对话框
    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = {
                showSwitchDialog = false
                switchPassword = ""
                switchError = null
            },
            title = { Text("切换到儿童端") },
            text = {
                Column {
                    Text(
                        text = "请输入家长密码以切换角色",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = switchPassword,
                        onValueChange = {
                            switchPassword = it
                            switchError = null
                        },
                        label = { Text("家长密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = switchError != null
                    )
                    if (switchError != null) {
                        Text(
                            text = switchError!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (switchPassword.isEmpty()) {
                            switchError = "请输入密码"
                        } else if (RoleManager.verifyParentPassword(context, switchPassword)) {
                            showSwitchDialog = false
                            onSwitchToChild(switchPassword)
                        } else {
                            switchError = "密码错误"
                        }
                    }
                ) {
                    Text("确认切换")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSwitchDialog = false
                    switchPassword = ""
                    switchError = null
                }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 家长端功能入口卡片
 */
@Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun FeatureCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        onClick = { /* P2 实现 */ }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(36.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                text = if (enabled) subtitle else "即将上线",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}
