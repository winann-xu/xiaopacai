// [android-tv] P2P 实时通道客户端（TCP + mTLS 长连接）
// 小趴菜 TVOS 版
//
// 为什么需要它（2026-09-12 用户拍板「方案 B」）：
//   电视端此前只有 HTTPS 心跳（60s 轮询拉策略），而 Web 端「下发策略」走的是
//   P2P 实时推送（PoliciesController.TryPush → P2pListenerService.SendToDevice）。
//   电视不在 P2P 会话表里 ⇒ 推送恒失败 ⇒ Web 端显示「设备离线」，与设备列表
//   显示的「在线」（HTTP 心跳置的 OnlineStatus）自相矛盾。
//   本类补齐电视端的 P2P 长连接，使实时下发的判定与手机版一致。
//
// 协议（复刻手机版 core/p2p/P2PConnectionService，服务端 P2pListenerService 强制 mTLS）：
//   1) TCP → host:9527 → TLS（提交客户端身份证书，指纹即设备身份锚点）
//   2) 帧 = 4 字节大端长度前缀 + JSON，{type, payload}
//   3) 发送 handshake（deviceId/deviceName/deviceType/version/timestamp/relay）
//   4) 服务端回 policy_update / announcement_push（握手成功即首推）+ heartbeat_ack
//   5) 每 30s 心跳；长时间收不到任何帧即重连（指数退避）
//
// 与 HTTP 通道的关系：两者并存、互不替代 —— HTTPS 继续负责使用上报/公告回执/
// 诊断/升级检查（已验证可用），P2P 只负责「实时下发」这一件事。

package com.xiaopacai.tvos.p2p

import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiaopacai.tvos.ui.announcement.AnnouncementOverlayActivity
import com.xiaopacai.tvos.ui.announcement.AnnouncementStore
import com.xiaopacai.tvos.util.AppLogTV
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object TvP2pClient {

    private const val TAG = "TvP2pClient"

    /** P2P 监听端口（服务端 P2P:ListenPort；Web 端二维码同样固定广播 9527） */
    const val P2P_PORT = 9527

    private const val HEARTBEAT_INTERVAL_MS = 30_000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val FIRST_FRAME_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 70_000
    private const val STALE_FRAME_MS = 200_000L
    private const val RECONNECT_BASE_MS = 2_000L
    private const val RECONNECT_MAX_MS = 60_000L
    private const val RATE_LIMIT_BASE_MS = 60_000L
    private const val RATE_LIMIT_MAX_MS = 600_000L
    /** 确定性拒绝（如设备尚无指纹）后的重试间隔：等家长在 Web 端处理完即自愈，不做 1s 级重试风暴 */
    private const val DETERMINISTIC_RETRY_MS = 300_000L

    private const val PREF_FILE = "tv_p2p_prefs"
    private const val PREF_SERVER_FP = "server_fingerprint"

    /** 确定性拒绝（重试无意义，需家长在 Web 端操作） */
    private val DETERMINISTIC_REJECTIONS = setOf(
        "revoked", "device_owned_by_other", "fingerprint_mismatch", "invalid_pairing_code"
    )

    /** 实时通道是否已连接（供界面显示） */
    val connected = MutableStateFlow(false)

    /** 最近一次错误/拒绝原因（空=正常） */
    val lastError = MutableStateFlow("")

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    @Volatile private var stopped = false

    /** 幂等启动（守护服务/看门狗/开机自启都会调用） */
    fun start(context: Context) {
        val app = context.applicationContext
        if (loopJob?.isActive == true) return
        stopped = false
        AppLogTV.i(TAG, "P2P 实时通道启动（设备身份指纹 ${TvP2pIdentity.fingerprint(app).take(16)}…）")
        loopJob = scope.launch { supervise(app) }
    }

    fun stop() {
        stopped = true
        loopJob?.cancel()
        loopJob = null
        connected.value = false
    }

    // ==================== 连接监管（指数退避） ====================

    private suspend fun supervise(app: Context) {
        var attempt = 0
        var deterministicLogged = false
        while (!stopped && scope.isActive) {
            val result = runCatching { connectAndServe(app) }
                .onFailure { Log.w(TAG, "连接异常: ${it.message}") }
                .getOrDefault(Outcome.RETRY)

            if (result == Outcome.DETERMINISTIC && !deterministicLogged) {
                AppLogTV.w(TAG, "P2P 握手被确定性拒绝：${lastError.value}（每 ${DETERMINISTIC_RETRY_MS / 60_000} 分钟重试一次，家长在 Web 端处理完即自动恢复）")
                deterministicLogged = true
            }

            val delayMs = when (result) {
                Outcome.DETERMINISTIC -> DETERMINISTIC_RETRY_MS
                Outcome.RATE_LIMITED -> minOf(RATE_LIMIT_BASE_MS shl attempt, RATE_LIMIT_MAX_MS)
                else -> minOf(RECONNECT_BASE_MS shl attempt, RECONNECT_MAX_MS)
            }
            attempt = if (result == Outcome.CONNECTED) 1 else (attempt + 1).coerceAtMost(10)
            Log.i(TAG, "${delayMs}ms 后重连（第 $attempt 次）")
            delay(delayMs)
        }
    }

    private enum class Outcome { CONNECTED, RETRY, RATE_LIMITED, DETERMINISTIC }

    // ==================== 一次完整连接 ====================

    private suspend fun connectAndServe(app: Context): Outcome {
        val host = SharedPreferenceHelperTV.getCloudHost(app).first()
        if (host.isBlank()) return Outcome.RETRY

        val deviceId = ensureDeviceId(app)
        if (deviceId.isBlank()) return Outcome.RETRY
        val deviceName = SharedPreferenceHelperTV.getDeviceName(app).first()

        var socket: Socket? = null
        var ssl: SSLSocket? = null
        var out: DataOutputStream? = null
        var inp: DataInputStream? = null
        var heartbeat: Job? = null

        try {
            socket = Socket().apply { connect(InetSocketAddress(host, P2P_PORT), CONNECT_TIMEOUT_MS) }
            ssl = createTlsSocket(app, socket)
            ssl.startHandshake()

            if (!verifyServerFingerprint(app, ssl)) {
                lastError.value = "服务端证书指纹不匹配"
                return Outcome.RETRY
            }

            out = DataOutputStream(BufferedOutputStream(ssl.outputStream))
            inp = DataInputStream(BufferedInputStream(ssl.inputStream))

            sendHandshake(out, deviceId, deviceName)

            // 首个响应帧：握手被拒（handshake_rejected）或握手后首推（policy_update）
            socket.soTimeout = FIRST_FRAME_TIMEOUT_MS
            val first = runCatching { readMessage(inp) }.getOrNull()
            socket.soTimeout = 0

            if (first != null) {
                val rejection = parseRejection(first)
                if (rejection != null) {
                    val code = rejection.first
                    lastError.value = "${rejection.second}（$code）"
                    AppLogTV.w(TAG, "P2P 握手被拒：${rejection.second} code=$code")
                    return when {
                        code == "ip_rate_limited" -> Outcome.RATE_LIMITED
                        code in DETERMINISTIC_REJECTIONS || code == "unpaired" -> Outcome.DETERMINISTIC
                        else -> Outcome.RETRY
                    }
                }
                handleMessage(app, first)
            }

            connected.value = true
            lastError.value = ""
            AppLogTV.i(TAG, "P2P 已连接（$host:$P2P_PORT）")

            heartbeat = launchHeartbeat(out)
            receiveLoop(app, inp, socket)

            return Outcome.CONNECTED
        } finally {
            heartbeat?.cancel()
            connected.value = false
            runCatching { out?.close() }
            runCatching { inp?.close() }
            runCatching { ssl?.close() }
            runCatching { socket?.close() }
        }
    }

    private suspend fun ensureDeviceId(app: Context): String {
        val existing = SharedPreferenceHelperTV.getDeviceId(app).first()
        if (existing.isNotBlank()) return existing
        SharedPreferenceHelperTV.generateDeviceId(app)
        return SharedPreferenceHelperTV.getDeviceId(app).first()
    }

    /** 心跳：每 30s 一帧；发送失败即断开（由上层重连） */
    private fun launchHeartbeat(out: DataOutputStream): Job = scope.launch {
        while (isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            val ok = runCatching {
                writeFrame(out, TvP2pMessage(
                    type = "heartbeat",
                    payload = mapOf("timestamp" to System.currentTimeMillis() / 1000)
                ))
                true
            }.getOrDefault(false)
            if (!ok) {
                AppLogTV.w(TAG, "P2P 心跳发送失败，断开重连")
                return@launch
            }
        }
    }

    /** 接收循环：长时间收不到任何帧（含 heartbeat_ack）判定链路已死 */
    private suspend fun receiveLoop(app: Context, inp: DataInputStream, socket: Socket) {
        var lastFrameAt = System.currentTimeMillis()
        while (!stopped && connected.value) {
            val msg = try {
                socket.soTimeout = READ_TIMEOUT_MS
                readMessage(inp)
            } catch (e: SocketTimeoutException) {
                if (System.currentTimeMillis() - lastFrameAt > STALE_FRAME_MS) {
                    AppLogTV.w(TAG, "P2P 长时间无响应，断开重连")
                    return
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "P2P 接收异常: ${e.message}")
                return
            }
            lastFrameAt = System.currentTimeMillis()
            if (msg == null) continue
            handleMessage(app, msg)
        }
    }

    // ==================== 下行消息处理 ====================

    private suspend fun handleMessage(app: Context, msg: TvP2pMessage) {
        when (msg.type) {
            "policy_update" -> applyPolicyUpdate(app, JSONObject(msg.payload))
            "limit_reset" -> {
                // [FIX-2026-09-12] 走 applyRemoteReset：记重置基线（重置后重新计时）并真正解锁
                TimeoutExecutorTV.applyRemoteReset(app)
                AppLogTV.i(TAG, "P2P 指令：重置当日用量（实时）")
            }
            "announcement_push" -> showAnnouncements(app, JSONObject(msg.payload))
            "guard_state" -> {
                // [TASK-GUARD-TOGGLE-V1] 家长在 Web 端「设备管理」禁用/启用管控
                val enabled = JSONObject(msg.payload).optBoolean("enabled", true)
                AppLogTV.i(TAG, "P2P 指令：管控${if (enabled) "启用" else "停用"}（实时）")
                TimeoutExecutorTV.setGuardEnabled(app, enabled)
            }
            "announcement_clear" -> {
                // [首页内嵌公告框] 撤回/删除后同步从首页公告框移除
                val ids = JSONObject(msg.payload).optJSONArray("announcementIds")
                val removeIds = mutableSetOf<Int>()
                if (ids != null) for (i in 0 until ids.length()) removeIds.add(ids.optInt(i))
                AnnouncementStore.remove(removeIds)
                AppLogTV.i(TAG, "P2P 指令：公告清除 ${removeIds.size} 条（首页公告框同步移除）")
            }
            "update_available" -> AppLogTV.i(TAG, "P2P 通知：服务端有新版本可用（下次检查升级时生效）")
            "heartbeat_ack" -> Unit
            else -> Log.d(TAG, "P2P 未处理消息: ${msg.type}")
        }
    }

    /** policy_update：payload.policies 为 PolicyConfig JSON 字符串数组（2.0 格式） */
    private suspend fun applyPolicyUpdate(app: Context, payload: JSONObject) {
        val items = payload.optJSONArray("policies") ?: return

        for (i in 0 until items.length()) {
            val raw = items.optString(i, "")
            if (raw.isBlank()) continue
            val p = runCatching { JSONObject(raw) }.getOrNull() ?: continue

            when (p.optString("policyType")) {
                "daily_limit" -> {
                    val limit = p.optInt("limitMinutes", -1)
                    if (limit >= 0) {
                        SharedPreferenceHelperTV.saveDailyLimit(app, limit)
                        TimeoutExecutorTV.setDailyLimit(limit, app)
                        AppLogTV.i(TAG, "P2P 策略生效：每日限额 $limit 分钟")
                    }
                }
                "sleep_time" -> {
                    val s = p.optString("sleepStart", "").takeIf { it.isNotBlank() && it != "null" }
                    val e = p.optString("sleepEnd", "").takeIf { it.isNotBlank() && it != "null" }
                    SharedPreferenceHelperTV.saveBedtimeStart(app, s ?: "")
                    SharedPreferenceHelperTV.saveBedtimeEnd(app, e ?: "")
                    TimeoutExecutorTV.setBedtime(s, e)
                    AppLogTV.i(TAG, "P2P 策略生效：就寝时段 ${s ?: "未设置"}–${e ?: "未设置"}")
                }
                else -> Unit // 分类/白名单/黑名单：TV 端分类限额未启用（与 Web 端一致）
            }
        }

        // app_categories：数组 [{packageName, appName, category}] → 存成 TV 端使用的 {包名: 分类} 映射
        val cats = payload.optJSONArray("app_categories") ?: return
        val map = JSONObject()
        for (i in 0 until cats.length()) {
            val o = cats.optJSONObject(i) ?: continue
            val pkg = o.optString("packageName", "")
            if (pkg.isNotBlank()) map.put(pkg, o.optString("category", "other"))
        }
        if (map.length() > 0) {
            SharedPreferenceHelperTV.saveAppCategories(app, map.toString())
            AppLogTV.i(TAG, "P2P 应用分类已更新（${map.length()} 个应用）")
        }
    }

    /** announcement_push：payload.announcements 的 priority 是数字（0/1/2），弹窗要字符串 */
    private fun showAnnouncements(app: Context, payload: JSONObject) {
        val arr = payload.optJSONArray("announcements") ?: return
        // [首页内嵌公告框] 实时推送同样写进共享状态（新发布/编辑后立刻出现在首页）
        runCatching { AnnouncementStore.upsertFromPush(arr) }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString() ?: continue
            if (id.isBlank() || id == "null") continue
            out.put(JSONObject().apply {
                put("id", id)
                put("title", o.optString("title", "(无标题)"))
                put("content", o.optString("content", ""))
                put("priority", when (o.optInt("priority", 0)) {
                    2 -> "urgent"
                    1 -> "important"
                    else -> "normal"
                })
            })
        }
        if (out.length() == 0) return

        AppLogTV.i(TAG, "P2P 收到云端公告 ${out.length()} 条，弹出展示（实时）")
        runCatching {
            app.startActivity(
                Intent(app, AnnouncementOverlayActivity::class.java)
                    .putExtra(AnnouncementOverlayActivity.EXTRA_JSON, out.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { AppLogTV.w(TAG, "弹出公告失败: ${it.message}") }
    }

    // ==================== 帧收发 ====================

    private fun sendHandshake(out: DataOutputStream, deviceId: String, deviceName: String) {
        val payload = mutableMapOf<String, Any>(
            "version" to "1.0",
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "deviceType" to "android-tv",
            "timestamp" to System.currentTimeMillis() / 1000,
            "relay" to true
        )
        // 说明：不携带 certFingerprint —— 服务端一律以 TLS 对端证书指纹为准
        // （P2pMessageHandler：payload 自报值不作信任依据）
        writeFrame(out, TvP2pMessage("handshake", payload))
    }

    private fun writeFrame(out: DataOutputStream, msg: TvP2pMessage) {
        val body = msg.toJson().toByteArray(Charsets.UTF_8)
        out.writeInt(body.size)  // DataOutputStream 默认大端
        out.write(body)
        out.flush()
    }

    private fun readMessage(inp: DataInputStream): TvP2pMessage? {
        val length = inp.readInt()  // 连接关闭时抛 EOFException
        if (length <= 0 || length > 1_048_576) {
            Log.w(TAG, "非法帧长度: $length")
            return null
        }
        val body = ByteArray(length)
        inp.readFully(body)
        return TvP2pMessage.fromJson(String(body, Charsets.UTF_8))
    }

    /** 拒绝帧解析：{type:handshake_rejected, error, error_code} → (code, reason) */
    private fun parseRejection(msg: TvP2pMessage): Pair<String, String>? {
        if (msg.type != "handshake_rejected") return null
        @Suppress("UNCHECKED_CAST")
        val code = (msg.payload["error_code"] as? String ?: "").ifBlank { "ip_rate_limited" }
        val reason = msg.payload["error"] as? String ?: "连接被拒绝"
        return code to reason
    }

    // ==================== TLS（mTLS + 服务端指纹 TOFU 固定） ====================

    private fun createTlsSocket(app: Context, socket: Socket): SSLSocket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(TvP2pIdentity.keyManagerFactory(app).keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())

        val ssl = ctx.socketFactory.createSocket(
            socket, socket.inetAddress.hostAddress, socket.port, true
        ) as SSLSocket

        // Android 5.1 无 TLS 1.3：按实际支持能力取交集（服务端 TLS 1.2+1.3 均支持）
        val supported = ssl.supportedProtocols.toSet()
        ssl.enabledProtocols = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
        ssl.enabledCipherSuites = ssl.supportedCipherSuites.filter {
            it.startsWith("TLS_AES") || it.startsWith("TLS_CHACHA") ||
                (it.startsWith("TLS_ECDHE") && it.contains("AES") && it.contains("GCM"))
        }.toTypedArray()
        return ssl
    }

    /** 服务端证书指纹固定：首次连接记录（TOFU），此后必须一致 */
    private fun verifyServerFingerprint(app: Context, ssl: SSLSocket): Boolean {
        val cert = (ssl.session.peerCertificates.firstOrNull() as? X509Certificate) ?: return false
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        val prefs = app.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val pinned = prefs.getString(PREF_SERVER_FP, "").orEmpty()
        if (pinned.isBlank()) {
            prefs.edit().putString(PREF_SERVER_FP, fp).apply()
            AppLogTV.i(TAG, "首次连接，已固定服务端证书指纹 ${fp.take(16)}…")
            return true
        }
        if (pinned != fp) {
            AppLogTV.w(TAG, "服务端证书指纹不匹配（期望 ${pinned.take(16)}… 实际 ${fp.take(16)}…），拒绝连接")
            return false
        }
        return true
    }
}
