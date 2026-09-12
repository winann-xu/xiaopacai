# [android-tv] ProGuard 规则 — 小趴菜 TVOS 版
# 混淆 + 压缩

# Keep Room entities and DAOs
-keep class com.xiaopacai.tvos.data.model.** { *; }
-keep class com.xiaopacai.tvos.data.database.** { *; }

# Keep service classes (reflection-based start)
-keep class com.xiaopacai.tvos.service.** { *; }

# Keep Compose functions
-keep class com.xiaopacai.tvos.ui.** { *; }

# OkHttp + Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Coroutines
-keepattributes *Annotation*, SourceFile, LineNumberTable
-keepclassmembers public final class * extends kotlinx.coroutines.AbstractCoroutine {
    public void <init>(...);
}

# JSON
-dontorg json.**
-dontwarn org.json.**

# Security Crypto
-dontwarn androidx.security.crypto.**

# Debug logging (release)
-assumenosideeffects class android.util.Log {
    public static boolean isLogEnabled(*);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
