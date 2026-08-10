package com.xiaopacai.child.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.ui.theme.XiaopacaiTheme

/**
 * [TASK-D2-03] 应用拦截全屏覆盖界面
 *
 * 当前台应用被拦截时，通过无障碍服务启动此 Activity 覆盖屏幕。
 * 显示拦截原因，提供"返回主页"按钮。
 *
 * UI 设计：品牌红色主题 + 图标 + 原因说明 + 可用操作
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
            XiaopacaiTheme {
                BlockOverlayScreen(
                    targetPackage = targetPackage,
                    reason = reason,
                    usedMinutes = usedMinutes,
                    limitMinutes = limitMinutes,
                    onGoHome = { finishAndGoHome() },
                    onEmergencyCall = { makeEmergencyCall() }
                )
            }
        }
    }

    /**
     * 返回桌面并关闭拦截界面
     */
    private fun finishAndGoHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(homeIntent)
        } catch (_: Exception) { /* 忽略 */ }
        finish()
    }

    /**
     * 拨打紧急电话
     */
    private fun makeEmergencyCall() {
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:110")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(dialIntent)
        } catch (_: Exception) {
            // 紧急情况下直接启动拨号盘
            val dialIntent = Intent(Intent.ACTION_DIAL)
            dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(dialIntent) } catch (_: Exception) {}
        }
    }

    override fun onBackPressed() {
        // 拦截返回键：返回桌面而非回到被拦截应用
        finishAndGoHome()
    }
}

// ==================== Compose UI ====================

@Composable
private fun BlockOverlayScreen(
    targetPackage: String,
    reason: String,
    usedMinutes: Int,
    limitMinutes: Int,
    onGoHome: () -> Unit,
    onEmergencyCall: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFD32F2F)  // 品牌红色
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 大图标
            Text(
                text = "🔒",
                fontSize = 72.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 标题
            Text(
                text = "应用已停用",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 原因说明
            Text(
                text = reason,
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 使用情况
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "今日使用",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "$usedMinutes / $limitMinutes 分钟",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "限额已用完，请休息一下",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 返回主页按钮
            Button(
                onClick = onGoHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "🏠 返回桌面",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFD32F2F)
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
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "📞 紧急电话",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 底部说明
            Text(
                text = "小趴菜 · 守护孩子的健康使用习惯\n紧急情况请拨打电话联系家长或警方",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}
