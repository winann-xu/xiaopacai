// [android-tv] 尺寸扩展 — dp 到 px 转换 (Compose TV 兼容)
// 小趴菜 TVOS 版

package com.xiaopacai.tvos.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType

/**
 * 将 Int 转换为 Dp（使用 Compose TV 兼容方式）
 * 注意：Compose TV 中 Int.dp 可能不可用，直接使用 Dp(value = ...)
 */
fun Int.toDp(): Dp = Dp(value = this.toFloat())

/**
 * 将 Float 转换为 Dp
 */
fun Float.toDp(): Dp = Dp(value = this)

/**
 * 将 Double 转换为 Dp
 */
fun Double.toDp(): Dp = Dp(value = this.toFloat())

/**
 * 将 Int 转换为 Sp
 */
fun Int.sp(): TextUnit = androidx.compose.ui.unit.TextUnit(this.toFloat(), TextUnitType.Sp)

/**
 * 将 Float 转换为 Sp
 */
fun Float.sp(): TextUnit = androidx.compose.ui.unit.TextUnit(this, TextUnitType.Sp)

/**
 * 将 Double 转换为 Sp
 */
fun Double.sp(): TextUnit = androidx.compose.ui.unit.TextUnit(this.toFloat(), TextUnitType.Sp)
