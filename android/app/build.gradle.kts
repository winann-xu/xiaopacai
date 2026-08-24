// [TASK-D1-02] APP 模块构建脚本
// 小趴菜儿童端 — 应用级构建配置

import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")  // 用于 Room 注解处理
}

// === [TASK-MILESTONE-V3] 需求 1：版本号联动 Git tag（语义化版本） ===
// versionName 构建时自动读取当前 Git tag（无 tag 的开发构建用 dev-短哈希）；
// versionCode 由 tag 语义化版本推导：major*10000 + minor*100 + patch，保证单调递增。
// 规范见 docs/VERSIONING.md；升级流程：更新 CHANGELOG → 打 tag（如 v1.1.0）→ 构建。
fun execGit(vararg args: String): String? = try {
    // 在仓库根目录执行 git；git 不可用/失败/无输出时返回 null（构建不因此失败）
    // 注意必须检查退出码：git describe 无 tag 时会在 stdout 输出 fatal 错误文本
    val proc = ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() != 0 || out.isEmpty()) null else out
} catch (_: Exception) { null }

// 当前 HEAD 的精确 tag（如 v1.1.0）；无精确 tag 返回 null
val gitTag: String? = execGit("describe", "--tags", "--exact-match")?.removePrefix("v")
// 短提交哈希（dev 版本号与 BuildConfig 记录用）
val gitCommit: String = execGit("rev-parse", "--short", "HEAD") ?: "unknown"

// 语义化版本 → versionCode：major*10000 + minor*100 + patch（v1.1.0 → 10100）
fun semverToVersionCode(version: String): Int {
    val parts = version.split(".").map { it.toIntOrNull() ?: 0 }
    return (parts.getOrElse(0) { 0 }) * 10000 + (parts.getOrElse(1) { 0 }) * 100 + (parts.getOrElse(2) { 0 })
}

// 无 tag 的开发构建兜底 versionCode：固定为 1，保证任何正式发布包（≥10000）都能覆盖升级
val devFallbackVersionCode = 1
// 正式版本号：打 tag 后由 tag 推导；未打 tag 用 dev-短哈希 标识开发包
val appVersionName: String = gitTag ?: "dev-$gitCommit"
val appVersionCode: Int = if (gitTag != null) semverToVersionCode(gitTag) else devFallbackVersionCode
// [TASK-STRICT-PROVISION-V1] 测试用版本号覆盖：允许 dev 包覆盖安装到生产版本之上
// （如 -PXPC_OVERRIDE_VERSION_CODE=10300）；正式发布仍以 Git tag 为准，不使用该参数。
val overrideVersionCode: Int? = providers.gradleProperty("XPC_OVERRIDE_VERSION_CODE")
    .orNull?.toIntOrNull()
// === 版本号联动结束 ===

android {
    namespace = "com.xiaopacai.child"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaopacai.child"
        minSdk = 26  // Android 8.0，保证前台服务与通知渠道支持
        targetSdk = 34
        versionCode = overrideVersionCode ?: appVersionCode
        versionName = appVersionName  // [TASK-MILESTONE-V3] 版本号联动 Git tag（docs/VERSIONING.md）

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // [TASK-MILESTONE-V3] 版本信息注入 BuildConfig（关于页展示、日志上报带版本）
        buildConfigField("String", "VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "VERSION_CODE", "$appVersionCode")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"${LocalDate.now()}\"")

        // 向量图标兼容
        vectorDrawables {
            useSupportLibrary = true
        }

        // 房间数据库 schema 导出目录
        kapt {
            arguments {
                arg("room.schemaLocation", "$projectDir/schemas")
            }
        }
    }

    signingConfigs {
        create("release") {
            // [TASK-PRELAUNCH-APK] 签名密钥从用户级 gradle.properties 读取（不入库）
            val storePath = providers.gradleProperty("XPC_KEYSTORE").orNull
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("XPC_STORE_PASS").get()
                keyAlias = providers.gradleProperty("XPC_KEY_ALIAS").getOrElse("xiaopacai")
                keyPassword = providers.gradleProperty("XPC_KEY_PASS").get()
            }
        }
        // [TASK-STRICT-COLOROS] ColorOS 强管制实验签名：AOSP testkey（公开测试证书，不入库）
        // 用于绕过 ColorOS 对第三方 Device Owner 的签名白名单校验（仅实验/专用版，非默认发布签名）。
        create("colorosTestkey") {
            val testkeyPath = providers.gradleProperty("XPC_TESTKEY").orNull
            if (!testkeyPath.isNullOrBlank()) {
                storeFile = file(testkeyPath)
                storePassword = "testkey"
                keyAlias = "testkey"
                keyPassword = "testkey"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // [TASK-PRELAUNCH-APK] A1：Release 发布包正式签名（密钥不入库，从环境变量读取）
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // [TASK-STRICT-COLOROS] testkey 签名的强管制专用变体（实验，不上架）
        create("strictTestkey") {
            isMinifyEnabled = true
            isShrinkResources = true
            versionNameSuffix = "-testkey"
            signingConfig = signingConfigs.getByName("colorosTestkey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // 调试模式下保留更多日志
            isDebuggable = true
        }
    }

    // Java 17 编译兼容
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true  // 启用 Jetpack Compose
        buildConfig = true  // 生成 BuildConfig（调试模式测试入口用）
    }

    // [TASK-PRELAUNCH-APK] B2：按 ABI 拆分，避免单包携带 4 份 SQLCipher 原生库
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }


    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/9/OSGI-INF/LICENSES/**"
        }
        // [TASK-STRICT-PROVISION-V1] 强管制模式：内置 libadb.so 需从 APK 解压到
        // nativeLibraryDir 才能被 ProcessBuilder 执行（LADB 同款行为）；
        // 默认 useLegacyPackaging=false 时 native 库留在 APK 内、目录为空。
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Robolectric 单元测试配置
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // === Jetpack Compose（声明式 UI） ===
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // === AndroidX 核心 ===
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // === CameraX（二维码扫码）===
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // === SQLCipher 加密数据库（BSD 社区版） ===
    // 使用 net.zetetic:android-database-sqlcipher 提供 SQLCipher 加密
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    // Room 运行时（配合 SQLCipher 使用）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // === 前台服务 ===
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // === 网络（P2P 通信） ===
    // OkHttp 用于 TCP/TLS 连接
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // BouncyCastle：家长端 P2P 服务端自签名证书生成（AndroidKeyStore 密钥不兼容 Conscrypt TLS 握手签名）
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78")
    // JmDNS 用于局域网服务发现
    implementation("org.jmdns:jmdns:3.5.9")

    // === 二维码生成（配对用） ===
    implementation("com.google.zxing:core:3.5.2")

    // === 测试 ===
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
    // org.json 用于 PolicyConfig 等需要 JSON 序列化的 JVM 单元测试
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
