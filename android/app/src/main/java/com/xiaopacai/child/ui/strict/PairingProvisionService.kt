package com.xiaopacai.child.ui.strict

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.R
import com.xiaopacai.child.adbshell.AdbOutputParser
import com.xiaopacai.child.adbshell.AdbRunner
import com.xiaopacai.child.adbshell.ProvisionMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [TASK-STRICT-PROVISION-V1] 强管制后台预置服务（ADR 0018 v1.3.3）
 *
 * 由 PairingCodeReceiver 在收到通知栏配对码后启动；前台服务保证 adb server
 * 启动与配对流程（数秒~数十秒）不被系统回收，进度通过通知实时呈现。
 *
 * v1.3.3 关键简化（OPPO 真机反复验证后确定）：
 * - 配对走本机回环 `127.0.0.1:<配对端口>`，无需 mDNS/组播锁/主机发现
 *   （真机验证：`adb pair 127.0.0.1:<port> <code>` 成功）；
 * - 配对成功后 adb server 会利用配对握手信息自动回连设备
 *   （真机验证：`adb devices` 立即出现 `_adb-tls-connect._tcp` 的 device 条目），
 *   连接端口无需用户输入、也无需 mDNS；
 * - 最后经该自动回连会话执行 dpm set-device-owner。
 * 用户只需输入「配对端口:配对码」（两者都在配对弹窗上显示）。
 */
class PairingProvisionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PairingCodeNotification.ensureChannel(this)
        startForeground(PairingCodeNotification.NOTIFICATION_ID, buildForegroundNotification())
        val code = intent?.getStringExtra(PairingCodeNotification.EXTRA_CODE)
        val pairPort = intent?.getIntExtra(EXTRA_PAIR_PORT, 0)
        if (code != null && pairPort != null && pairPort in 1..65535) {
            runProvision(code, pairPort)
        }
        return START_NOT_STICKY
    }

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, PairingCodeNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("强管制配对进行中")
            .setContentText("正在处理配对码…")
            .setOngoing(true)
            .build()

    private fun runProvision(code: String, pairPort: Int) {
        scope.launch {
            postRunning("正在配对…")
            val runner = AdbRunner.create(this@PairingProvisionService)
            val pairRes = runner.pair("127.0.0.1", pairPort, code)
            if (pairRes == null ||
                AdbOutputParser.classifyPair(pairRes.exitCode, pairRes.output)
                != AdbOutputParser.PairOutcome.SUCCESS
            ) {
                finishWithFailure(
                    "配对失败：${pairRes?.output?.ifBlank { "请检查配对端口与配对码" } ?: "adb 执行失败"}",
                    ProvisionMachine.ProvisionError.PAIR_FAILED
                )
                return@launch
            }

            postRunning("正在连接无线调试…")
            val serial = awaitConnectedDevice(runner, 15_000)
            if (serial == null) {
                finishWithFailure(
                    "已配对但未能自动连接：请重新点按「使用配对码配对设备」获取新配对码后重试",
                    ProvisionMachine.ProvisionError.CONNECTION_FAILED
                )
                return@launch
            }

            postRunning("正在执行系统级预置（dpm）…")
            val dpmRes = runner.shell(
                serial,
                "dpm set-device-owner com.xiaopacai.child/.service.GuardianDeviceAdminReceiver"
            )
            runner.killServer()
            val outcomeDpm = if (dpmRes == null) {
                AdbOutputParser.DpmOutcome.UNKNOWN_FAILURE
            } else {
                AdbOutputParser.classifyDpm(dpmRes.exitCode, dpmRes.output)
            }
            if (outcomeDpm == AdbOutputParser.DpmOutcome.SUCCESS) {
                PairingStatusStore.post(PairingStatusStore.Status.Succeeded)
                PairingCodeNotification.showResult(
                    this@PairingProvisionService,
                    "强管制模式已激活 ✓",
                    "小趴菜已是本设备的 Device Owner。"
                )
            } else {
                val msg = when (outcomeDpm) {
                    AdbOutputParser.DpmOutcome.ACCOUNTS_PRESENT ->
                        "设备存在账号，请恢复出厂或在无账号状态下操作"
                    AdbOutputParser.DpmOutcome.ALREADY_DEVICE_OWNER ->
                        "设备已是 Device Owner，无需重复预置"
                    AdbOutputParser.DpmOutcome.TEST_ONLY_BUILD ->
                        "当前为调试包，请安装正式版本"
                    AdbOutputParser.DpmOutcome.ROM_REJECTED ->
                        "本机型系统拒绝第三方 Device Owner 预置"
                    else -> "预置失败：${dpmRes?.output?.ifBlank { "未知错误" } ?: "adb 执行失败"}"
                }
                finishWithFailure(
                    msg,
                    when (outcomeDpm) {
                        AdbOutputParser.DpmOutcome.ACCOUNTS_PRESENT ->
                            ProvisionMachine.ProvisionError.DPM_ACCOUNTS_PRESENT
                        AdbOutputParser.DpmOutcome.ALREADY_DEVICE_OWNER ->
                            ProvisionMachine.ProvisionError.DPM_ALREADY_SET
                        AdbOutputParser.DpmOutcome.TEST_ONLY_BUILD ->
                            ProvisionMachine.ProvisionError.DPM_TEST_ONLY
                        AdbOutputParser.DpmOutcome.ROM_REJECTED ->
                            ProvisionMachine.ProvisionError.DPM_ROM_REJECTED
                        else -> ProvisionMachine.ProvisionError.DPM_UNKNOWN
                    },
                    dpmOutcome = outcomeDpm
                )
            }
            stopSelf()
        }
    }

    /**
     * 轮询 `adb devices`，等待配对握手后 adb server 自动回连的 device 条目。
     * 返回 serial（如 `adb-XXX._adb-tls-connect._tcp.`），超时返回 null。
     */
    private suspend fun awaitConnectedDevice(runner: AdbRunner, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val res = runner.devices()
            if (res != null && res.exitCode == 0) {
                val serial = AdbOutputParser.parseConnectedDeviceSerial(res.output)
                if (serial != null) return serial
            }
            delay(500)
        }
        return null
    }

    private fun postRunning(stepText: String) {
        PairingStatusStore.post(PairingStatusStore.Status.Running(stepText))
        PairingCodeNotification.update(this, "强管制配对进行中", stepText)
    }

    private fun finishWithFailure(
        message: String,
        error: ProvisionMachine.ProvisionError,
        dpmOutcome: AdbOutputParser.DpmOutcome? = null
    ) {
        PairingStatusStore.post(
            PairingStatusStore.Status.Failed(message, error, dpmOutcome)
        )
        PairingCodeNotification.showResult(this, "强管制配对失败", message)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAIR_PORT = "pair_port"

        fun start(context: Context, code: String, pairPort: Int) {
            val intent = Intent(context, PairingProvisionService::class.java)
                .putExtra(PairingCodeNotification.EXTRA_CODE, code)
                .putExtra(EXTRA_PAIR_PORT, pairPort)
            context.startForegroundService(intent)
        }
    }
}
