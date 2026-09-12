// [android-tv] 就寝时段/睡眠定时器 — BedtimeScreen
// 小趴菜 TVOS 版 — 设置就寝时间和睡眠定时器
// 功能：设定就寝时间 → 定时自动锁定 → 就寝提醒

package com.xiaopacai.tvos.ui.bedtime

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

/**
 * 就寝时段 Activity
 * 
 * 功能：
 * 1. 设置就寝开始时间（如 21:00）
 * 2. 设置就寝结束时间（如 07:00）
 * 3. 设置睡眠定时器（如 30 分钟后锁定）
 * 4. 就寝提醒（开始前 10 分钟提醒）
 * 5. 就寝时段自动禁用电视（除白名单）
 * 
 * 使用场景：
 * - 家长设置孩子就寝时间，防止熬夜看电视
 * - 睡眠定时器帮助孩子在规定时间内入睡
 */
class BedtimeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BedtimeActivityTV"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0D1B2A)
            ) {
                BedtimeScreen(
                    onBack = { finish() },
                    onSave = {
                        Toast.makeText(this, "就寝时间已设置 ✓", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

// ==================== Compose 就寝时段界面 ====================

@Composable
fun BedtimeScreen(
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    var bedtimeStart by remember { mutableStateOf("21:00") }
    var bedtimeEnd by remember { mutableStateOf("07:00") }
    var timerMinutes by remember { mutableStateOf("30") }
    var enableRemind by remember { mutableStateOf(true) }
    var remindBefore by remember { mutableStateOf("10") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🌙 就寝时段",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF9C27B0)
            )
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
            ) {
                Text("← 返回", fontSize = 20.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "设定孩子的就寝时间，帮助养成良好习惯",
            fontSize = 20.sp,
            color = Color(0xFF888888)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // ===== 就寝时段设置 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(32.dp)) {
                Text(
                    text = "🌙 就寝时段",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFCE93D8),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // 开始时间
                TimePickerField(
                    label = "就寝开始",
                    value = bedtimeStart,
                    onValueChange = { bedtimeStart = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 结束时间
                TimePickerField(
                    label = "就寝结束",
                    value = bedtimeEnd,
                    onValueChange = { bedtimeEnd = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "💡 就寝时段内，电视自动锁定（白名单应用除外）",
                    fontSize = 16.sp,
                    color = Color(0xFF888888)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ===== 睡眠定时器 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(32.dp)) {
                Text(
                    text = "⏰ 睡眠定时器",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF90CAF9),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Text(
                    text = "电视开启后自动计时，到达设定时间自动锁定",
                    fontSize = 16.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = timerMinutes,
                        onValueChange = {
                            if (it.all { c -> c.isDigit() } && it.length <= 3) {
                                timerMinutes = it
                            }
                        },
                        modifier = Modifier.width(150.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF2C2C2C),
                            unfocusedContainerColor = Color(0xFF2C2C2C),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Text("分钟后自动锁定", fontSize = 20.sp, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ===== 就寝提醒 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(32.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔔 就寝提醒",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFAB40)
                    )
                    Switch(
                        checked = enableRemind,
                        onCheckedChange = { enableRemind = it }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (enableRemind) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("就寝前", fontSize = 20.sp, color = Color.White)
                        TextField(
                            value = remindBefore,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() } && it.length <= 2) {
                                    remindBefore = it
                                }
                            },
                            modifier = Modifier.width(100.dp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2C2C2C),
                                unfocusedContainerColor = Color(0xFF2C2C2C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Text("分钟提醒", fontSize = 20.sp, color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // ===== 保存按钮 =====
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF9C27B0)
            )
        ) {
            Text("💾 保存就寝设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 当前状态提示
        CurrentBedtimeStatus(
            bedtimeStart = bedtimeStart,
            bedtimeEnd = bedtimeEnd
        )
    }
}

@Composable
fun TimePickerField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(
            text = label,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFB0B0B0),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF2C2C2C),
                unfocusedContainerColor = Color(0xFF2C2C2C),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
    }
}

@Composable
fun CurrentBedtimeStatus(bedtimeStart: String, bedtimeEnd: String) {
    val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    val isActive = isTimeInRange(currentTime, bedtimeStart, bedtimeEnd)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF1B3A1B) else Color(0xFF2C2C2C)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (isActive) "🌙 就寝时段中" else "☀️ 非就寝时段",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) Color(0xFFCE93D8) else Color.White
                )
                Text(
                    text = "$bedtimeStart - $bedtimeEnd",
                    fontSize = 18.sp,
                    color = Color(0xFF888888)
                )
            }
            Text(
                text = "当前: $currentTime",
                fontSize = 18.sp,
                color = Color(0xFF888888)
            )
        }
    }
}

/**
 * 检查当前时间是否在就寝时段范围内
 * 简化实现：支持跨天（如 21:00-07:00）
 */
fun isTimeInRange(currentTime: String, start: String, end: String): Boolean {
    val currentMinutes = timeToMinutes(currentTime)
    val startMinutes = timeToMinutes(start)
    val endMinutes = timeToMinutes(end)

    return when {
        startMinutes <= endMinutes -> {
            // 不跨天：如 08:00-17:00
            currentMinutes in startMinutes..endMinutes
        }
        else -> {
            // 跨天：如 21:00-07:00
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }
}

fun timeToMinutes(time: String): Int {
    val parts = time.split(":")
    val hours = parts[0].toIntOrNull() ?: 0
    val minutes = parts[1].toIntOrNull() ?: 0
    return hours * 60 + minutes
}
