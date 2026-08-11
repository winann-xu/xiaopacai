package com.xiaopacai.child

import android.os.Bundle
import android.util.Log
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
import com.xiaopacai.child.ui.theme.XiaopacaiTheme

/**
 * [TASK-ROLE-P1] 小趴菜主 Activity — 双角色路由
 *
 * 应用唯一 Activity，按角色分流：
 * - UNSET（首次启动）：角色引导页
 * - CHILD（儿童端）：现有 MainScreen 守护界面
 * - PARENT（家长端）：密码登录 → 家长主页
 *
 * 角色切换需要家长密码校验。
 */
class MainActivity : ComponentActivity() {

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
        var parentLoggedIn by remember {
            mutableStateOf(currentRole == RoleManager.Role.PARENT && RoleManager.isParentPasswordSet(this@MainActivity))
        }

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

        // 角色引导页回调：选定角色后刷新并进入对应流程
        val onRoleSelected: () -> Unit = {
            currentRole = RoleManager.getCurrentRole(this@MainActivity)
            if (currentRole == RoleManager.Role.PARENT) {
                showParentFlow = true
                // 检查密码是否已设置
                parentLoggedIn = RoleManager.isParentPasswordSet(this@MainActivity)
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
                        onSwitchToChild = { password ->
                            RoleManager.switchToChild(this@MainActivity, password)
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
                        // [TASK-ROLE-P1] 从儿童端切到家长端：先设角色，密码在登录页校验
                        RoleManager.setCurrentRole(this@MainActivity, RoleManager.Role.PARENT)
                        currentRole = RoleManager.Role.PARENT
                        showParentFlow = true
                        parentLoggedIn = RoleManager.isParentPasswordSet(this@MainActivity)
                    }
                )
            }
        }
    }
}
