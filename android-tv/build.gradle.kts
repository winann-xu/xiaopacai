// [android-tv] 工程根构建脚本
// 小趴菜儿童端 TVOS 版 — 全局构建配置

// 顶层构建脚本：配置所有子模块共用的插件与仓库
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.android.library") version "8.7.0" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
