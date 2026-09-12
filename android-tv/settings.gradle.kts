// [android-tv] 工程根构建配置 — settings
// 小趴菜（儿童守护）TVOS 版（小米电视）— 独立 Android TV APK
// 代码目录：android-tv/  |  包名：com.xiaopacai.tvos

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "xiaopacai-tvos"
include(":app")
