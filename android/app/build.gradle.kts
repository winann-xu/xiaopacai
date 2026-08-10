// [TASK-D1-02] APP 模块构建脚本
// 小趴菜儿童端 — 应用级构建配置

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")  // 用于 Room 注解处理
}

android {
    namespace = "com.xiaopacai.child"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaopacai.child"
        minSdk = 26  // Android 8.0，保证前台服务与通知渠道支持
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

    // === SQLCipher 加密数据库（BSD 社区版） ===
    // 使用 net.zetetic:android-database-sqlcipher 提供 SQLCipher 加密
    implementation("net.zetetic:android-database-sqlcipher:4.5.6")
    // Room 运行时（配合 SQLCipher 使用）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // === 前台服务 ===
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // === 网络（P2P 通信） ===
    // OkHttp 用于 TCP/TLS 连接
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JmDNS 用于局域网服务发现
    implementation("org.jmdns:jmdns:3.5.9")

    // === 二维码生成（配对用） ===
    implementation("com.google.zxing:core:3.5.2")

    // === 测试 ===
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
