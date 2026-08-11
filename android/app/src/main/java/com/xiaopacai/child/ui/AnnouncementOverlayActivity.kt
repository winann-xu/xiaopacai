package com.xiaopacai.child.ui

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.data.database.AnnouncementDao
import com.xiaopacai.child.ui.theme.XiaopacaiTheme
import com.xiaopacai.child.util.DbPassphraseProvider

/**
 * [TASK-OPT-4] 紧急公告全屏置顶界面
 *
 * 家长发布 priority=urgent 公告时，无论儿童正在游戏/视频，均全屏置顶显示，
 * 儿童必须点击"我知道了"确认后才能关闭；确认后标记已读。
 */
class AnnouncementOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AnnouncementOverlay"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val announcementId = intent.getStringExtra("announcement_id") ?: ""
        val title = intent.getStringExtra("title") ?: "紧急公告"
        val content = intent.getStringExtra("content") ?: ""

        setContent {
            XiaopacaiTheme(darkTheme = false) {
                UrgentAnnouncementScreen(
                    title = title,
                    content = content,
                    onAcknowledge = {
                        markAsRead(announcementId)
                        Log.i(TAG, "紧急公告已确认: $announcementId")
                        finish()
                    }
                )
            }
        }
    }

    private fun markAsRead(announcementId: String) {
        if (announcementId.isEmpty()) return
        try {
            val passphrase = DbPassphraseProvider.getPassphrase(this)
            AnnouncementDao(XiaopacaiApp.instance.database)
                .markAsRead(announcementId, passphrase)
        } catch (e: Exception) {
            Log.e(TAG, "标记公告已读失败: ${e.message}")
        }
    }

    override fun onBackPressed() {
        // 紧急公告不可用返回键关闭，必须显式确认
    }
}

/** 紧急公告全屏界面（红橙渐变，明确"确认"动作） */
@Composable
private fun UrgentAnnouncementScreen(
    title: String,
    content: String,
    onAcknowledge: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFC62828), Color(0xFFEF6C00))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "🚨", fontSize = 56.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.92f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = content.ifBlank { "家长发布了紧急通知，请仔细阅读。" },
                        fontSize = 17.sp,
                        color = Color(0xFF424242),
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onAcknowledge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "我知道了",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
