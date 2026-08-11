package com.xiaopacai.child.p2p

import android.content.Context
import android.util.Log
import com.xiaopacai.child.XiaopacaiApp
import com.xiaopacai.child.service.GuardianForegroundService
import com.xiaopacai.child.util.DbPassphraseProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.sqlcipher.database.SQLiteDatabase

/**
 * [TASK-D1-04] 配对管理器
 *
 * 统一管理 P2P 发现、连接、配对流程。
 * 协调 DiscoveryService 和 ConnectionService，
 * 提供简化的 API 给 UI 层调用。
 */

/** 配对流程状态 */
enum class PairingState {
    IDLE,           // 空闲
    SCANNING,       // 扫描中（查找家长端）
    FOUND_PARENT,   // 已发现家长端
    PAIRING,        // 配对中
    CONNECTED,      // 已连接
    ERROR           // 错误
}

class PairingManager(private val context: Context) {

    companion object {
        private const val TAG = "PairingManager"
    }

    private val discoveryService = P2PDiscoveryService()
    // [FIX-LEGACY-a] 使用 GuardianForegroundService 共享的 P2PConnectionService 实例
    // UI 配对后 usage_report 走同一条 TLS 链路
    private val connectionService = GuardianForegroundService.getP2PConnection()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 配对状态 */
    private val _pairingState = MutableStateFlow(PairingState.IDLE)
    val pairingState: StateFlow<PairingState> = _pairingState

    init {
        // [FIX-LEGACY-a] 持续监听共享连接的实时状态，同步到 pairingState
        // 确保 UI 卡片显示真实的连接状态（跨组件/跨生命周期）
        scope.launch {
            connectionService.connectionState.collect { state ->
                when (state) {
                    P2PConnectionState.CONNECTED -> {
                        if (_pairingState.value != PairingState.CONNECTED) {
                            _pairingState.value = PairingState.CONNECTED
                        }
                    }
                    P2PConnectionState.DISCONNECTED -> {
                        if (_pairingState.value == PairingState.CONNECTED ||
                            _pairingState.value == PairingState.PAIRING) {
                            _pairingState.value = PairingState.IDLE
                        }
                    }
                    else -> { /* CONNECTING / HANDSHAKING / RECONNECTING 不覆盖 UI 状态 */ }
                }
            }
        }
    }

    /** P2P-FIX: 暴露发现的家长端列表供 UI 使用 */
    val discoveredParents: StateFlow<List<DiscoveredParent>> = discoveryService.discoveredParents

    /**
     * 开始扫描家长端设备
     * 并行使用 mDNS 和 UDP 广播发现
     */
    fun startScanning() {
        _pairingState.value = PairingState.SCANNING

        scope.launch {
            discoveryService.startDiscovery(scope)

            // 监听发现结果
            launch {
                discoveryService.discoveredParents.collect { parents ->
                    if (parents.isNotEmpty() && _pairingState.value == PairingState.SCANNING) {
                        _pairingState.value = PairingState.FOUND_PARENT
                    }
                }
            }
        }
    }

    /**
     * 停止扫描
     */
    fun stopScanning() {
        discoveryService.stopDiscovery()
        if (_pairingState.value == PairingState.SCANNING) {
            _pairingState.value = PairingState.IDLE
        }
    }

    /**
     * 连接到选定的家长端
     * @param parent 选中的家长端设备
     * @param pairingCode 配对码（6 位数字）
     */
    fun connectToParent(parent: DiscoveredParent, pairingCode: String) {
        _pairingState.value = PairingState.PAIRING

        // 从数据库读取已保存的证书指纹
        val savedFingerprint = getSavedFingerprint(parent.deviceId)

        scope.launch {
            connectionService.connect(
                host = parent.host,
                port = parent.port,
                expectedFingerprint = savedFingerprint,
                deviceId = getLocalDeviceId(),
                deviceName = getLocalDeviceName(),
                pairingCode = pairingCode,
                scope = scope
            )

            // 监控连接状态
            launch {
                connectionService.connectionState.collect { state ->
                    when (state) {
                        P2PConnectionState.CONNECTED -> {
                            _pairingState.value = PairingState.CONNECTED

                            // 保存证书指纹（首次配对或指纹更新）
                            val fingerprint = connectionService.getConnectedFingerprint()
                            if (fingerprint != null) {
                                saveFingerprint(parent.deviceId, fingerprint)
                            }
                        }
                        P2PConnectionState.DISCONNECTED -> {
                            if (_pairingState.value == PairingState.PAIRING ||
                                _pairingState.value == PairingState.CONNECTED) {
                                _pairingState.value = PairingState.IDLE
                            }
                        }
                        else -> { /* 中间状态不改变 */ }
                    }
                }
            }
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        connectionService.disconnect()
        _pairingState.value = PairingState.IDLE
    }

    /**
     * 手动添加家长端（IP 直连兜底）
     */
    fun addManualParent(host: String, port: Int): DiscoveredParent {
        return discoveryService.addManualParent(host, port)
    }

    // === 持久化 ===

    /** 从加密数据库读取已保存的证书指纹 */
    private fun getSavedFingerprint(deviceId: String): String? {
        val db = XiaopacaiApp.instance.database
            .getReadable(getDbPassphrase())
        return try {
            val cursor = db.rawQuery(
                "SELECT cert_fingerprint FROM pairing_info WHERE parent_id = ?",
                arrayOf(deviceId)
            )
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取指纹失败", e)
            null
        }
    }

    /** 保存证书指纹到加密数据库 */
    private fun saveFingerprint(deviceId: String, fingerprint: String) {
        val db = XiaopacaiApp.instance.database
            .getWritable(getDbPassphrase())
        try {
            db.execSQL("""
                INSERT OR REPLACE INTO pairing_info
                (parent_id, cert_fingerprint, last_connected_at, is_active)
                VALUES (?, ?, strftime('%s', 'now'), 1)
            """.trimIndent(), arrayOf(deviceId, fingerprint))
        } catch (e: Exception) {
            Log.e(TAG, "保存指纹失败", e)
        }
    }

    /** 获取本机设备 ID */
    private fun getLocalDeviceId(): String {
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = "XP-" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12).uppercase()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    /** 获取本机设备名称 */
    private fun getLocalDeviceName(): String {
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_name", android.os.Build.MODEL) ?: "未知设备"
    }

    /** 获取数据库密钥 */
    private fun getDbPassphrase(): ByteArray {
        return DbPassphraseProvider.getPassphrase(context)
    }

    fun destroy() {
        // [FIX-LEGACY-a] 不再断开共享 P2P 连接（连接由 GuardianForegroundService 管理生命周期）
        // 仅停止扫描和取消协程作用域
        stopScanning()
        scope.cancel()
    }
}
