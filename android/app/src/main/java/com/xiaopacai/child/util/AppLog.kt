package com.xiaopacai.child.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [TASK-MILESTONE-V3] 需求 14：本机运行日志环形缓冲（时间/级别/模块/内容）
 *
 * - 环形缓冲：内存 5000 条上限；文件追加 JSON 行，5MB 上限（超限按内存最新条目重写，
 *   重写阈值 4MB 避免临界抖动）；
 * - 脱敏：写入时自动打码——密码/验证码/令牌/密钥等明文不落盘；服务端入库前二次打码（纵深防御）；
 * - 输出三路：内存缓冲（UI 展示）+ 文件落盘（跨进程留存）+ 镜像 android.util.Log（既有排查手段）；
 * - 崩溃：DiagnosticsCollector 的崩溃处理器调用 [eCrash] 同步落盘，进程消亡不丢最后一条；
 * - 上传：LogUploader 增量读取（按时间戳去重），配合 5000 条上限即「本地保留策略」。
 */
object AppLog {

    /** 一条日志：时间戳（epoch ms）/ 级别 / 模块 tag / 内容 */
    data class Entry(val ts: Long, val level: String, val tag: String, val msg: String)

    const val LEVEL_DEBUG = "D"
    const val LEVEL_INFO = "I"
    const val LEVEL_WARN = "W"
    const val LEVEL_ERROR = "E"

    /** 需求 14 建议上限：5000 条 / 5MB */
    const val MAX_ENTRIES = 5000
    const val MAX_FILE_BYTES = 5L * 1024 * 1024

    private const val FILE_NAME = "xpc_applog.txt"
    /** 文件重写阈值（略低于 5MB 上限，重写后文件 ≈ 4MB） */
    private const val REWRITE_BYTES = 4L * 1024 * 1024
    /** 单条内容上限（堆栈保留长度；服务端入库再截断到 1000 字符） */
    private const val MAX_MSG_LEN = 4000

    private val lock = Any()
    /** 内存环形缓冲，旧 → 新 */
    private val buffer = ArrayList<Entry>()
    @Volatile
    private var file: File? = null

    // ==================== 脱敏模式（写入时打码） ====================

    /** key=value 赋值形式：password/token/secret/api-key 等，value 整体打码（保留分隔符后空格可读） */
    private val SECRET_ASSIGNMENT = Regex(
        "(?i)((?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|auth[_-]?token)\\s*[:=]\\s*)[^\\s,;，；]+"
    )

    /** 验证码/校验码标签后跟 4-8 位数字：仅数字打码，保留标签可读 */
    private val VERIFICATION_CODE = Regex(
        "(?i)((?:验证码|校验码|verification[\\s_-]?code|sms[\\s_-]?code)\\s*[:=：]?\\s*)\\d{4,8}"
    )

    /** JWT（三段 Base64URL，eyJ 开头） */
    private val JWT_TOKEN = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")

    /** 64 位十六进制串（密钥/哈希常见形态） */
    private val HEX_64 = Regex("(?i)\\b[a-f0-9]{64}\\b")

    /**
     * 敏感信息打码（纯函数，可单测）：
     * - `password=xxx` / `token: xxx` → `password=***`
     * - `验证码 123456` → `验证码 ***`
     * - 裸 JWT → `***`
     * - 64 位 hex → `***`
     */
    fun maskSecrets(text: String): String {
        var out = SECRET_ASSIGNMENT.replace(text, "$1***")
        out = VERIFICATION_CODE.replace(out, "$1***")
        out = JWT_TOKEN.replace(out, "***")
        out = HEX_64.replace(out, "***")
        return out
    }

    // ==================== 初始化 ====================

    /** 应用启动时调用一次：加载既有文件（仅最近 MAX_ENTRIES 行，容错损坏行） */
    fun init(context: Context) {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        initWithFile(f)
    }

    /** 指定文件初始化（单元测试注入临时文件用） */
    internal fun initWithFile(f: File) {
        require(!f.isDirectory) { "日志文件路径不能是目录: $f" }
        synchronized(lock) {
            if (file != null) return
            file = f
            loadFromFile(f)
        }
        i("App", "日志缓冲初始化完成，文件 ${f.length()} 字节 / 内存 ${buffer.size} 条")
    }

    /** 仅测试用：重置单例状态（AppLog 为进程级单例，正常流程永不调用） */
    internal fun resetForTest() {
        synchronized(lock) {
            buffer.clear()
            file = null
        }
    }

    private fun loadFromFile(f: File) {
        if (!f.exists()) return
        try {
            val lines = f.readLines()
            // 仅保留最近 MAX_ENTRIES 行（文件可能因历史原因超量）
            lines.takeLast(MAX_ENTRIES).forEach { line ->
                parseLine(line)?.let { buffer.add(it) }
            }
        } catch (_: Exception) {
            // 文件损坏不阻断启动，从空缓冲开始
        }
    }

    private fun parseLine(line: String): Entry? = try {
        val o = JSONObject(line)
        Entry(o.optLong("t"), o.optString("l", LEVEL_INFO), o.optString("tag", ""), o.optString("m", ""))
    } catch (_: Exception) {
        null
    }

    // ==================== 写日志 ====================

    fun d(tag: String, msg: String) = write(LEVEL_DEBUG, tag, msg, null)

    fun i(tag: String, msg: String) = write(LEVEL_INFO, tag, msg, null)

    fun w(tag: String, msg: String) = write(LEVEL_WARN, tag, msg, null)

    fun e(tag: String, msg: String, tr: Throwable? = null) = write(LEVEL_ERROR, tag, msg, tr)

    /**
     * 崩溃处理器专用：同步写缓冲 + 落盘（[write] 之外的唯一入口，
     * 崩溃线程上避免任何异步/重逻辑，保证进程消亡前数据已到文件）。
     */
    fun eCrash(tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), LEVEL_ERROR, tag.take(64), maskSecrets(msg).take(MAX_MSG_LEN))
        synchronized(lock) {
            buffer.add(entry)
            if (buffer.size > MAX_ENTRIES) {
                val excess = buffer.size - MAX_ENTRIES
                buffer.subList(0, excess).clear()
            }
            file?.let { f ->
                try { f.appendText(jsonLine(entry) + "\n") } catch (_: Exception) {}
            }
        }
        Log.e(tag, msg)
    }

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val content = if (tr != null) msg + "\n" + Log.getStackTraceString(tr) else msg
        val entry = Entry(
            System.currentTimeMillis(), level, tag.take(64),
            maskSecrets(content).take(MAX_MSG_LEN)
        )
        synchronized(lock) {
            buffer.add(entry)
            if (buffer.size > MAX_ENTRIES) {
                val excess = buffer.size - MAX_ENTRIES
                buffer.subList(0, excess).clear()
            }
            val f = file
            if (f != null) {
                try {
                    f.appendText(jsonLine(entry) + "\n")
                    if (f.length() > REWRITE_BYTES) rewriteFileLocked(f)
                } catch (_: Exception) {
                    // 日志落盘失败不影响主流程（仅内存缓冲继续工作）
                }
            }
        }
        // 镜像 android.util.Log，保持既有排查手段
        when (level) {
            LEVEL_ERROR -> Log.e(tag, msg, tr)
            LEVEL_WARN -> Log.w(tag, msg, tr)
            LEVEL_DEBUG -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }
    }

    /** 文件超限：按内存最新条目重写（从最新回溯，累计不超过 REWRITE_BYTES，丢弃更旧条目） */
    private fun rewriteFileLocked(f: File) {
        try {
            val keep = ArrayList<Entry>()
            var bytes = 0L
            for (i in buffer.indices.reversed()) {
                val line = jsonLine(buffer[i]) + "\n"
                if (bytes + line.length > REWRITE_BYTES) break
                keep.add(buffer[i])
                bytes += line.length
            }
            keep.reverse() // 恢复旧 → 新
            f.writeText(keep.joinToString("") { jsonLine(it) + "\n" })
            buffer.clear()
            buffer.addAll(keep)
        } catch (_: Exception) {}
    }

    private fun jsonLine(entry: Entry): String =
        JSONObject()
            .put("t", entry.ts)
            .put("l", entry.level)
            .put("tag", entry.tag)
            .put("m", entry.msg)
            .toString()

    // ==================== 读取 ====================

    /** 全部日志（最新在前，供 UI 滚动展示） */
    fun entries(): List<Entry> = synchronized(lock) { buffer.reversed() }

    /** 导出为可读文本（复制用） */
    fun exportText(): String = synchronized(lock) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        buffer.joinToString("\n") { e ->
            "${fmt.format(Date(e.ts))} ${e.level}/${e.tag} ${e.msg}"
        }
    }

    fun fileSizeBytes(context: Context): Long =
        File(context.applicationContext.filesDir, FILE_NAME).length()

    /** 清空：内存 + 文件（确认弹窗后调用） */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            try { file?.writeText("") } catch (_: Exception) {}
        }
        Log.i("AppLog", "运行日志已清空")
    }
}
