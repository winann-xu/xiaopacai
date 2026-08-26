package com.xiaopacai.child.ui.child

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.service.DoSetupService
import com.xiaopacai.child.ui.strict.StrictProvisionActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val isDo = DoSetupService.isDeviceOwner(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DO 授权", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDo) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (isDo) "DO 已激活" else "DO 未激活",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDo) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isDo)
                            "本应用已为设备所有者（Device Owner），防卸载最强。"
                        else
                            "未激活 Device Owner。激活后可脱离电脑完成系统级预置，" +
                                "提供最强防卸载保护。需 Android 11+ 且设备无账号/出厂重置状态。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isDo) {
                Button(
                    onClick = { DoSetupService.startDoSetup(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始 DO 授权")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
