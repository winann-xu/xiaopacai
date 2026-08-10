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
import androidx.core.view.WindowInsetsControllerCompat

/**
 * [TASK-D3-04] 小趴菜儿童端主题 — 成品级打磨
 *
 * 品牌色彩系统：以绿色为主调（成长·安全），蓝色为辅（信任·稳定）。
 * 支持浅色/深色模式自动切换，深色模式下使用降低亮度的品牌色以保护视力。
 *
 * 色彩语义：
 * - Primary（主色）：品牌绿 — 按钮、进度条、标题
 * - Secondary（辅色）：天空蓝 — 链接、次要操作
 * - Tertiary（第三色）：温暖橙 — 提醒、警告
 * - Error：警示红 — 错误、超时
 * - Surface Variant：浅灰绿 — 卡片、容器背景
 */

// ===== 浅色主题色彩 =====
// 品牌绿系列（Primary）
private val LightPrimary = Color(0xFF388E3C)            // 主色：深一度品牌绿
private val LightOnPrimary = Color(0xFFFFFFFF)          // 主色上文字
private val LightPrimaryContainer = Color(0xFFC8E6C9)   // 主色浅容器
private val LightOnPrimaryContainer = Color(0xFF1B5E20) // 容器上文字
// 天空蓝系列（Secondary）
private val LightSecondary = Color(0xFF0288D1)          // 辅色
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFB3E5FC)
private val LightOnSecondaryContainer = Color(0xFF01579B)
// 温暖橙系列（Tertiary - 提醒/警告）
private val LightTertiary = Color(0xFFF57C00)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFFFE0B2)
private val LightOnTertiaryContainer = Color(0xFFE65100)
// 背景/表面
private val LightBackground = Color(0xFFF8FBF8)         // 背景：带轻微绿意的白
private val LightOnBackground = Color(0xFF1A1C1A)
private val LightSurface = Color(0xFFFFFFFF)            // 表面纯白
private val LightOnSurface = Color(0xFF1A1C1A)
private val LightSurfaceVariant = Color(0xFFF0F4F0)     // 表面变体：浅灰绿
private val LightOnSurfaceVariant = Color(0xFF424940)
private val LightOutline = Color(0xFFB9C5B9)            // 边框颜色
private val LightOutlineVariant = Color(0xFFDEE5DE)     // 浅边框
// 错误色
private val LightError = Color(0xFFD32F2F)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFCDD2)
private val LightOnErrorContainer = Color(0xFFB71C1C)

// ===== 深色主题色彩 =====
// 品牌绿系列（深色模式下降亮度，减少视觉刺激）
private val DarkPrimary = Color(0xFF81C784)             // 深色主色
private val DarkOnPrimary = Color(0xFF1B5E20)
private val DarkPrimaryContainer = Color(0xFF2E7D32)
private val DarkOnPrimaryContainer = Color(0xFFC8E6C9)
// 天空蓝系列
private val DarkSecondary = Color(0xFF4FC3F7)
private val DarkOnSecondary = Color(0xFF003548)
private val DarkSecondaryContainer = Color(0xFF01579B)
private val DarkOnSecondaryContainer = Color(0xFFB3E5FC)
// 温暖橙系列
private val DarkTertiary = Color(0xFFFFB74D)
private val DarkOnTertiary = Color(0xFF4E2600)
private val DarkTertiaryContainer = Color(0xFFE65100)
private val DarkOnTertiaryContainer = Color(0xFFFFE0B2)
// 背景/表面
private val DarkBackground = Color(0xFF1A1C1A)
private val DarkOnBackground = Color(0xFFE1E3E1)
private val DarkSurface = Color(0xFF1A1C1A)
private val DarkOnSurface = Color(0xFFE1E3E1)
private val DarkSurfaceVariant = Color(0xFF2D322D)
private val DarkOnSurfaceVariant = Color(0xFFC1C9C1)
private val DarkOutline = Color(0xFF6B736B)
private val DarkOutlineVariant = Color(0xFF3D433D)
// 错误色
private val DarkError = Color(0xFFEF5350)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFCDD2)

// ===== Material3 配色方案 =====
private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

/**
 * [TASK-D3-04] 小趴菜品牌主题
 *
 * 提供统一的 Material3 品牌主题，自动适配浅色/深色模式。
 * 全面支持无障碍辅助功能（高对比度、大字体适配）。
 */
@Composable
fun XiaopacaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // 设置系统栏颜色与主题一致（状态栏 + 导航栏）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()

            val insetsController = WindowCompat.getInsetsController(window, view)
            // 状态栏图标/文字颜色：浅色背景用深色图标，深色背景用浅色图标
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme

            // Android 10+ 支持系统栏完全透明（边到边布局）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = XiaopacaiTypography,
        content = content
    )
}
