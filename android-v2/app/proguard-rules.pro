# ProGuard 混淆规则
# 小趴菜儿童端 — 保持关键类不被混淆

# === SQLCipher ===
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# === JmDNS（局域网发现） ===
-keep class javax.jmdns.** { *; }

# === OkHttp / Okio ===
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# === ZXing（二维码） ===
-keep class com.google.zxing.** { *; }

# === 保持 Serializable 类 ===
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# === 移除调试日志（Release 构建） ===
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
