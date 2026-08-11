package com.xiaopacai.child.debug

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.data.database.AnnouncementDao
import com.xiaopacai.child.p2p.P2PConnectionService
import com.xiaopacai.child.p2p.ParentP2PListenerService
import com.xiaopacai.child.service.AntiBypassService
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.service.UsageStatsCollector
import com.xiaopacai.child.ui.BlockOverlayActivity
import com.xiaopacai.child.util.DbPassphraseProvider
import kotlinx.coroutines.launch

/**
 * [TEST-ONLY] 模拟器 GUI 走查调试触发器（仅 debug 构建存在）
 *
 * 通过 adb 以 intent extra 驱动测试场景：
 *
 * === 儿童端（原有）===
 *   action=start_service  拉起守护前台服务（真实用户路径为开机广播，此处为测试捷径）
 *   action=seed           注入策略缓存（daily_limit=1 分钟、黑白名单、分类限额）与测试公告
 *   action=seed_highlimit 注入高限额策略（daily_limit=999999），供 IME 豁免验证（采集器不触发超时）
 *   action=collect        立即执行一次时长采集 + 超时判定（触发整机停用 BlockOverlay）
 *   action=partial        反射设置 collector 为超时 partial 模式（受限守护，供拦截演示）
 *   action=fullstate      反射设置 collector 为超时 full 模式（不触发 TimeoutExecutor 覆盖层，供 IME 豁免验证）
 *   action=reset          恢复 collector 为正常状态
 *   action=overlay        直接展示 BlockOverlay（携带 targetPackage/reason 参数）
 *   action=notify         触发一条安全告警通知（防绕过告警演示）
 *   action=pair           直接以 P2PConnectionService 连接家长端（默认 10.0.2.2:9527，可用 host/port 参数覆盖）
 *   action=pair_report    连接家长端后发送一条 usage_report 并等待 sync_ack（验证 TLS 上时长上报链路）
 *   action=pair_shared    使用 GuardianForegroundService 共享连接连接家长端（供 SyncManager 断网重试验证）
 *   action=home           返回桌面（关闭覆盖界面）
 *
 * === 家长端（P2 新增）===
 *   action=parent_start         启动 ParentP2PListenerService 监听端口 9527
 *   action=parent_stop          停止 ParentP2PListenerService
 *   action=parent_paircode      生成配对码并 Toast 展示
 *   action=parent_seedpolicy    向 parent_policies 注入测试策略（daily_limit=60min/游戏30min/就寝22:00-06:00）
 *   action=parent_seedannounce  向 parent_announcements 注入 3 条测试公告
 *   action=parent_fulldata      一键注入全部家长端测试数据（策略+公告+设备注册记录）
 *   action=parent_setup         直接设置家长角色与密码（跳过 UI 引导，供联调自动化）
 *
 * 该组件不进入 release 构建，仅用于测试环境，不构成产品功能。
 */
class DebugTriggerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DebugTrigger"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action") ?: "home"
        Log.i(TAG, "action=$action")

        try {
            when (action) {
                "start_service" -> startService()
                "seed" -> seedData()
                "seed_highlimit" -> seedHighLimit()
                "collect" -> collectNow()
                "partial" -> setPartialMode()
                "fullstate" -> setFullState()
                "reset" -> resetMode()
                "overlay" -> showOverlay()
                "notify" -> sendSecurityNotify()
                "pair" -> pairWithParent()
                "pair_report" -> pairAndReport()
                "pair_shared" -> pairShared()
                // === 家长端（P2 新增）===
                "parent_start" -> parentStart()
                "parent_stop" -> parentStop()
                "parent_paircode" -> parentPairCode()
                "parent_seedpolicy" -> parentSeedPolicy()
            "parent_seedannounce" -> parentSeedAnnounce()
            "parent_fulldata" -> parentFullData()
            "parent_setup" -> parentSetup()
                else -> goHome()
            }
            toast("DebugTrigger: $action ok")
        } catch (e: Exception) {
            Log.e(TAG, "action=$action failed: ${e.message}", e)
            toast("DebugTrigger: $action failed: ${e.message}")
        }
        finish()
    }

    private fun startService() {
        GuardianForegroundService.start(this)
    }

    private fun seedData() {
        val passphrase = DbPassphraseProvider.getPassphrase(this)
        val db = XiaopacaiApp.instance.database
        val writable = db.getWritable(passphrase)
        val now = (System.currentTimeMillis() / 1000).toString()
        try {
            // 1 分钟限额策略（整机停用 full）
            writable.execSQL(
                """INSERT OR REPLACE INTO policy_cache (policy_type, policy_data, version, applied_at)
                   VALUES (?, ?, ?, ?)""",
                arrayOf(
                    "daily_limit",
                    """{"policyType":"daily_limit","limitMinutes":1,"restrictMode":"full"}""",
                    "1",
                    now
                )
            )
            // 白名单（超时后仍可用）
            writable.execSQL(
                """INSERT OR REPLACE INTO policy_cache (policy_type, policy_data, version, applied_at)
                   VALUES (?, ?, ?, ?)""",
                arrayOf(
                    "whitelist",
                    """{"policyType":"whitelist","packages":["com.xiaopacai.child"]}""",
                    "1",
                    now
                )
            )
            // 黑名单（始终拦截）
            writable.execSQL(
                """INSERT OR REPLACE INTO policy_cache (policy_type, policy_data, version, applied_at)
                   VALUES (?, ?, ?, ?)""",
                arrayOf(
                    "blacklist",
                    """{"policyType":"blacklist","packages":["com.android.calculator2"]}""",
                    "1",
                    now
                )
            )
            // 分类限额：娱乐类 1 分钟（partial 拦截演示）
            writable.execSQL(
                """INSERT OR REPLACE INTO policy_cache (policy_type, policy_data, version, applied_at)
                   VALUES (?, ?, ?, ?)""",
                arrayOf(
                    "category_limit",
                    """{"policyType":"category_limit","category":"game","categoryLimitMinutes":1}""",
                    "1",
                    now
                )
            )
        } finally {
            writable.close()
        }

        // 注入测试公告（普通/重要/紧急三档）
        val dao = AnnouncementDao(db)
        dao.upsert("test-ann-1", "周末使用提醒", "记得按时休息，保护眼睛哦。", 0, 0, passphrase)
        dao.upsert("test-ann-2", "学习任务更新", "本周学习计划已由家长端更新，请查看。", 1, 0, passphrase)
        dao.upsert("test-ann-3", "紧急通知", "今晚 21:00 前需要完成在线课程签到。", 2, 0, passphrase)
    }

    private fun seedHighLimit() {
        val passphrase = DbPassphraseProvider.getPassphrase(this)
        val writable = XiaopacaiApp.instance.database.getWritable(passphrase)
        val now = (System.currentTimeMillis() / 1000).toString()
        try {
            writable.execSQL(
                """INSERT OR REPLACE INTO policy_cache (policy_type, policy_data, version, applied_at)
                   VALUES (?, ?, ?, ?)""",
                arrayOf(
                    "daily_limit",
                    """{"policyType":"daily_limit","limitMinutes":999999,"restrictMode":"full"}""",
                    "1",
                    now
                )
            )
        } finally {
            writable.close()
        }
    }

    private fun collectNow() {
        var collector = GuardianForegroundService.getCollector()
        if (collector == null) {
            // 服务尚未初始化完成，先启动并等待
            GuardianForegroundService.start(this)
            var retry = 0
            while (collector == null && retry < 10) {
                Thread.sleep(1000)
                collector = GuardianForegroundService.getCollector()
                retry++
            }
        }
        if (collector == null) {
            throw IllegalStateException("collector not ready")
        }
        collector.collectAndPersist()
    }

    private fun setPartialMode() {
        val collector = requireCollector()
        setField(collector, "_isTimeoutActive", true)
        setField(collector, "_stopMode", "partial")
    }

    private fun setFullState() {
        val collector = requireCollector()
        setField(collector, "_isTimeoutActive", true)
        setField(collector, "_stopMode", "full")
    }

    private fun resetMode() {
        val collector = GuardianForegroundService.getCollector() ?: return
        setField(collector, "_isTimeoutActive", false)
        setField(collector, "_stopMode", "none")
    }

    private fun showOverlay() {
        val intent = android.content.Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra("target_package", intent.getStringExtra("targetPackage") ?: "com.android.chrome")
            putExtra("reason", intent.getStringExtra("reason") ?: "使用时长已超限（测试）")
        }
        startActivity(intent)
    }

    private fun sendSecurityNotify() {
        AntiBypassService.notifySecurityEvent(
            this,
            "防绕过告警（测试）",
            "检测到无障碍服务被关闭，守护拦截可能失效，请家长尽快检查。"
        )
    }

    private fun pairWithParent() {
        val host = intent.getStringExtra("host") ?: "10.0.2.2"
        val port = intent.getIntExtra("port", 9527)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
        )
        val service = P2PConnectionService()
        scope.launch {
            service.connect(
                host = host,
                port = port,
                expectedFingerprint = null,
                deviceId = "XP-DEBUG-PROBE",
                deviceName = "模拟器测试设备",
                scope = scope
            )
        }
        Log.i(TAG, "pair initiated: $host:$port")
    }

    private fun pairAndReport() {
        val host = intent.getStringExtra("host") ?: "10.0.2.2"
        val port = intent.getIntExtra("port", 9527)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
        )
        val service = P2PConnectionService()
        scope.launch {
            service.connect(
                host = host,
                port = port,
                expectedFingerprint = null,
                deviceId = "XP-DEBUG-REPORT",
                deviceName = "模拟器测试设备",
                scope = scope
            )
            // 等待连接建立后发送时长上报
            service.connectionState.collect { state ->
                if (state == com.xiaopacai.child.p2p.P2PConnectionState.CONNECTED) {
                    val records = org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("packageName", "com.android.chrome")
                            put("appName", "Chrome")
                            put("date", "2026-08-10")
                            put("totalMinutes", 25)
                            put("category", "other")
                        })
                    }
                    val ok = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO
                    ) {
                        service.sendMessage(
                            com.xiaopacai.child.p2p.P2PMessage(
                                type = "usage_report",
                                payload = mapOf(
                                    "deviceId" to "XP-DEBUG-REPORT",
                                    "records" to records.toString(),
                                    "timestamp" to (System.currentTimeMillis() / 1000)
                                )
                            )
                        )
                    }
                    Log.i(TAG, "usage_report sent over TLS: ok=$ok")
                }
            }
            // 记录收到的消息载荷（验证 sync_ack 计数）
            service.receivedMessages.collect { messages ->
                messages.filter { it.type == "sync_ack" || it.type == "policy_update" }
                    .forEach { msg ->
                        Log.i(TAG, "RECEIVED ${msg.type}: ${msg.payload}")
                    }
            }
        }
    }

    private fun pairShared() {
        val host = intent.getStringExtra("host") ?: "10.0.2.2"
        val port = intent.getIntExtra("port", 9527)
        val pairingCode = intent.getStringExtra("pairingCode")
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
        )
        scope.launch {
            com.xiaopacai.child.service.GuardianForegroundService.getP2PConnection()
                .connect(
                    host = host,
                    port = port,
                    expectedFingerprint = null,
                    deviceId = "XP-SHARED",
                    deviceName = "模拟器测试设备",
                    pairingCode = pairingCode,
                    scope = scope
                )
            Log.i(TAG, "shared connection pair initiated: $host:$port")
        }
    }

    // ==================== 家长端调试触发器（P2 新增）====================

    private fun parentStart() {
        ParentP2PListenerService.start(this)
        Log.i(TAG, "ParentP2PListenerService started")
    }

    private fun parentStop() {
        ParentP2PListenerService.stop(this)
        Log.i(TAG, "ParentP2PListenerService stopped")
    }

    private fun parentPairCode() {
        parentStart() // 确保服务已启动
        // 使用反射获取实例并生成配对码
        try {
            val f = ParentP2PListenerService::class.java.getDeclaredField("instance")
            f.isAccessible = true
            val svc = f.get(null) as? ParentP2PListenerService
            if (svc != null) {
                val code = svc.generatePairingCode()
                val fingerprint = svc.getCertificateFingerprint().take(16) + "..."
                Log.i(TAG, "Pairing code: $code, fingerprint: $fingerprint")
                toast("配对码: $code\n指纹: $fingerprint")
            } else {
                Log.w(TAG, "ParentP2PListenerService instance not found")
                toast("实例未找到，请确认服务已启动")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parent_paircode failed: ${e.message}", e)
            toast("失败: ${e.message}")
        }
    }

    /**
     * 向家长端数据库注入测试策略
     *
     * 将测试策略写入 parent_policies 表，供 ParentHomeScreen 策略配置页展示。
     */
    private fun parentSeedPolicy() {
        val passphrase = DbPassphraseProvider.getPassphrase(this)
        val db = XiaopacaiApp.instance.database
        val writable = db.getWritable(passphrase)
        val now = (System.currentTimeMillis() / 1000).toString()
        try {
            // 注册测试设备
            writable.execSQL("""
                INSERT OR REPLACE INTO device_registry
                (device_id, device_name, cert_fingerprint, last_connected_at, is_active)
                VALUES (?, ?, ?, ?, 1)
            """.trimIndent(), arrayOf(
                "XP-DEBUG-DEVICE", "模拟器测试设备",
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
                now
            ))

            // 每日限额 60 分钟
            writable.execSQL("""
                INSERT OR REPLACE INTO parent_policies
                (policy_id, policy_type, policy_name, policy_data, target_device_id, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
            """.trimIndent(), arrayOf(
                java.util.UUID.randomUUID().toString(), "daily_limit", "每日限额60分钟",
                """{"policyType":"daily_limit","limitMinutes":60,"restrictMode":"full"}""",
                "", "1", now, now
            ))

            // 就寝时段 22:00-06:00
            writable.execSQL("""
                INSERT OR REPLACE INTO parent_policies
                (policy_id, policy_type, policy_name, policy_data, target_device_id, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
            """.trimIndent(), arrayOf(
                java.util.UUID.randomUUID().toString(), "bedtime", "就寝时段22:00-06:00",
                """{"policyType":"bedtime","startTime":"22:00","endTime":"06:00"}""",
                "", "1", now, now
            ))

            // 分类限额：游戏 30 分钟
            writable.execSQL("""
                INSERT OR REPLACE INTO parent_policies
                (policy_id, policy_type, policy_name, policy_data, target_device_id, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
            """.trimIndent(), arrayOf(
                java.util.UUID.randomUUID().toString(), "category_limit", "游戏类限额30分钟",
                """{"policyType":"category_limit","category":"game","categoryLimitMinutes":30}""",
                "", "1", now, now
            ))

            // 白名单
            writable.execSQL("""
                INSERT OR REPLACE INTO parent_policies
                (policy_id, policy_type, policy_name, policy_data, target_device_id, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
            """.trimIndent(), arrayOf(
                java.util.UUID.randomUUID().toString(), "whitelist", "白名单",
                """{"policyType":"whitelist","packages":["com.xiaopacai.child","com.android.contacts","com.android.phone"]}""",
                "", "1", now, now
            ))

            // 黑名单
            writable.execSQL("""
                INSERT OR REPLACE INTO parent_policies
                (policy_id, policy_type, policy_name, policy_data, target_device_id, is_active, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
            """.trimIndent(), arrayOf(
                java.util.UUID.randomUUID().toString(), "blacklist", "黑名单",
                """{"policyType":"blacklist","packages":["com.android.calculator2","com.android.gallery3d"]}""",
                "", "1", now, now
            ))

            Log.i(TAG, "parent_seedpolicy: 注入 1 设备 + 5 策略完成")
            toast("已注入 1 台设备 + 5 条策略")
        } finally {
            writable.close()
        }
    }

    /**
     * 向家长端数据库注入测试公告
     */
    private fun parentSeedAnnounce() {
        val passphrase = DbPassphraseProvider.getPassphrase(this)
        val db = XiaopacaiApp.instance.database
        val writable = db.getWritable(passphrase)
        val now = (System.currentTimeMillis() / 1000).toString()
        try {
            // 普通公告
            writable.execSQL("""
                INSERT OR REPLACE INTO parent_announcements
                (announcement_id, title, content, priority, status, target_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), arrayOf(
                "parent-ann-1", "周末使用提醒",
                "记得按时休息，保护眼睛哦。每天户外活动至少1小时。",
                "0", "draft", "XP-DEBUG-DEVICE", now, now
            ))

            // 重要公告
            writable.execSQL("""
                INSERT OR REPLACE INTO parent_announcements
                (announcement_id, title, content, priority, status, target_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), arrayOf(
                "parent-ann-2", "学习计划更新",
                "本周数学练习需要完成第5章全部习题，请在周五前提交。",
                "1", "draft", "XP-DEBUG-DEVICE", now, now
            ))

            // 紧急公告（广播）
            writable.execSQL("""
                INSERT OR REPLACE INTO parent_announcements
                (announcement_id, title, content, priority, status, target_device_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), arrayOf(
                "parent-ann-3", "紧急通知",
                "今晚22:00系统维护升级，服务暂时不可用，预计持续30分钟。",
                "2", "published", null, now, now
            ))

            Log.i(TAG, "parent_seedannounce: 注入 3 条公告完成")
            toast("已注入 3 条公告 (1 紧急已发布 + 2 草稿)")
        } finally {
            writable.close()
        }
    }

    /**
     * 一键注入全部家长端测试数据
     */
    private fun parentFullData() {
        parentSeedPolicy()
        parentSeedAnnounce()
        Log.i(TAG, "parent_fulldata: 全部家长端测试数据注入完成")
        toast("家长端测试数据注入完成:\n1 设备 + 5 策略 + 3 公告")
    }

    private fun parentSetup() {
        val pwd = intent.getStringExtra("password") ?: "123456"
        val okPwd = com.xiaopacai.child.role.RoleManager.setParentPassword(this, pwd)
        val okRole = com.xiaopacai.child.role.RoleManager.setCurrentRole(
            this, com.xiaopacai.child.role.RoleManager.Role.PARENT
        )
        Log.i(TAG, "parent_setup: setPwd=$okPwd setRole=$okRole")
    }

    private fun goHome() {
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
    }

    private fun requireCollector(): UsageStatsCollector {
        return GuardianForegroundService.getCollector()
            ?: throw IllegalStateException("collector not ready, run action=start_service first")
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
