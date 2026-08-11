package com.xiaopacai.child.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.xiaopacai.child.p2p.P2PMessage
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.ui.theme.XiaopacaiTheme
import com.xiaopacai.child.util.DbPassphraseProvider

/**
 * [TASK-OPT-12-P2] 紧急公告全屏覆盖 Activity（需求4）
 *
 * 家长发布紧急公告（priority >= 2 且 requires_ack）时全屏置顶展示：
 * - showWhenLocked + turnScreenOn：锁屏/息屏下也能亮屏显示
 * - singleInstance + excludeFromRecents：独立任务栈、不进最近任务
 * - 内容含"我知道了"确认按钮：确认后记录 acknowledged_at 并上报 announcement_ack
 * - 防绕过：未确认前返回键无效；无障碍服务检测到被切走后重新拉起
 */
class AnnouncementOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AnnouncementOverlay"

        /** 待确认的紧急公告（未确认期间非空，无障碍服务据此防绕过） */
        @Volatile
        private var pendingAnnouncementId: String? = null

        @Volatile
        private var pendingTitle: String = ""

        @Volatile
        private var pendingContent: String = ""

        /**
         * 启动紧急公告覆盖层（可在 Service/Receiver 中调用）
         */
        fun launch(context: Context, announcementId: String, title: String, content: String) {
            pendingAnnouncementId = announcementId
            pendingTitle = title
            pendingContent = content
            try {
                val intent = Intent(context, AnnouncementOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra("announcement_id", announcementId)
                    putExtra("title", title)
                    putExtra("content", content)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "启动紧急公告覆盖层失败: ${e.message}")
            }
        }

        /** 是否有未确认的紧急公告（无障碍服务防绕过检测用） */
        fun hasPendingUrgent(): Boolean = pendingAnnouncementId != null

        /** 无障碍服务检测到绕过时重新拉起覆盖层 */
        fun relaunchPending(context: Context) {
            val id = pendingAnnouncementId ?: return
            launch(context, id, pendingTitle, pendingContent)
        }
    }

    /** 确认标记（确认后才能关闭） */
    private var confirmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏/息屏下亮屏显示（与 BlockOverlayActivity 一致）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 读取公告内容
        val announcementId = intent.getStringExtra("announcement_id")
            ?: pendingAnnouncementId
            ?: finishAndRemoveTask()
        val title = intent.getStringExtra("title") ?: pendingTitle
        val content = intent.getStringExtra("content") ?: pendingContent

        if (announcementId == null) {
            finishAndRemoveTask()
            return
        }

        setContent {
            XiaopacaiTheme(darkTheme = false) {
                UrgentAnnouncementScreen(
                    title = title,
                    content = content,
                    onConfirm = {
                        // 确认：记录回执时间 + 上报家长端 + 关闭
                        confirmed = true
                        acknowledgeAndReport(announcementId, title)
                        pendingAnnouncementId = null
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }

    /**
     * 确认处理：落库 acknowledged_at + P2P 上报 announcement_ack
     */
    private fun acknowledgeAndReport(announcementId: String, title: String) {
        try {
            val context = applicationContext
            val passphrase = DbPassphraseProvider.getPassphrase(context)
            val dao = AnnouncementDao(XiaopacaiApp.instance.database)
            dao.markAcknowledged(announcementId, passphrase)
            android.util.Log.i(TAG, "紧急公告已确认: $announcementId（$title）")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "记录公告回执失败: ${e.message}")
        }

        // 通过 P2P 链路上报回执（未连接时静默丢弃，下次公告重推会再提示）
        try {
            val p2p = GuardianForegroundService.getP2PConnection()
            val message = P2PMessage(
                type = "announcement_ack",
                payload = mapOf(
                    "announcementId" to announcementId,
                    "acknowledgedAt" to (System.currentTimeMillis() / 1000),
                    "deviceId" to getDeviceId()
                )
            )
            p2p.sendMessage(message)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "上报公告回执失败: ${e.message}")
        }
    }

    /**
     * 获取设备 ID（与 SyncManager 同一来源）
     */
    private fun getDeviceId(): String {
        val prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    // === 防绕过：未确认前不允许返回键关闭 ===
    override fun onBackPressed() {
        if (confirmed) {
            super.onBackPressed()
        } else {
            // 未确认：忽略返回键
            Toast.makeText(this, "请先点击「我知道了」确认紧急公告", Toast.LENGTH_SHORT).show()
        }
    }

    // === 防绕过：未确认前不允许 HOME 键离开（无障碍服务会重新拉起） ===
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!confirmed && pendingAnnouncementId != null) {
            android.util.Log.w(TAG, "检测到尝试离开紧急公告（未确认），等待无障碍服务重新拉起")
        }
    }
}

// ==================== Compose UI ====================

/** 紧急公告全屏界面（品牌红渐变，与封锁界面风格一致） */
@Composable
private fun UrgentAnnouncementScreen(
    title: String,
    content: String,
    onConfirm: () -> Unit
) {
    val gradientColors = listOf(
        Color(0xFFC62828),
        Color(0xFFD32F2F),
        Color(0xFFE53935)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradientColors))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 紧急标识
            Text(text = "🚨", fontSize = 56.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "紧急公告",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "来自家长的重要通知，请仔细阅读",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 公告内容卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.18f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = content,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.92f),
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 确认按钮（唯一关闭通道）
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "我知道了",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFC62828)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "确认后家长将收到回执，请如实阅读后再确认",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}
