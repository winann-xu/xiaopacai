// [TASK-D1-02] Android 工程根构建脚本
// 小趴菜儿童端 — 全局构建配置

// 顶层构建脚本：配置所有子模块共用的插件与仓库
plugins {
    // Android 应用插件（版本由 settings.gradle.kts 统一管理）
    id("com.android.application") version "8.2.0" apply false
    // Kotlin Android 插件
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    // Kotlin 编译时注解处理（用于 Room 等）
    id("org.jetbrains.kotlin.kapt") version "1.9.20" apply false
}
