// [android-tv] 数据模型 — DeviceInfo
// 小趴菜 TVOS 版 — 设备硬件信息

package com.xiaopacai.tvos.data.model

import android.os.Build

/**
 * 设备硬件信息快照
 */
data class DeviceInfo(
    val brand: String = Build.BRAND,
    val model: String = Build.MODEL,
    val manufacturer: String = Build.MANUFACTURER,
    val product: String = Build.PRODUCT,
    val board: String = Build.BOARD,
    val device: String = Build.DEVICE,
    val hardware: String = Build.HARDWARE,
    val serial: String = Build.SERIAL,
    val sdkVersion: Int = Build.VERSION.SDK_INT,
    val osVersion: String = Build.VERSION.RELEASE,
    val androidId: String = "",
    val macAddress: String = "",
    val tvBrand: String = "AndroidTV",  // 电视品牌（小米/海信/索尼等）
    val miuiVersion: String = "unknown" // MIUI TV 版本
)
