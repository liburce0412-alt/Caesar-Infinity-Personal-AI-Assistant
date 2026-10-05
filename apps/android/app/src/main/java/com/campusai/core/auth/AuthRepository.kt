package com.campusai.core.auth

import android.content.Context
import android.util.Base64
import com.campusai.BuildConfig
import com.campusai.core.network.AuthSession
import com.campusai.core.network.RefreshSessionRejectedException
import com.campusai.core.network.SupabaseClient
import com.campusai.core.security.SecurePreferences
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class AuthState(
    val signedIn: Boolean = false,
    val email: String = "",
    val userId: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val requiresSignIn: Boolean = false,
)

class AuthRepository internal constructor(
    private val readStored: (String) -> String,
    private val writeStored: (Map<String, String>) -> Boolean,
    private val refreshRemote: suspend (String) -> Result<AuthSession> = SupabaseClient::refresh,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    companion object {
        @Volatile private var instance: AuthRepository? = null

        fun getInstance(context: Context): AuthRepository = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                AuthRepository(
                    readStored = { SecurePreferences.decrypt(app, it) },
                    writeStored = { SecurePreferences.encryptAll(app, it) },
                ).also { repository ->
                    instance = repository
                    SupabaseClient.ensureSession = { repository.refresh() }
                }
            }
        }
    }

    private val backendScope = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val accessKey = "supabase_access_token:$backendScope"
    private val refreshKey = "supabase_refresh_token:$backendScope"
    private val emailKey = "supabase_email:$backendScope"
    private val userIdKey = "supabase_user_id:$backendScope"
    private val stateLock = Any()
    private val refreshMutex = Mutex()
    private var generation = 0L
    private var lastRefreshAttempt: Long? = null
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        val token = readStored(accessKey)
        val email = readStored(emailKey)
        val userId = readStored(userIdKey).ifBlank { jwtPayload(token)?.optString("sub").orEmpty() }
        if (token.isNotBlank()) {
            SupabaseClient.installSession(token)
            _state.value = AuthState(signedIn = true, email = email, userId = userId)
        }
    }

    suspend fun signIn(email: String, password: String): Boolean {
        val attempt = beginSignIn()
        val result = SupabaseClient.signIn(email, password)
        return synchronized(stateLock) {
            if (attempt != generation) return@synchronized false
            result.fold(
                onSuccess = { accept(normalizeSession(it, email)) },
                onFailure = { _state.value = AuthState(error = it.message ?: "登录失败，请稍后重试。"); false },
            )
        }
    }

    suspend fun signUp(email: String, password: String, inviteCode: String): Boolean {
        val attempt = beginSignIn()
        val result = SupabaseClient.signUp(email, password, inviteCode)
        return synchronized(stateLock) {
            if (attempt != generation) return@synchronized false
            result.fold(
                onSuccess = { response ->
                    val session = response.session
                    if (session == null) {
                        _state.value = AuthState(email = response.email, notice = "账号已创建，请先完成邮箱确认再登录。")
                        false
                    } else accept(normalizeSession(session, email), "注册成功，已直接登录。")
                },
                onFailure = { _state.value = AuthState(error = it.message ?: "注册失败，请稍后重试。"); false },
            )
        }
    }

    private fun beginSignIn(): Long = synchronized(stateLock) {
        invalidate()
        _state.value = AuthState(busy = true)
        generation
    }

    // UI, foreground resume and WorkManager share one lock and re-read the latest pair.
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        val pending = synchronized(stateLock) {
            if (!_state.value.signedIn) return@withLock false
            if (accessIsFresh(readStored(accessKey))) return@withLock true
            val token = readStored(refreshKey)
            if (token.isBlank()) {
                requireSignIn()
                return@withLock false
            }
            val now = nowSeconds()
            if (lastRefreshAttempt?.let { now >= it && now - it < 5 } == true) return@withLock false
            lastRefreshAttempt = now
            generation to token
        }
        // Once rotation starts, finish saving its response even if the screen/worker is cancelled.
        withContext(NonCancellable) {
            val result = refreshRemote(pending.second)
            synchronized(stateLock) {
                if (pending.first != generation) return@synchronized false
                result.fold(
                    onSuccess = { accept(normalizeSession(it)) },
                    onFailure = {
                        if (it is RefreshSessionRejectedException) requireSignIn()
                        // Offline/5xx/429 keep the saved pair for a later retry.
                        false
                    },
                )
            }
        }
    }

    fun signOut() = synchronized(stateLock) {
        invalidate()
        _state.value = AuthState()
    }

    fun clearError() = synchronized(stateLock) {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    private fun requireSignIn() {
        val email = _state.value.email
        invalidate()
        _state.value = AuthState(email = email, error = "登录已失效，请重新登录。你的本地记录仍然保留。", requiresSignIn = true)
    }

    private fun invalidate() {
        generation++
        lastRefreshAttempt = null
        writeStored(listOf(accessKey, refreshKey, emailKey, userIdKey).associateWith { "" })
        SupabaseClient.clearSession()
    }

    private fun accept(session: AuthSession, notice: String? = null): Boolean {
        val saved = session.accessToken.isNotBlank() && session.refreshToken.isNotBlank() && session.userId.isNotBlank() &&
            writeStored(mapOf(accessKey to session.accessToken, refreshKey to session.refreshToken,
                emailKey to session.email, userIdKey to session.userId))
        if (!saved) {
            invalidate()
            _state.value = AuthState(error = "设备安全存储不可用，未能保存登录状态，请重新登录。", requiresSignIn = true)
            return false
        }
        SupabaseClient.installSession(session.accessToken)
        _state.value = AuthState(signedIn = true, email = session.email, userId = session.userId, notice = notice)
        return true
    }

    private fun accessIsFresh(token: String): Boolean =
        (jwtPayload(token)?.optLong("exp", 0L) ?: 0L) > nowSeconds() + 60

    private fun normalizeSession(session: AuthSession, fallbackEmail: String = ""): AuthSession = session.copy(
        email = session.email.ifBlank { fallbackEmail.ifBlank { _state.value.email } },
        userId = session.userId.ifBlank { jwtPayload(session.accessToken)?.optString("sub").orEmpty() }
            .ifBlank { _state.value.userId },
    )

    private fun jwtPayload(token: String): JSONObject? = runCatching {
        val payload = token.split('.').getOrNull(1).orEmpty()
        JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
    }.getOrNull()
}
