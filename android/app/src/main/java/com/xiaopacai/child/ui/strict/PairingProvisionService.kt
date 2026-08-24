package com.xiaopacai.child.ui.strict

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xiaopacai.child.R
import com.xiaopacai.child.adbshell.AdbOutputParser
import com.xiaopacai.child.adbshell.AdbPairingDiscovery
import com.xiaopacai.child.adbshell.AdbRunner
import com.xiaopacai.child.adbshell.ProvisionMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * [TASK-STRICT-PROVISION-V1] 强管制后台预置服务（ADR 0018 v1.3.2）
 *
 * 由 PairingCodeReceiver 在收到通知栏配对码后启动；前台服务保证 adb server
 * 启动与配对流程（数秒~数十秒）不被系统回收，进度通过通知实时呈现。
 * 流程：mDNS 发现（持组播锁）→ adb pair → connect → dpm set-device-owner。
 */
class PairingProvisionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PairingCodeNotification.ensureChannel(this)
        startForeground(PairingCodeNotification.NOTIFICATION_ID, buildForegroundNotification())
        val code = intent?.getStringExtra(PairingCodeNotification.EXTRA_CODE)
        if (code != null) {
            runProvision(code)
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

    private fun runProvision(code: String) {
        scope.launch {
            val discovery = AdbPairingDiscovery(
                this@PairingProvisionService,
                scope,
                onFound = {},
                onError = {}
            )
            postRunning("正在发现无线调试服务…")
            val outcome = discovery.discoverNow(this@PairingProvisionService)
            val svc = outcome.services
            if (svc == null) {
                val msg = when {
                    !outcome.pairingFound ->
                        "未发现配对服务：请确认「使用配对码配对设备」弹窗已打开且该页面保持在前台"
                    !outcome.connectFound -> "未发现无线调试端口：请确认已开启无线调试"
                    else -> "无法解析无线调试地址，请重试"
                }
                finishWithFailure(msg, ProvisionMachine.ProvisionError.DISCOVERY_FAILED)
                return@launch
            }

            postRunning("正在配对…")
            val runner = AdbRunner.create(this@PairingProvisionService)
            val pairRes = runner.pair(svc.host, svc.pairingPort, code)
            if (pairRes == null ||
                AdbOutputParser.classifyPair(pairRes.exitCode, pairRes.output)
                != AdbOutputParser.PairOutcome.SUCCESS
            ) {
                finishWithFailure(
                    "配对失败：${pairRes?.output?.ifBlank { "请检查配对码" } ?: "adb 执行失败"}",
                    ProvisionMachine.ProvisionError.PAIR_FAILED
                )
                return@launch
            }

            postRunning("正在连接无线调试…")
            val connRes = runner.connect(svc.host, svc.adbPort)
            if (connRes == null ||
                AdbOutputParser.classifyConnect(connRes.exitCode, connRes.output)
                != AdbOutputParser.ConnectOutcome.SUCCESS
            ) {
                finishWithFailure(
                    "连接失败：${connRes?.output?.ifBlank { "无法连接无线调试端口" } ?: "参数无效"}",
                    ProvisionMachine.ProvisionError.CONNECTION_FAILED
                )
                return@launch
            }

            postRunning("正在执行系统级预置（dpm）…")
            val serial = "${svc.host}:${svc.adbPort}"
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
        fun start(context: Context, code: String) {
            val intent = Intent(context, PairingProvisionService::class.java)
                .putExtra(PairingCodeNotification.EXTRA_CODE, code)
            context.startForegroundService(intent)
        }
    }
}
