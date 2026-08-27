package com.xiaopacai.child

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.xiaopacai.child.ui.MainScreen
import com.xiaopacai.child.ui.theme.XiaopacaiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // [TASK-V2.0.6-UNBIND-SYNC] 打开应用即确保守护前台服务存活：
        // OPPO 清理/强停会杀掉服务导致云同步与守护中断，重开应用时自愈恢复
        try {
            com.xiaopacai.child.service.GuardianForegroundService.start(this)
        } catch (_: Exception) {
            // 前台服务启动失败不阻断界面
        }

        setContent {
            XiaopacaiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
