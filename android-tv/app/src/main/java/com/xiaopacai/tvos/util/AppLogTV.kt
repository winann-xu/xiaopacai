// [android-tv] 应用日志缓冲 + 脱敏 —— 【项8】自动上传运行日志（用于使用报告 / 故障追踪）
// 小趴菜 TVOS 版
//
// 设计要点（对标手机版 AppLog / LogUploader）：
//   1. 环形缓冲：内存 400 条 + 落盘（files/logs/tv_applog.txt，上限 ~256KB），重启不丢
//   2. **写日志时立刻脱敏**：邮箱、手机号、密码/令牌字段、JWT、Bearer 头、配对码全部打码，
//      保证「脱敏后」的数据才可能离开设备（服务端另有二次脱敏，这里先做第一道）
//   3. 崩溃捕获：Thread.setDefaultUncaughtExceptionHandler 把崩溃栈记进缓冲，
//      上送字段 recentCrashes 供故障追踪

package com.xiaopacai.tvos.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogTV {

    private const val TAG = "AppLogTV"
    private const val MAX_MEMORY_ENTRIES = 400
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "tv_applog.txt"

    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** 是否已安装崩溃捕获（只装一次） */
    @Volatile
    private var crashHandlerInstalled = false

    /** 应用级 Context（init 时注入，使 i/w/e 也能落盘——否则只有崩溃路径会写文件） */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class Entry(val ts: Long, val level: String, val tag: String, val msg: String) {
        /** 上送格式：`time level/tag: msg` */
        fun render(): String = "${timeFmt.format(Date(ts))} $level/$tag: $msg"
    }

    // ==================== 写入 ====================

    fun i(tag: String, msg: String) = append("I", tag, msg)
    fun w(tag: String, msg: String) = append("W", tag, msg)
    fun e(tag: String, msg: String) = append("E", tag, msg)

    fun append(level: String, tag: String, msg: String, context: Context? = null) {
        val safeMsg = sanitize(msg)
        val entry = Entry(System.currentTimeMillis(), level, tag, safeMsg)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_MEMORY_ENTRIES) buffer.removeFirst()
        }
        // 落盘（失败不抛）：优先用调用方传入的 context，其次用 init 注入的应用级 context
        runCatching { (context ?: appContext)?.let { persist(it, entry) } }
    }

    // ==================== 脱敏 ====================

    private val reEmail = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val rePhone = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val reJwt = Regex("""eyJ[A-Za-z0-9\-_]{6,}\.[A-Za-z0-9\-_]{6,}\.[A-Za-z0-9\-_]{4,}""")
    private val reBearer = Regex("""(?i)(bearer\s+)[A-Za-z0-9\-._~+/=]{8,}""")
    private val reSecretField = Regex(
        """(?i)("?(?:password|passwd|pwd|token|accessToken|refreshToken|secret|pairCode|code)"?\s*[:=]\s*)("?)([^"',\s}]{3,})"""
    )
    private val reGgufPath = Regex("""(/Users|/vol\d|C:\\Users)[^\s"']{10,}""")

    /**
     * 脱敏：邮箱/手机号/JWT/Bearer/密钥字段/本机绝对路径
     */
    fun sanitize(raw: String): String {
        var s = raw
        s = reJwt.replace(s, "[JWT已打码]")
        s = reBearer.replace(s) { "${it.groupValues[1]}[已打码]" }
        s = reEmail.replace(s) { m -> maskEmail(m.value) }
        s = rePhone.replace(s) { m -> maskPhone(m.value) }
        s = reSecretField.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}[已打码]" }
        s = reGgufPath.replace(s, "[本地路径]")
        return if (s.length > 800) s.substring(0, 800) + "…(截断)" else s
    }

    /** `xwag14@126.com` → `x***@126.com` */
    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return "[邮箱已打码]"
        val head = email.substring(0, at)
        val domain = email.substring(at)
        return if (head.length <= 1) "***$domain" else "${head.first()}***$domain"
    }

    /** `13812345678` → `138****5678` */
    private fun maskPhone(phone: String): String =
        if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else "****"

    // ==================== 落盘 / 读取 ====================

    private fun logFile(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR).apply { if (!exists()) mkdirs() }
        return File(dir, LOG_FILE)
    }

    private fun persist(context: Context, entry: Entry) {
        val f = logFile(context)
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            // 超限就砍掉前一半（简单轮转）
            val kept = runCatching {
                f.readLines().let { it.drop(it.size / 2) }
            }.getOrDefault(emptyList())
            f.writeText(kept.joinToString("\n") + "\n")
        }
        f.appendText(entry.render() + "\n")
    }

    /** 内存缓冲快照（上送用） */
    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    /** 最近若干条 ERROR / 崩溃，用于 diagnostics-report.recentCrashes */
    fun recentErrors(limit: Int = 20): List<String> = synchronized(lock) {
        buffer.filter { it.level == "E" || it.msg.contains("崩溃") }
            .takeLast(limit)
            .map { it.render() }
    }

    // ==================== 崩溃捕获 ====================

    /**
     * 安装全局崩溃捕获（在 Application.onCreate 调用一次）。
     * 记录脱敏后的栈信息，然后交回原处理器，保持应用原有崩溃行为。
     */
    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = throwable.stackTraceToString().take(600)
                append("E", "CRASH", "崩溃 ${throwable.javaClass.simpleName}: ${throwable.message} | $stack", appContext)
                Log.e(TAG, "捕获到崩溃，已记入待上送缓冲", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
