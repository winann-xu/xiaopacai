package com.xiaopacai.child.ui.parent

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.data.database.ParentDao
import org.json.JSONObject
import java.util.UUID

/**
 * [TASK-ROLE-P2] 家长端策略配置页
 *
 * 功能：
 * - 每日使用限额（滑杆 30~480 分钟）
 * - 就寝时段（开始/结束时间）
 * - 分类限额（游戏/社交/视频/学习）
 * - 超时处理方式（整机停用/部分 APP 停用/仅提醒）
 * - 保存后可通过 P2P 下发到已连接儿童端
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentPolicyScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 策略状态
    var dailyLimit by remember { mutableIntStateOf(120) }
    var sleepStart by remember { mutableStateOf("21:00") }
    var sleepEnd by remember { mutableStateOf("07:00") }
    var gameLimit by remember { mutableIntStateOf(60) }
    var socialLimit by remember { mutableIntStateOf(90) }
    var videoLimit by remember { mutableIntStateOf(120) }
    var studyUnlimited by remember { mutableStateOf(true) }
    var stopMode by remember { mutableStateOf("full") }  // full / partial / none
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<String?>(null) }

    // 加载现有策略
    LaunchedEffect(Unit) {
        try {
            val policies = ParentDao.getPolicies(context)
            for (i in 0 until policies.length()) {
                val p = policies.getJSONObject(i)
                when (p.optString("policyType")) {
                    "daily_limit" -> {
                        val data = p.optJSONObject("policyData")
                        if (data != null) dailyLimit = data.optInt("limitMinutes", 120)
                    }
                    "sleep_time" -> {
                        val data = p.optJSONObject("policyData")
                        if (data != null) {
                            sleepStart = data.optString("startTime", "21:00")
                            sleepEnd = data.optString("endTime", "07:00")
                        }
                    }
                    "category_limit" -> {
                        val data = p.optJSONObject("policyData")
                        if (data != null) {
                            when (p.optString("policyName")) {
                                "游戏限额" -> gameLimit = data.optInt("limitMinutes", 60)
                                "社交限额" -> socialLimit = data.optInt("limitMinutes", 90)
                                "视频限额" -> videoLimit = data.optInt("limitMinutes", 120)
                            }
                        }
                    }
                    "stop_mode" -> {
                        stopMode = p.optJSONObject("policyData")?.optString("mode", "full") ?: "full"
                    }
                }
            }
        } catch (_: Exception) {}
        isLoading = false
    }

    // 保存策略
    fun saveAllPolicies() {
        isSaving = true
        saveResult = null
        try {
            // 1. 每日限额
            ParentDao.savePolicy(
                context, null, "daily_limit", "每日限额",
                JSONObject().put("limitMinutes", dailyLimit)
            )
            // 2. 就寝时段
            ParentDao.savePolicy(
                context, null, "sleep_time", "就寝时段",
                JSONObject().put("startTime", sleepStart).put("endTime", sleepEnd)
            )
            // 3. 游戏限额
            ParentDao.savePolicy(
                context, null, "category_limit", "游戏限额",
                JSONObject().put("category", "game").put("limitMinutes", gameLimit)
            )
            // 4. 社交限额
            ParentDao.savePolicy(
                context, null, "category_limit", "社交限额",
                JSONObject().put("category", "social").put("limitMinutes", socialLimit)
            )
            // 5. 视频限额
            ParentDao.savePolicy(
                context, null, "category_limit", "视频限额",
                JSONObject().put("category", "video").put("limitMinutes", videoLimit)
            )
            // 6. 超时处理方式
            ParentDao.savePolicy(
                context, null, "stop_mode", "超时处理",
                JSONObject().put("mode", stopMode)
            )
            saveResult = "策略已保存" + if (ParentP2PListenerService.isRunning) "（已连接设备将自动同步）" else ""
        } catch (e: Exception) {
            saveResult = "保存失败: ${e.message}"
        }
        isSaving = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("策略配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { saveAllPolicies() },
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 保存结果提示
                saveResult?.let {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (it.startsWith("策略已保存"))
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 14.sp
                        )
                    }
                }

                // 1. 每日使用限额
                PolicySection(title = "每日使用限额", icon = Icons.Filled.Timer) {
                    Text(
                        text = "每天最多使用 $dailyLimit 分钟",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = dailyLimit.toFloat(),
                        onValueChange = { dailyLimit = it.toInt() },
                        valueRange = 30f..480f,
                        steps = 44  // 每 10 分钟一步
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("30分钟", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("480分钟", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 2. 就寝时段
                PolicySection(title = "就寝时段", icon = Icons.Filled.Bedtime) {
                    Text(
                        text = "就寝时段内设备将自动停用",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = sleepStart,
                            onValueChange = { sleepStart = it },
                            label = { Text("开始") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Text("—", fontSize = 20.sp)
                        OutlinedTextField(
                            value = sleepEnd,
                            onValueChange = { sleepEnd = it },
                            label = { Text("结束") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }

                // 3. 分类限额
                PolicySection(title = "分类限额", icon = Icons.Filled.Category) {
                    CategoryLimitRow("🎮 游戏", gameLimit, 0..300) { gameLimit = it }
                    CategoryLimitRow("💬 社交", socialLimit, 0..300) { socialLimit = it }
                    CategoryLimitRow("🎬 视频", videoLimit, 0..300) { videoLimit = it }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Checkbox(
                            checked = studyUnlimited,
                            onCheckedChange = { studyUnlimited = it }
                        )
                        Text("📚 学习类应用不限时", fontSize = 14.sp)
                    }
                }

                // 4. 超时处理方式
                PolicySection(title = "超时处理方式", icon = Icons.Filled.Block) {
                    Text(
                        text = "超过每日限额后执行的操作",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val stopModes = listOf(
                        Triple("full", "整机停用", "非白名单应用全部不可用，仅保留紧急呼叫与家长豁免入口"),
                        Triple("partial", "部分 APP 停用", "仅娱乐类/指定应用被停用，学习类可继续使用"),
                        Triple("none", "仅提醒", "不强制停用，仅在守护页面显示超时提醒")
                    )

                    stopModes.forEach { (mode, title, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = stopMode == mode,
                                onClick = { stopMode = mode }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * 策略配置区块容器
 */
@Composable
private fun PolicySection(
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
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * 分类限额行（标签 + 滑杆 + 数值）
 */
@Composable
private fun CategoryLimitRow(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp)
            Text("${value}分钟", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
}
