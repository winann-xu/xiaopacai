// [android-tv] 家长登录 Activity — ParentLoginActivity
// 小趴菜 TVOS 版 — 家长验证：手输账号（邮箱）+ 密码登录
// 规则：已登录账号自动记忆（DataStore），密码永不落盘
// 验证走后端 POST /api/auth/login（复用既有账号体系）

package com.xiaopacai.tvos.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

import com.xiaopacai.tvos.util.DeviceBinding
import com.xiaopacai.tvos.util.EmergencyReleaseServiceTV
import com.xiaopacai.tvos.util.NetworkHelper
import com.xiaopacai.tvos.util.SharedPreferenceHelperTV
import com.xiaopacai.tvos.util.TimeoutExecutorTV
import kotlin.coroutines.resume

/**
 * 家长登录 Activity
 *
 * - 验证方式：手输账号（邮箱）+ 密码 → POST /api/auth/login
 * - 账号记忆：登录成功后持久化账号（DataStore），下次进入自动预填
 * - 密码不记：密码仅存在于本次输入，不写入任何存储
 * - 成功以 RESULT_OK 回传，调用方据此放行（解锁 / 进设置等）
 */
class ParentLoginActivity : ComponentActivity() {

    companion object {
        /** 可选：调用方传入的动作标签，用于界面副标题（如「家长紧急停用」） */
        const val EXTRA_ACTION_LABEL = "parent_login_action_label"

        /** 可选：登录成功后是否直接解除超时锁屏（锁屏服务调用时置 true） */
        const val EXTRA_UNLOCK_ON_SUCCESS = "parent_login_unlock_on_success"

        /** 可选：本次验证是否为「家长紧急停用」——成功则进入限时解除（到期自动恢复守护） */
        const val EXTRA_EMERGENCY_RELEASE = "parent_login_emergency_release"

        /** 回传：登录成功后的访问令牌（RESULT_OK 结果 Intent 中） */
        const val EXTRA_ACCESS_TOKEN = "parent_login_access_token"
    }

    /** 登录成功后回传给调用方的访问令牌（公告管理需要） */
    private var accessTokenState: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val actionLabel = intent.getStringExtra(EXTRA_ACTION_LABEL) ?: "家长验证"
        val unlockOnSuccess = intent.getBooleanExtra(EXTRA_UNLOCK_ON_SUCCESS, false)
        val emergencyRelease = intent.getBooleanExtra(EXTRA_EMERGENCY_RELEASE, false)

        // [BUGFIX] 标记「家长验证进行中」：守护服务据此暂停重新拉起锁屏，
        // 否则每 30s 的 CLEAR_TOP 重拉会把本页顶掉，验证永远做不完。
        TimeoutExecutorTV.beginVerification()

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF121212)
            ) {
                ParentLoginScreen(
                    actionLabel = actionLabel,
                    context = this@ParentLoginActivity,
                    emergencyMode = emergencyRelease,
                    onSuccess = { token ->
                        accessTokenState = token
                        com.xiaopacai.tvos.util.AppLogTV.i("ParentLogin", "家长验证成功（只记账号，不落盘密码）")
                        // 【项3】家长验证成功 → 顺带把本机电视绑定到该家长账号。
                        // 不绑定的话 Web 端看不到这台电视，也就无法下发策略/公告（对标手机版双角色 App 的绑定流程）。
                        // 独立作用域：不随 Activity finish() 被取消。
                        if (!emergencyRelease && token.isNotBlank()) {
                            CoroutineScope(Dispatchers.IO).launch {
                                when (val r = DeviceBinding.bindWithParentToken(applicationContext, token)) {
                                    is DeviceBinding.Result.Success ->
                                        Log.i("ParentLoginActivity", "本机已绑定到家长账号，Web 端可见")
                                    is DeviceBinding.Result.Failed ->
                                        Log.w("ParentLoginActivity", "自动绑定失败: ${r.reason}")
                                }
                            }
                        }
                        if (emergencyRelease) {
                            // 限时解除，到期自动恢复守护（主界面与锁屏页共用）
                            EmergencyReleaseServiceTV.activate(this@ParentLoginActivity)
                        }
                        if (unlockOnSuccess) {
                            TimeoutExecutorTV.unlock(this@ParentLoginActivity)
                        }
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(EXTRA_ACCESS_TOKEN, accessTokenState)
                        )
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        // 验证结束：恢复守护服务的上锁判定（配合 beginVerification 的防顶掉修复）
        TimeoutExecutorTV.endVerification()
        super.onDestroy()
    }
}

// ==================== Compose 登录界面 ====================

@Composable
fun ParentLoginScreen(
    actionLabel: String,
    context: Context,
    emergencyMode: Boolean = false,
    onSuccess: (accessToken: String) -> Unit,
    onCancel: () -> Unit
) {
    // [FIX-2026-09-13-FIELD-EDIT] 账号改用 TextFieldValue：需要**读光标位置**才能判断
    // 「方向键是移动光标、还是该跳出输入框」。旧实现把右键无条件吞掉去跳密码框，
    // 导致遥控器右键永远进不了文本编辑 —— 预填的旧账号既移不了光标也删不掉（真机 .22 反馈）。
    var account by remember { mutableStateOf(TextFieldValue("")) }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val hasLocalPassword = remember { EmergencyReleaseServiceTV.hasPassword(context) }
    val accountFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    val clearAccountFocus = remember { FocusRequester() }

    // 预填已记忆账号（密码永不预填）；光标放到末尾，配合「清除」按钮即可改账号
    LaunchedEffect(Unit) {
        val saved = SharedPreferenceHelperTV.getParentAccount(context).first()
        account = TextFieldValue(saved, selection = TextRange(saved.length))
        accountFocus.requestFocus()
    }

    val submit: () -> Unit = {
        // 紧急解除且已设本地紧急密码时无需账号（离线可用）
        val needAccount = !emergencyMode || !hasLocalPassword
        when {
            isLoading -> Unit
            password.isBlank() -> errorMsg = "请输入密码"
            needAccount && account.text.isBlank() -> errorMsg = "请输入账号和密码"
            else -> {
                isLoading = true
                errorMsg = null
                scope.launch {
                    if (emergencyMode) {
                        when (val result =
                            EmergencyReleaseServiceTV.verifyPassword(context, password, account.text)) {
                            is EmergencyReleaseServiceTV.Result.Success -> {
                                if (account.text.isNotBlank()) {
                                    SharedPreferenceHelperTV.saveParentAccount(
                                        context, account.text.trim().lowercase()
                                    )
                                }
                                onSuccess("")
                            }
                            is EmergencyReleaseServiceTV.Result.Incorrect -> errorMsg = result.reason
                            is EmergencyReleaseServiceTV.Result.LockedOut -> errorMsg = result.message
                        }
                        isLoading = false
                    } else {
                        val host = SharedPreferenceHelperTV.getCloudHost(context).first()
                        val port = SharedPreferenceHelperTV.getCloudPort(context).first()
                        val url = NetworkHelper.buildBaseUrl(host, port) + "/api/auth/login"
                        val payload = JSONObject().apply {
                            put("Username", account.text.trim())
                            put("Password", password)
                        }.toString()

                        val (code, body) = loginRequest(url, payload)
                        isLoading = false
                        when {
                            code in 200..299 -> {
                                // 只记账号，不记密码
                                SharedPreferenceHelperTV.saveParentAccount(
                                    context, account.text.trim().lowercase()
                                )
                                val token = runCatching {
                                    JSONObject(body).optString("accessToken", "")
                                }.getOrDefault("")
                                onSuccess(token)
                            }
                            code == 401 -> errorMsg = "账号或密码错误"
                            code == 429 -> errorMsg = "失败次数过多，请 1 小时后重试"
                            code <= 0 -> errorMsg = "网络异常，请检查网络后重试"
                            else -> errorMsg = "登录失败（HTTP $code）"
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "\uD83D\uDD11 $actionLabel",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (emergencyMode) "输入家长紧急密码，或用账号 + 密码"
                else "请输入家长账号（邮箱）和密码",
                fontSize = 20.sp,
                color = Color(0xFFB0B0B0),
                textAlign = TextAlign.Center
            )

            if (emergencyMode && hasLocalPassword) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已设紧急密码：可只输紧急密码，断网也能解除",
                    fontSize = 16.sp,
                    color = Color(0xFF81C784),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("账号（邮箱）") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(accountFocus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                // [FIX-2026-09-13-FIELD-EDIT] 旧实现在这里无条件吞掉右键去跳密码框，
                                // 遥控器根本进不了文本编辑。现在：光标不在末尾 → 交给输入框自己移动光标；
                                // 已在末尾/已空 → 才跳出（先去「清除」按钮，再往下才是密码框）。
                                Key.DirectionDown -> {
                                    passwordFocus.requestFocus()
                                    true
                                }
                                Key.DirectionRight -> {
                                    if (account.text.isEmpty()) {
                                        clearAccountFocus.requestFocus(); true
                                    } else {
                                        false // 让输入框把光标右移
                                    }
                                }
                                Key.DirectionLeft -> {
                                    // 光标已在最左：吞掉，避免焦点乱跑到取消按钮
                                    account.selection.start == 0 && account.selection.end == 0
                                }
                                else -> false
                            }
                        },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    )
                )
                OutlinedButton(
                    onClick = {
                        account = TextFieldValue("")
                        accountFocus.requestFocus()
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .height(64.dp)
                        .focusRequester(clearAccountFocus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> {
                                    passwordFocus.requestFocus(); true
                                }
                                Key.DirectionLeft -> {
                                    accountFocus.requestFocus(); true
                                }
                                else -> false
                            }
                        }
                ) {
                    Text("清除", fontSize = 20.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "遥控器：← → 移动光标，↓ 切到密码框；换账号点右边「清除」",
                fontSize = 14.sp,
                color = Color(0xFF888888),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                enabled = !isLoading,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else when (e.key) {
                            // [FIX-2026-09-13-FIELD-EDIT] 上下才是跳框；左右留给密码框内部编辑
                            // （旧实现左右也拿去跳框 → 已输入的密码改不了、删不掉）
                            Key.DirectionUp -> {
                                accountFocus.requestFocus()
                                true
                            }
                            Key.DirectionDown -> {
                                loginFocus.requestFocus()
                                true
                            }
                            else -> false
                        }
                    },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = errorMsg ?: " ",
                fontSize = 20.sp,
                color = Color(0xFFEF5350),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = submit,
                    enabled = !isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .focusRequester(loginFocus)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                                passwordFocus.requestFocus()
                                true
                            } else false
                        },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(
                        text = if (isLoading) "登录中…" else "登录",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("取消", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * 调用后端登录接口，返回 (HTTP 状态码, 响应体)；网络故障返回 (-1, 错误信息)
 */
private suspend fun loginRequest(url: String, json: String): Pair<Int, String> =
    suspendCancellableCoroutine { cont ->
        NetworkHelper.postJsonDetailed(url, json) { code, body ->
            if (cont.isActive) cont.resume(code to body)
        }
    }
