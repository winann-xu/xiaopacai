// [android-tv] 豁免请求页面 — ExemptionRequestScreen
// 小趴菜 TVOS 版 — 孩子在锁屏时可以请求家长额外时间
// 功能：显示请求表单 → 上传到云端 → 家长 APP 审批 → 解锁

package com.xiaopacai.tvos.ui.exemption

import android.os.Bundle
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
import com.xiaopacai.tvos.util.EncryptionHelper

/**
 * 豁免请求 Activity
 * 
 * 使用场景：
 * - 孩子在看电视时被锁定，但还有未看完的内容
 * - 可以请求额外 15/30/60 分钟
 * - 请求发送到云端，家长 APP 审批
 * - 家长批准后，设备自动解锁
 * 
 * 界面元素：
 * - 标题：请求额外时间
 * - 原因选择（预设 + 自定义）
 * - 时长选择（15/30/60 分钟）
 * - 提交按钮
 * - 返回锁屏按钮
 */
class ExemptionRequestActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ExemptionRequestActivityTV"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF1A237E)
            ) {
                ExemptionRequestScreen(
                    onBack = {
                        finish()
                    },
                    onSubmit = { reason, minutes ->
                        // TODO: 发送请求到云端
                        Toast.makeText(
                            this,
                            "已发送请求，等待家长批准（${minutes}分钟）",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onToast = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

// ==================== Compose 豁免请求界面 ====================

@Composable
fun ExemptionRequestScreen(
    onBack: () -> Unit,
    onSubmit: (reason: String, minutes: Int) -> Unit,
    onToast: (String) -> Unit
) {
    var selectedMinutes by remember { mutableIntStateOf(15) }
    var customReason by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableIntStateOf(-1) }

    val presetReasons = listOf(
        "我正在看重要的节目",
        "我要完成作业/学习",
        "我在和别人视频通话",
        "投屏还没完成",
        "其他原因"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // 返回按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回锁屏", fontSize = 20.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 标题
        Text(
            text = "⏰ 请求额外时间",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "向家长申请更多电视时间",
            fontSize = 24.sp,
            color = Color(0xFFB0B0B0),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // ===== 时长选择 =====
        Text(
            text = "选择时长",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            listOf(15, 30, 60).forEach { minutes ->
                DurationButton(
                    minutes = minutes,
                    selected = selectedMinutes == minutes,
                    onClick = { selectedMinutes = minutes }
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // ===== 原因选择 =====
        Text(
            text = "选择原因",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            presetReasons.forEachIndexed { index, reason ->
                PresetReasonCard(
                    reason = reason,
                    selected = selectedPreset == index,
                    onClick = {
                        selectedPreset = index
                        customReason = ""
                    }
                )
            }

            // 自定义原因
            Spacer(modifier = Modifier.height(8.dp))
            CustomReasonInput(
                value = customReason,
                onValueChange = {
                    customReason = it
                    selectedPreset = -1
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // ===== 提交按钮 =====
        Button(
            onClick = {
                val reason = if (selectedPreset >= 0) presetReasons[selectedPreset] else customReason
                if (reason.isNotBlank()) {
                    onSubmit(reason, selectedMinutes)
                } else {
                    onToast("请选择或输入原因")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            )
        ) {
            Text(
                text = "📤 提交请求（${selectedMinutes}分钟）",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 提示文字
        Text(
            text = "⚠ 每天最多可申请 2 次额外时间",
            fontSize = 16.sp,
            color = Color(0xFF9E9E9E),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun DurationButton(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(160.dp)
            .height(100.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF4CAF50) else Color(0xFF2C2C2C)
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$minutes",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "分钟",
                fontSize = 18.sp,
                color = Color(0xFFB0B0B0)
            )
        }
    }
}

@Composable
fun PresetReasonCard(reason: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF1B3A1B) else Color(0xFF2C2C2C)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = reason,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Text("✓", fontSize = 32.sp, color = Color(0xFF4CAF50))
            }
        }
    }
}

@Composable
fun CustomReasonInput(value: String, onValueChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "自定义原因",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF3C3C3C),
                    unfocusedContainerColor = Color(0xFF3C3C3C),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }
    }
}
