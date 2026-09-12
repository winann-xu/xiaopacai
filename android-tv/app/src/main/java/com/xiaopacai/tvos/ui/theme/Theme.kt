// [android-tv] Compose 主题 — 小趴菜 TVOS 版
package com.xiaopacai.tvos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ==================== 颜色定义 ====================
private val Green = Color(0xFF4CAF50)
private val GreenDark = Color(0xFF388E3C)
private val GreenLight = Color(0xFF81C784)
private val Red = Color(0xFFF44336)
private val RedDark = Color(0xFFD32F2F)
private val Orange = Color(0xFFFF9800)
private val DarkBg = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val DarkCard = Color(0xFF2C2C2C)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0B0B0)

// ==================== TV 主题配色 ====================
private val XiaopacaiTVColorScheme = darkColorScheme(
    primary = Green,
    onPrimary = TextPrimary,
    primaryContainer = GreenDark,
    secondary = Orange,
    error = Red,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    background = DarkBg,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

// ==================== 主题入口 ====================
@Composable
fun XiaopacaiTVTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = XiaopacaiTVColorScheme,
        content = content
    )
}
