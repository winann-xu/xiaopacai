package com.xiaopacai.child.ui.parent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaopacai.child.util.UpdateManager
import com.xiaopacai.child.util.UpdateNotifier
import kotlinx.coroutines.launch
import java.io.File

/**
 * [TASK-APP-UPDATE-V1] 更新弹窗（ADR 0017 C2/D6）：
 * - 强制更新：不可关闭，仅「立即更新」按钮（每次进入家长端弹，直到更新完成）
 * - 可选更新：「立即更新 / 以后再说 / 跳过此版本」（版本+每日一次频控由调用方把关）
 * - 下载：进度条实时展示 + 进度通知；完成自动 SHA-256 校验后发起安装
 * - 未知来源权限未开：按钮变「去开启权限」，开启后回弹窗继续安装
 * - [TASK-UPDATE-DEADLOCK-FIX] 安装失败原因显式化：签名不匹配/权限缺失/系统失败
 *   均在弹窗内展示并可重试，杜绝「强制更新弹窗反复出现但永远装不上」的死锁；
 *   Device Owner 设备走 PackageInstaller 会话静默安装（系统不打断）。
 * - [TASK-UPDATE-DEADLOCK-FIX] 修复下载完成后安装不触发的旧闭包 bug：
 *   旧实现 readyFile 在组合期捕获，下载完成的协程回调拿到的是点击瞬间的旧闭包
 *   （readyFile=null）→ startInstall 直接 return，安装永远不执行，只能反复重下。
 *   现改为调用时实时解析已校验 APK。
 * - [downloadedFile]：已有校验通过的 APK 时跳过下载直接安装（下载完成通知点击路径）
 */
@Composable
fun UpdateDialog(
    info: UpdateManager.UpdateInfo,
    downloadedFile: File?,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onCloseAfterInstall: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var needPermission by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }

    // [TASK-UPDATE-DEADLOCK-FIX] 安装入口文件必须在调用时实时解析：
    // 下载完成回调（协程）触发安装时，组合期捕获的旧值（null）会导致安装永不执行
    fun currentReadyFile(): File? =
        downloadedFile ?: UpdateManager.lastDownloadedApk(context, info.versionCode)

    fun startInstall() {
        val file = currentReadyFile()
        if (file == null || !file.exists()) return
        when (val result = UpdateManager.installApk(context, file)) {
            is UpdateManager.InstallResult.Started -> {
                installError = null
                onCloseAfterInstall()
            }
            UpdateManager.InstallResult.NeedPermission -> {
                needPermission = true
                installError = null
                UpdateManager.openInstallPermissionSettings(context)
            }
            UpdateManager.InstallResult.SignatureMismatch -> {
                installError =
                    "更新包签名与本机安装版本不一致，已被安全拦截。\n" +
                    "如持续出现请联系管理员，勿混用正式版/特别版安装包。"
            }
            is UpdateManager.InstallResult.Failed -> {
                installError = "安装失败：${result.reason}"
            }
        }
    }

    fun startDownload() {
        if (downloading) return
        installError = null
        val file = currentReadyFile()
        if (file != null && file.exists()) {
            startInstall()
            return
        }
        downloading = true
        progress = 0
        scope.launch {
            val result = UpdateManager.downloadApk(context, info) { done, total ->
                val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                progress = percent
                UpdateNotifier.notifyDownloadProgress(context, info.versionName, percent)
            }
            downloading = false
            if (result != null) {
                UpdateNotifier.notifyDownloadComplete(context, info)
                startInstall()
            } else {
                UpdateNotifier.notifyDownloadFailed(context, info.versionName)
                // 下载失败：按钮回「重试下载」，progress 复位
                progress = 0
            }
        }
    }

    AlertDialog(
        // 强制更新不可跳过：拦截所有关闭途径
        onDismissRequest = { if (!info.force) onDismiss() },
        title = {
            Text(if (info.force) "必须更新才能继续使用" else "发现新版本 v${info.versionName}")
        },
        text = {
            Column {
                Text(
                    buildString {
                        append("最新版本：v${info.versionName}")
                        if (info.force) append("（低于 v${versionNameOf(info.minVersionCode)} 的版本已停止支持）")
                        if (info.sizeBytes > 0) append("\n大小：${info.sizeBytes / 1024 / 1024} MB")
                        if (info.changelog.isNotBlank()) append("\n\n${info.changelog}")
                    },
                    fontSize = 14.sp
                )
                if (downloading) {
                    Spacer(modifier = Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("正在下载… $progress%", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                installError?.let { err ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(err, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (needPermission && UpdateManager.canRequestPackageInstalls(context)) {
                    needPermission = false
                }
                if (downloading) return@TextButton
                startDownload()
            }) {
                Text(if (downloading) "下载中…"
                else if (needPermission) "去开启权限"
                else if (installError != null) "重试更新"
                else "立即更新")
            }
        },
        dismissButton = {
            if (!info.force) {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = onDismiss) { Text("以后再说") }
                    TextButton(onClick = onSkip) { Text("跳过此版本") }
                }
            }
        },
    )
}

/** 版本码 → 版本号（与 web 端 codeToVersion 同口径） */
fun versionNameOf(code: Int): String =
    "${code / 10000}.${(code % 10000) / 100}.${code % 100}"
