package com.campusai.core.auth

import android.content.Context
import com.campusai.BuildConfig
import android.util.Base64
import com.campusai.core.network.AuthSession
import com.campusai.core.network.SupabaseClient
import com.campusai.core.security.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class AuthState(
    val signedIn: Boolean = false,
    val email: String = "",
    val userId: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class AuthRepository(private val context: Context) {
    private val backendScope = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val accessKey = "supabase_access_token:$backendScope"
    private val refreshKey = "supabase_refresh_token:$backendScope"
    private val emailKey = "supabase_email:$backendScope"
    private val userIdKey = "supabase_user_id:$backendScope"
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        val token = SecurePreferences.decrypt(context, accessKey)
        val email = SecurePreferences.decrypt(context, emailKey)
        val userId = SecurePreferences.decrypt(context, userIdKey).ifBlank { jwtSubject(token) }
        if (token.isNotBlank()) {
            SupabaseClient.installSession(token)
            _state.value = AuthState(signedIn = true, email = email, userId = userId)
        }
    }

    suspend fun signIn(email: String, password: String): Boolean {
        _state.value = _state.value.copy(busy = true, error = null)
        return SupabaseClient.signIn(email, password).fold(
            onSuccess = { rawSession ->
                val session = normalizeSession(rawSession, email)
                if (!persist(session)) {
                    SupabaseClient.clearSession()
                    _state.value = AuthState(error = "设备安全存储不可用，没有保存登录令牌。请检查系统锁屏与安全设置。")
                    false
                } else {
                    _state.value = AuthState(signedIn = true, email = session.email.ifBlank { email.trim() }, userId = session.userId)
                    true
                }
            },
            onFailure = { error -> _state.value = AuthState(error = error.message ?: "登录失败，请稍后重试。"); false },
        )
    }

    suspend fun signUp(email: String, password: String, inviteCode: String): Boolean {
        _state.value = _state.value.copy(busy = true, error = null, notice = null)
        return SupabaseClient.signUp(email, password, inviteCode).fold(
            onSuccess = { result ->
                val session = result.session?.let { normalizeSession(it, result.email) }
                if (session == null) {
                    _state.value = AuthState(
                        email = result.email,
                        notice = "账号已创建，请先完成邮箱确认再登录。",
                    )
                    false
                } else if (!persist(session)) {
                    SupabaseClient.clearSession()
                    _state.value = AuthState(error = "账号已创建，但设备安全存储不可用，未能保存登录状态。请返回登录页重试。")
                    false
                } else {
                    _state.value = AuthState(signedIn = true, email = session.email, userId = session.userId, notice = "注册成功，已直接登录。")
                    true
                }
            },
            onFailure = { error ->
                _state.value = AuthState(error = error.message ?: "注册失败，请稍后重试。")
                false
            },
        )
    }

    suspend fun refresh(): Boolean {
        val refreshToken = SecurePreferences.decrypt(context, refreshKey)
        if (refreshToken.isBlank()) return false
        return SupabaseClient.refresh(refreshToken).fold(
            onSuccess = { rawSession ->
                val session = normalizeSession(rawSession)
                val saved = persist(session)
                if (saved) _state.value = AuthState(signedIn = true, email = session.email, userId = session.userId)
                saved
            },
            onFailure = { false },
        )
    }

    fun signOut() {
        listOf(accessKey, refreshKey, emailKey, userIdKey).forEach { SecurePreferences.encrypt(context, it, "") }
        SupabaseClient.clearSession()
        _state.value = AuthState()
    }

    fun clearError() { _state.value = _state.value.copy(error = null, notice = null) }

    private fun persist(session: AuthSession): Boolean {
        val email = session.email.ifBlank { _state.value.email }
        val saved = listOf(
            SecurePreferences.encrypt(context, accessKey, session.accessToken),
            SecurePreferences.encrypt(context, refreshKey, session.refreshToken),
            SecurePreferences.encrypt(context, emailKey, email),
            SecurePreferences.encrypt(context, userIdKey, session.userId),
        ).all { it }
        if (!saved) listOf(accessKey, refreshKey, emailKey, userIdKey).forEach { SecurePreferences.encrypt(context, it, "") }
        return saved
    }

    private fun normalizeSession(session: AuthSession, fallbackEmail: String = ""): AuthSession = session.copy(
        email = session.email.ifBlank { fallbackEmail.ifBlank { _state.value.email } },
        userId = session.userId
            .ifBlank { jwtSubject(session.accessToken) }
            .ifBlank { _state.value.userId },
    )

    private fun jwtSubject(token: String): String = runCatching {
        val payload = token.split('.').getOrNull(1).orEmpty()
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(decoded, Charsets.UTF_8)).optString("sub")
    }.getOrDefault("")
}
