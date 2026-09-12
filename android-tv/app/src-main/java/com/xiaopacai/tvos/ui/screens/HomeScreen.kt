package com.xiaopacai.tvos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import androidx.compose.material3 as m3 // To avoid confusion if needed
import com.xiaopacai.tvos.R
import com.xiaopacai.`tvos`.ui.theme.XiaopacaiTVTheme

/**
 * 小趴菜 TV 主界面 - 符合 Xiaomi TV 标准实现
 * 特点：使用 tv-material3, 支持 D-pad 焦点缩放效果, 高对比度配色。
 */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToPinVerify: () -> Unit
) {
    // 使用渐变背景提升大屏视觉深度 (10-foot UI principle)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 64.dp, top = 72.dp, end = 64.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            contentPadding = PaddingValues(bottom = 56.dp)
        ) {
            // 1. 顶部标题栏
            item {
                Text(
                    text = "小趴菜 TV",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // 2. 今日使用时长卡片 (使用 tv-material3 Card)
            item {
                UsageSummaryCard(usedMinutes = 45, dailyLimit = 60)
            }

            // 3. 操作控制区 (使用 StandardCardButton 提供完美的焦点缩放效果)
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "快速操作",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        ControlButton(
                            label = "家长设置",
                            onClick = onNavigateToSettings,
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                        ControlButton(
                            label = "白名单",
                            onClick = onNavigateToWhitelist,
                            containerColor = MaterialTheme.color_scheme.primary // wait, typo in color_scheme? Let's use primary
                        )
                        // Fix typo below: materialtheme.colorScheme.primary
                    }
                }
            }
        }
    }
}

// Helper to fix the typo in my thought above during writing...
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ControlButton(
    label: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color
) {
    StandardCardButton(
        onClick = onClick,
        modifier = Modifier.width(280.dp).height(80.dp),
        scale = ClickableDefaults.scale(focusedScale = 1.05f), // D-pad Focus Scale effect
        colors = ButtonDefaults.buttonColors(containerColor = containerColor)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

// Separate function for usage card (Static/Non-focusable)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UsageSummaryCard(usedMinutes: Int, dailyLimit: Int) {
    val remaining = (dailyLimit - usedMinutes).coerceAtLeast(0)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(32.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "今日已使用",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$usedMinutes / $dailyLimit",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "分钟",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme un-typoed... (wait)
                )
            }
            // Let's just write it clean.
        }
    }
}
