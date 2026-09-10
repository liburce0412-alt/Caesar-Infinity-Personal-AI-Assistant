package com.campusai.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.campusai.core.designsystem.SpectraTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.campusai.core.auth.AuthState
import com.campusai.core.designsystem.BrandMark
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.SpectraPrimaryButton
import com.campusai.core.designsystem.SpectraTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class AuthMode { SIGN_IN, SIGN_UP }

@Composable
fun AuthScreen(
    state: AuthState,
    onSignIn: suspend (String, String) -> Boolean,
    onSignUp: suspend (String, String, String) -> Boolean,
    onClearMessage: () -> Unit,
    onBack: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val layout = SpectraTheme.layout
    val tokens = SpectraTheme.tokens
    val fluid = SpectraTheme.isFluid
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = layout.pageHorizontalPadding,
                vertical = if (fluid) 54.dp else 20.dp,
            ),
        contentAlignment = if (fluid) Alignment.TopCenter else Alignment.Center,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        GlassPanel(
            Modifier.fillMaxWidth().padding(top = if (fluid) 54.dp else 0.dp),
            radius = tokens.radii.hero.value.roundToInt(),
            emphasized = true,
            shadowed = !fluid,
        ) {
            Column(
                Modifier.padding(if (fluid) 28.dp else 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (fluid) 16.dp else 12.dp),
            ) {
                PeaceWelcome()
                BrandMark(Modifier.size(if (fluid) 56.dp else 72.dp))
                Text(if (mode == AuthMode.SIGN_IN) "登录 Caesar∞" else "创建 Caesar∞ 账号", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (mode == AuthMode.SIGN_IN) "登录后可以同步树洞、心愿墙和你的时间记录；本地能力无需登录。"
                    else "使用管理员提供的邀请码注册，创建账号后即可进入应用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(.62f),
                )
                com.campusai.core.designsystem.CaesarSlidingSelector(
                    options = listOf("登录", "注册"),
                    selectedIndex = if (mode == AuthMode.SIGN_IN) 0 else 1,
                    onSelected = { index ->
                        mode = if (index == 0) AuthMode.SIGN_IN else AuthMode.SIGN_UP
                        if (mode == AuthMode.SIGN_IN) confirmPassword = ""
                        onClearMessage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    motionEnabled = tokens.motion.enabled,
                )
                Spacer(Modifier.height(2.dp))
                SpectraTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("邮箱") }, singleLine = true, shape = RoundedCornerShape(tokens.radii.input))
                SpectraTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(tokens.radii.input))
                if (mode == AuthMode.SIGN_UP) SpectraTextField(
                    confirmPassword,
                    { confirmPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("确认密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(tokens.radii.input),
                    isError = confirmPassword.isNotBlank() && confirmPassword != password,
                    supportingText = if (confirmPassword.isNotBlank() && confirmPassword != password) ({ Text("两次输入的密码不一致。") }) else null,
                )
                if (mode == AuthMode.SIGN_UP) SpectraTextField(
                    inviteCode, { inviteCode = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("邀请码") }, singleLine = true, shape = RoundedCornerShape(tokens.radii.input),
                    supportingText = { Text("每个邀请码只能使用一次。") },
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
                SpectraPrimaryButton(
                    text = when {
                        state.busy && mode == AuthMode.SIGN_IN -> "正在登录"
                        state.busy -> "正在创建账号"
                        mode == AuthMode.SIGN_IN -> "安全登录"
                        else -> "直接注册并登录"
                    },
                    onClick = { scope.launch {
                        val succeeded = if (mode == AuthMode.SIGN_IN) onSignIn(email, password) else onSignUp(email, password, inviteCode)
                        if (succeeded) onBack()
                    } },
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (mode == AuthMode.SIGN_IN) Icons.Rounded.Lock else Icons.Rounded.PersonAdd,
                    enabled = email.contains('@') && password.length >= 8 && (mode == AuthMode.SIGN_IN || (confirmPassword == password && inviteCode.isNotBlank())) && !state.busy,
                )
            }
        }
    }
}
