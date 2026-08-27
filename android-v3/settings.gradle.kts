// [TASK-D1-02][TASK-D2-01] Android 工程设置
// 小趴菜儿童端 — 模块注册与依赖仓库配置

pluginManagement {
    repositories {
        // 优先使用国内镜像加速（阿里云），回退 Google/Maven Central
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 优先使用国内镜像加速（阿里云），回退 Google/Maven Central
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // Zetetic SQLCipher 仓库（加密数据库核心依赖）
        maven { url = uri("https://s3.amazonaws.com/repo.commonsware.com") }
        google()
        mavenCentral()
    }
}

// 注册唯一的子模块：儿童端 APP
rootProject.name = "xiaopacai-child"
include(":app")
