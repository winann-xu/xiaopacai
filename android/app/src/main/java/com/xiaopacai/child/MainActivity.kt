package com.xiaopacai.child

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.xiaopacai.child.role.RoleManager
import com.xiaopacai.child.ui.MainScreen
import com.xiaopacai.child.ui.parent.ParentHomeScreen
import com.xiaopacai.child.ui.parent.ParentLoginScreen
import com.xiaopacai.child.ui.parent.RoleGuideScreen
import com.xiaopacai.child.ui.parent.UpdateDialog
import com.xiaopacai.child.ui.theme.XiaopacaiTheme
import com.xiaopacai.child.util.UpdateManager
import com.xiaopacai.child.util.UpdateNotifier
import java.io.File

/**
 * [TASK-ROLE-P1] 小趴菜主 Activity — 双角色路由
 *
 * 应用唯一 Activity，按角色分流：
 * - UNSET（首次启动）：角色引导页
 * - CHILD（儿童端）：现有 MainScreen 守护界面
 * - PARENT（家长端）：云端账号登录 → 家长主页
 *
 * [TASK-ACCOUNT-V1] 家长登录态仅存在于进程会话内（parentLoggedIn 不持久化）：
 * 每次进入 / 切回 / 重启家长端都必须云端邮箱+密码验证。
 *
 * [TASK-APP-UPDATE-V1] C1：家长端启动静默检查 + 强制更新拦截（D6 每次进入家长端）；
 * 更新通知点击（携带版本码）直达更新弹窗；儿童端不弹任何更新 UI（守护不被打断红线）。
 */
class MainActivity : ComponentActivity() {

    /** 更新通知点击带来的目标版本码（0=无），onNewIntent 触发重查 */
    private val updateTriggerVersion = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            XiaopacaiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 更新通知（可用/下载完成）点击：携带目标版本码，触发弹窗直达
        updateTriggerVersion.value =
            intent.getIntExtra(UpdateNotifier.EXTRA_INSTALL_VERSION_CODE, 0)
    }

    /**
     * [TASK-ROLE-P1] 应用根 Composable
     *
     * 根据当前角色分流到不同的界面流程。
     * 使用 mutableStateOf 监听角色变化以触发界面重组。
     */
    @Composable
    private fun AppRoot() {
        var currentRole by remember { mutableStateOf(RoleManager.getCurrentRole(this@MainActivity)) }
        var showParentFlow by remember { mutableStateOf(currentRole == RoleManager.Role.PARENT) }
        // [TASK-ACCOUNT-V1] 登录态仅会话内有效：重启/切回一律重新云端验证
        var parentLoggedIn by remember { mutableStateOf(false) }

        // [FIX] 儿童模式打开应用即拉起守护前台服务（含 SyncManager/采集器），
        // 避免重装/重启后服务不运行导致公告不展示、数据不同步、超时失效
        LaunchedEffect(currentRole) {
            if (currentRole == RoleManager.Role.CHILD) {
                try {
                    com.xiaopacai.child.service.GuardianForegroundService.start(this@MainActivity)
                } catch (e: Exception) {
                    Log.e("MainActivity", "拉起守护服务失败: ${e.message}")
                }
            }
        }

        // [TASK-APP-UPDATE-V1] C1/D6：更新检查（进入家长端 / 通知点击）
        // 儿童端角色绝不弹更新 UI（守护流程不被打断红线），仅家长端呈现。
        var updateDialogInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
        var updateDownloadedFile by remember { mutableStateOf<File?>(null) }

        LaunchedEffect(currentRole, updateTriggerVersion.value) {
            if (currentRole != RoleManager.Role.PARENT) return@LaunchedEffect

            val triggerVersion = updateTriggerVersion.value
            val result = UpdateManager.check(this@MainActivity, manual = false)
            when (result) {
                is UpdateManager.CheckResult.Update -> {
                    val info = result.info
                    // 通知点击直达：跳过频控（用户明确意图）；否则按 D6 频控把关
                    val fromNotification = triggerVersion > 0
                    if (fromNotification || info.force ||
                        UpdateManager.shouldPrompt(this@MainActivity, info)
                    ) {
                        UpdateManager.markPrompted(this@MainActivity, info)
                        updateDialogInfo = info
                        updateDownloadedFile =
                            UpdateManager.lastDownloadedApk(this@MainActivity, info.versionCode)
                    }
                    updateTriggerVersion.value = 0
                }
                is UpdateManager.CheckResult.UpToDate -> {
                    if (triggerVersion > 0) {
                        // 通知点击但已是最新（可能刚装完新版本）：提示一声即可
                        Toast.makeText(this@MainActivity, "已是最新版本", Toast.LENGTH_SHORT).show()
                        updateTriggerVersion.value = 0
                    }
                }
                is UpdateManager.CheckResult.Failed -> {
                    // 静默失败不打扰；通知点击失败时给提示（若已有校验通过的包则仍可安装）
                    if (triggerVersion > 0) {
                        val file = UpdateManager.lastDownloadedApk(this@MainActivity, triggerVersion)
                        if (file != null) {
                            val info = UpdateManager.UpdateInfo(
                                hasUpdate = true,
                                versionCode = triggerVersion,
                                versionName =
                                    UpdateManager.lastDownloadedVersionName(this@MainActivity, triggerVersion)
                                        ?: "v${triggerVersion / 10000}.${(triggerVersion % 10000) / 100}.${triggerVersion % 100}",
                                minVersionCode = 0,
                                force = false,
                                abiMissing = false,
                                url = "",
                                sha256 = "",
                                sizeBytes = file.length(),
                                changelog = "更新包已下载并通过安全校验",
                            )
                            updateDialogInfo = info
                            updateDownloadedFile = file
                        } else {
                            Toast.makeText(this@MainActivity, result.reason, Toast.LENGTH_SHORT).show()
                        }
                        updateTriggerVersion.value = 0
                    }
                }
            }
        }

        // 角色引导页回调：选定角色后刷新并进入对应流程
        val onRoleSelected: () -> Unit = {
            currentRole = RoleManager.getCurrentRole(this@MainActivity)
            if (currentRole == RoleManager.Role.PARENT) {
                showParentFlow = true
                parentLoggedIn = false // 云端验证后才可进入家长端
            }
        }

        when {
            // === 首次启动：角色引导 ===
            currentRole == RoleManager.Role.UNSET -> {
                RoleGuideScreen(
                    onChildSelected = onRoleSelected,
                    onParentSelected = onRoleSelected
                )
            }

            // === 家长端流程 ===
            currentRole == RoleManager.Role.PARENT -> {
                if (parentLoggedIn) {
                    ParentHomeScreen(
                        // [TASK-ACCOUNT-V1] 家长主页内已通过 SystemGateDialog 云端验证后才回调
                        onSwitchToChild = {
                            RoleManager.setCurrentRole(this@MainActivity, RoleManager.Role.CHILD)
                            currentRole = RoleManager.Role.CHILD
                            showParentFlow = false
                            parentLoggedIn = false
                        },
                        onLogout = {
                            parentLoggedIn = false
                        }
                    )
                } else {
                    ParentLoginScreen(
                        onLoginSuccess = {
                            parentLoggedIn = true
                        },
                        onSwitchToChild = {
                            // 放弃登录，回到儿童端（无需密码的逃生通道）
                            RoleManager.setCurrentRole(this@MainActivity, RoleManager.Role.CHILD)
                            currentRole = RoleManager.Role.CHILD
                            showParentFlow = false
                        }
                    )
                }
            }

            // === 儿童端（默认） ===
            else -> {
                MainScreen(
                    onSwitchToParent = {
                        // [TASK-ACCOUNT-V1] 从儿童端切到家长端：先设角色，云端验证在登录页完成
                        RoleManager.setCurrentRole(this@MainActivity, RoleManager.Role.PARENT)
                        currentRole = RoleManager.Role.PARENT
                        showParentFlow = true
                        parentLoggedIn = false
                    }
                )
            }
        }

        // [TASK-APP-UPDATE-V1] C2：更新弹窗（仅家长端；强制更新不可跳过）
        updateDialogInfo?.let { info ->
            UpdateDialog(
                info = info,
                downloadedFile = updateDownloadedFile,
                onDismiss = { updateDialogInfo = null },
                onSkip = {
                    UpdateManager.markSkipped(this@MainActivity, info.versionCode)
                    updateDialogInfo = null
                },
                // 强制更新：发起安装后保持弹窗（用户在系统确认页取消可再次点）；
                // 安装成功后进程被系统替换重启，弹窗随进程消失。
                onCloseAfterInstall = {
                    if (!info.force) updateDialogInfo = null
                }
            )
        }
    }
}
