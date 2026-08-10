package com.xiaopacai.child

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xiaopacai.child.ui.MainScreen
import com.xiaopacai.child.ui.theme.XiaopacaiTheme

/**
 * [TASK-D1-02] 小趴菜儿童端主 Activity
 *
 * 应用唯一 Activity，使用 Jetpack Compose 渲染全部界面。
 * 启动后先检查权限，未授权则引导用户完成权限设置。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用边到边渲染（Android 15+ 默认行为）
        enableEdgeToEdge()

        setContent {
            XiaopacaiTheme {
                // 应用主题：支持深色模式、统一字体/色板
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
