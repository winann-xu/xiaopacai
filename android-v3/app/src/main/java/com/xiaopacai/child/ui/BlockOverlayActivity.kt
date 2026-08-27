package com.xiaopacai.child.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.service.EmergencyReleaseService
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.ui.theme.XiaopacaiTheme
import kotlinx.coroutines.delay

/**
 * [TASK-D3-04] 应用拦截全屏覆盖界面 — 成品级打磨
 *
 * 当前台应用被拦截时，通过无障碍服务启动此 Activity 覆盖屏幕。
 * 品牌红色主题 + 渐变背景 + 缩放动画 + 呼吸提示。
 *
 * 动画流程：锁图标从大到小弹入 → 信息卡片滑入 → 按钮依次淡入
 */
class BlockOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 在锁屏上显示（紧急电话场景可用）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 获取被拦截信息
        val targetPackage = intent.getStringExtra("target_package") ?: "未知应用"
        val reason = intent.getStringExtra("reason") ?: "使用时长已超限"

        // 获取当前使用状态
        val collector = GuardianForegroundService.getCollector()
        val usedMinutes = collector?.todayTotalMinutes?.toInt() ?: 0
        val limitMinutes = collector?.todayLimitMinutes?.toInt() ?: 120

        setContent {
            var emergencyReleaseDialog by remember { mutableStateOf(false) }
            XiaopacaiTheme(darkTheme = false) {
                BlockOverlayScreen(
                    targetPackage = targetPackage,
                    reason = reason,
                    usedMinutes = usedMinutes,
                    limitMinutes = limitMinutes,
                    onGoHome = { finishAndGoHome() },
                    onEmergencyCall = { makeEmergencyCall() },
                    onEmergencyRelease = { emergencyReleaseDialog = true }
                )
                if (emergencyReleaseDialog) {
                    com.xiaopacai.child.ui.child.EmergencyReleaseDialog(
                        onDismiss = { emergencyReleaseDialog = false },
                        onReleased = {
                            emergencyReleaseDialog = false
                            finishAndGoHome()
                        }
                    )
                }
            }
        }
    }

    private fun finishAndGoHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(homeIntent) } catch (_: Exception) {}
        finish()
    }

    private fun makeEmergencyCall() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:110")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) } catch (_: Exception) {}
        }
    }

    override fun onBackPressed() {
        finishAndGoHome()
    }
}

// ==================== Compose UI — 品牌红色锁屏 ====================

/** 品牌红渐变色 */
private val BlockGradientColors = listOf(
    Color(0xFFC62828),  // 深红
    Color(0xFFD32F2F),  // 品牌红
    Color(0xFFE53935),  // 亮红
)

@Composable
private fun BlockOverlayScreen(
    targetPackage: String,
    reason: String,
    usedMinutes: Int,
    limitMinutes: Int,
    onGoHome: () -> Unit,
    onEmergencyCall: () -> Unit,
    onEmergencyRelease: () -> Unit = {}
) {
    // [TASK-D3-04] 入场动画状态
    var iconVisible by remember { mutableStateOf(false) }
    var cardVisible by remember { mutableStateOf(false) }
    var buttonsVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        iconVisible = true
        delay(400)
        cardVisible = true
        delay(300)
        buttonsVisible = true
    }

    // [REQ] 超时/就寝解除后自动收起锁屏，避免“额度已重置/就寝结束但锁屏还挂着”
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val collector = GuardianForegroundService.getCollector()
            if (collector != null && !collector.isTimeoutActive) {
                onGoHome()
                return@LaunchedEffect
            }
            if (EmergencyReleaseService.isActive(context)) {
                onGoHome()
                return@LaunchedEffect
            }
        }
    }

    // icon scale（回弹效果）
    val iconScale by animateFloatAsState(
        targetValue = if (iconVisible) 1f else 0.3f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "iconScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = BlockGradientColors
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // === 1. 锁图标（弹入动画） ===
            Box(
                modifier = Modifier
                    .scale(iconScale)
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🔒", fontSize = 40.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // [TASK-MILESTONE-V3] 需求 15 走查：中段弹性+可滚动，
            // 长原因文案/大字体下按钮组固定在底部，避免不可达
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // === 2. 标题（淡入） ===
                AnimatedVisibility(
                    visible = iconVisible,
                    enter = fadeIn(animationSpec = tween(500)) +
                            slideInVertically { it / 4 }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "应用已停用",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = reason,
                            fontSize = 15.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // === 3. 使用情况卡片（滑入） ===
                AnimatedVisibility(
                    visible = cardVisible,
                    enter = fadeIn(tween(400)) +
                            slideInVertically(tween(400)) { it / 3 }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.18f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "今日使用",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$usedMinutes / $limitMinutes 分钟",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // 进度条
                            LinearProgressIndicator(
                                progress = (usedMinutes.toFloat() / limitMinutes.coerceAtLeast(1)).coerceIn(0f, 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.25f),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "限额已用完，请休息一下",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // === 4. 按钮组（依次淡入） ===
            AnimatedVisibility(
                visible = buttonsVisible,
                enter = fadeIn(tween(600)) +
                        slideInVertically(tween(600)) { it / 2 }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 返回主页按钮
                    Button(
                        onClick = onGoHome,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 8.dp
                        )
                    ) {
                        Text(
                            text = "🏠 返回桌面",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFC62828)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 紧急电话按钮
                    OutlinedButton(
                        onClick = onEmergencyCall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "📞 紧急电话",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 家长紧急停用按钮
                    OutlinedButton(
                        onClick = onEmergencyRelease,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFFD54F)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "🔑 家长紧急停用",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // === 5. 底部品牌标识（始终可见） ===
            Text(
                text = "🥬 小趴菜 · 守护孩子的健康使用习惯\n紧急情况请拨打电话联系家长或警方",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}
