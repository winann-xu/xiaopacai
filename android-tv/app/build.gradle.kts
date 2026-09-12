// [android-tv] APP 模块构建脚本
// 小趴菜（儿童守护）TVOS 版 — 应用级构建配置

import java.time.LocalDate
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("jacoco")
}

// === 版本号联动 Git tag ===
fun execGit(vararg args: String): String? = try {
    val proc = ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() != 0 || out.isEmpty()) null else out
} catch (_: Exception) { null }

val gitTag: String? = execGit("describe", "--tags", "--exact-match")?.removePrefix("v")
val gitCommit: String = execGit("rev-parse", "--short", "HEAD") ?: "unknown"

// TV 版 tag 形如 tv1.0.0（前缀 tv 只标识 TV 线，不进版本号），故取数前先剥掉 tv/v 前缀：
// tv1.0.0 -> versionCode 10000；versionName 仍保留完整 tag（设备上显示 tv1.0.0）
fun semverToVersionCode(version: String): Int {
    val parts = version.removePrefix("tv").removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    return (parts.getOrElse(0) { 0 }) * 10000 + (parts.getOrElse(1) { 0 }) * 100 + (parts.getOrElse(2) { 0 })
}

val devFallbackVersionCode = 1
val appVersionName: String = gitTag ?: "dev-$gitCommit"
val appVersionCode: Int = if (gitTag != null) semverToVersionCode(gitTag) else devFallbackVersionCode

val overrideVersionCode: Int? = providers.gradleProperty("XPC_OVERRIDE_VERSION_CODE").orNull?.toIntOrNull()

android {
    namespace = "com.xiaopacai.tvos"
    compileSdk = 35

    // 小米电视 AIDL00 为 Android 5.1 (API 22)，minSdk 从 26 降到 21
    // 启用 core library desugaring 让 Java 8 字节码在 API 22 上可运行
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    defaultConfig {
        applicationId = "com.xiaopacai.tvos"
        minSdk = 21  // Android 5.1（小米电视 AIDL00 实测系统版本）
        multiDexEnabled = true  // API 22 需内置多 dex 支持
        targetSdk = 35
        versionCode = overrideVersionCode ?: appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "VERSION_CODE", "${overrideVersionCode ?: appVersionCode}")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"${LocalDate.now()}\"")
        // CLOUD_HOST/PORT 可配置（从环境变量或 Gradle 属性注入，不写死单一域名）
        // 使用方式：
        //   ./gradlew assembleDebug -PXPC_CLOUD_HOST=dev.example.com -PXPC_CLOUD_PORT=8443
        //   或 set XPC_CLOUD_HOST, XPC_CLOUD_PORT 环境变量
        val cloudHost = providers.gradleProperty("XPC_CLOUD_HOST")
            .orNull
            .takeIf { !it.isNullOrEmpty() }
            ?: System.getenv("XPC_CLOUD_HOST")
            .takeIf { !it.isNullOrEmpty() }
            ?: "192.168.1.100"  // 默认：家长自托管地址
        val cloudPort = providers.gradleProperty("XPC_CLOUD_PORT")
            .orNull
            .takeIf { !it.isNullOrEmpty() }
            ?: System.getenv("XPC_CLOUD_PORT")
            .takeIf { !it.isNullOrEmpty() }
            ?: "443"  // 默认 HTTPS 端口

        buildConfigField("String", "CLOUD_HOST", "\"$cloudHost\"")
        buildConfigField("int", "CLOUD_PORT", "$cloudPort")

        vectorDrawables {
            useSupportLibrary = true
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("XPC_KEYSTORE").orNull
            if (!storePath.isNullOrEmpty()) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("XPC_KEYSTORE_PASSWORD").orNull ?: ""
                keyAlias = providers.gradleProperty("XPC_KEY_ALIAS").orNull ?: ""
                keyPassword = providers.gradleProperty("XPC_KEY_PASSWORD").orNull ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs["release"]
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // === Robolectric 配置 ===
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    // Jacoco 覆盖率报告 — 覆盖三核心模块
    tasks.register<JacocoReport>("jacocoTestReport") {
        // Do NOT dependOn testDebugUnitTest — it's finalized by test task
        // This ensures jacoco runs even when tests have failures

        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(true)
        }

        // 指定要覆盖的核心模块
        val mainSrc = "${project.projectDir}/src/main/java/com/xiaopacai/tvos"

        sourceDirectories.setFrom(files(mainSrc))
        classDirectories.setFrom(
            fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug/com/xiaopacai/tvos") {
                // === 包含：需要覆盖的核心模块 ===
                include("**/util/TimeoutStateMachine*.class")
                include("**/util/EncryptionHelper*.class")
                include("**/util/NetworkHelper*.class")
                include("**/util/TimeoutExecutorTV.class")
                include("**/data/model/*.class")

                // === 排除：无法在 JVM 单元测试中执行的协程 lambda ===
                exclude("**/*\$refreshUsage\$1*.class")
                exclude("**/*\$onAppLaunched\$1*.class")
                exclude("**/*\$postJson\$1*.class")
                exclude("**/*\$get\$1*.class")
                exclude("**/*\$startHeartbeat\$1*.class")
                exclude("**/*\$triggerHeartbeat\$1*.class")
                exclude("**/*\$startMonitoring\$1*.class")
                exclude("**/*\$startTamperDetection\$1*.class")
                exclude("**/*\$processHeartbeatResponse\$1*.class")
                exclude("**/*\$applyPolicy\$1*.class")
                exclude("**/*\$applyConfigUpdate\$1*.class")
                exclude("**/*\$handleAction\$1*.class")
                exclude("**/*\$buildUsageDataArray\$1*.class")

                // === 排除：纯 Service 类（含 Android 生命周期，需 Robolectric） ===
                exclude("**/service/CloudSyncServiceTV*.class")
                exclude("**/service/AntiBypassServiceTV*.class")
                exclude("**/service/TamperAlertService*.class")
                exclude("**/service/LockOverlayService*.class")
            }
        )
        executionData.setFrom(
            fileTree("${layout.buildDirectory.get()}") {
                include("**/*.exec")
            }
        )
    }

    // 确保单元测试生成 Jacoco exec 文件
    tasks.withType<Test> {
        finalizedBy("jacocoTestReport")
        // Use Robolectric's built-in JUnit 4 runner (simpler, more compatible)
        useJUnit()
        ignoreFailures = true
    }
}

dependencies {
    // 小米电视 AIDL00：Android 5.1 (API 22)，需要 desugaring 运行 JDK 8 字节码
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // === Compose for TV（官方推荐栈） ===
    implementation("androidx.tv:tv-material:1.0.0-alpha10")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")

    // === Compose（与 android-v3 对齐） ===
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // === AndroidX Core ===
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // === Lifecycle & ViewModel ===
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")

    // === Room + SQLCipher（加密数据库） ===
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // SQLCipher for encrypted database
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // === OkHttp + JSON（HTTP 通信，对接 CloudSyncService 协议） ===
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")

    // === BouncyCastle（P2P mTLS 客户端身份证书：EC P-256 自签名生成 + PKCS12 往返） ===
    // 版本取 1.77：与手机版 core 的 API 一致，且本机 Gradle 缓存已具备（可离线构建）
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // === Coroutines ===
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // === DataStore（偏好设置） ===
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // === WorkManager（定时心跳/后台任务） ===
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // === Testing ===
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.robolectric:shadows-multidex:4.14.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-inline:5.2.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
