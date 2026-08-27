package com.xiaopacai.child.ui.strict

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.xiaopacai.child.ui.theme.XiaopacaiTheme

/**
 * [TASK-STRICT-PROVISION-V1] 强管制模式入口（ADR 0018）
 *
 * 独立受控入口：设置 → 守护增强 → 强管制模式。
 * 普通用户界面不出现 ADB/命令提示（D4 决策延续），全部能力封装在此页。
 */
class StrictProvisionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaopacaiTheme {
                StrictProvisionScreen(onBack = { finish() })
            }
        }
    }
}
