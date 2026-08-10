package com.xiaopacai.child.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * [TASK-D1-02] 小趴菜儿童端主题
 *
 * 定义全局 UI 主题：色板、字体、组件样式。
 * 支持浅色/深色模式自动切换。
 */

// === 主色调：温暖的绿色系（儿童友好、不刺眼） ===
private val LightPrimary = Color(0xFF4CAF50)       // 主色：温和绿
private val LightOnPrimary = Color(0xFFFFFFFF)     // 主色上文字
private val LightPrimaryContainer = Color(0xFFC8E6C9) // 主色浅容器
private val LightSecondary = Color(0xFF03A9F4)     // 辅色：天空蓝
private val LightBackground = Color(0xFFF5F5F5)    // 背景浅灰
private val LightSurface = Color(0xFFFFFFFF)       // 表面纯白
private val LightError = Color(0xFFE53935)         // 错误红

private val DarkPrimary = Color(0xFF81C784)        // 深色主色
private val DarkOnPrimary = Color(0xFF1B5E20)      // 深色主色文字
private val DarkPrimaryContainer = Color(0xFF2E7D32) // 深色主色容器
private val DarkSecondary = Color(0xFF4FC3F7)      // 深色辅色
private val DarkBackground = Color(0xFF121212)     // 深色背景
private val DarkSurface = Color(0xFF1E1E1E)        // 深色表面
private val DarkError = Color(0xFFEF5350)          // 深色错误

// === Material3 配色方案 ===
private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    secondary = LightSecondary,
    background = LightBackground,
    surface = LightSurface,
    error = LightError,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    secondary = DarkSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    error = DarkError,
)

/**
 * 小趴菜主题 Composable
 * 包裹应用顶层，提供统一的 Material3 主题
 */
@Composable
fun XiaopacaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // 设置状态栏颜色与主题一致
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = XiaopacaiTypography,
        content = content
    )
}
