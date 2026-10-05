package com.campusai.core.network

import com.campusai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val email: String,
    val userId: String,
)

data class AuthSignUpResult(
    val session: AuthSession?,
    val email: String,
    val userId: String,
)

object SupabaseClient {
    private var client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val supabaseUrl: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val supabaseAnonKey: String get() = BuildConfig.SUPABASE_ANON_KEY
    @Volatile
    var userJwt: String = ""
        private set

    fun isConfigured() = supabaseUrl.startsWith("https://") &&
        !supabaseUrl.contains("your-project") &&
        supabaseAnonKey.isNotBlank() &&
        !supabaseAnonKey.contains("replace-with") &&
        !supabaseAnonKey.contains("your_anon")

    @Volatile var ensureSession: (suspend () -> Boolean)? = null
        internal set
    @Volatile private var sessionGeneration = 0L

    fun installSession(accessToken: String) { userJwt = accessToken }
    fun clearSession() { sessionGeneration++; userJwt = "" }

    suspend fun restGet(
        table: String,
        parameters: Map<String, String>,
        callTimeoutSeconds: Long? = null,
        sessionToken: String? = null,
    ): Result<JSONArray> = withContext(Dispatchers.IO) {
        authenticatedRequest(callTimeoutSeconds, sessionToken) {
            val url = "$supabaseUrl/rest/v1/$table".toHttpUrl().newBuilder().apply {
                parameters.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
            Request.Builder().url(url).get().build()
        }.mapCatching { raw -> JSONArray(raw) }
    }

    suspend fun restInsert(table: String, payload: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        authenticatedRequest {
            Request.Builder()
                .url("$supabaseUrl/rest/v1/$table")
                .header("Prefer", "return=representation")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        }.mapCatching { raw -> JSONArray(raw).optJSONObject(0) ?: JSONObject() }
    }

    suspend fun restUpdate(
        table: String,
        filters: Map<String, String>,
        payload: JSONObject,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        authenticatedRequest {
            val url = "$supabaseUrl/rest/v1/$table".toHttpUrl().newBuilder().apply {
                filters.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
            Request.Builder()
                .url(url)
                .header("Prefer", "return=representation")
                .patch(payload.toString().toRequestBody(jsonMediaType))
                .build()
        }.mapCatching { raw ->
            JSONArray(raw).optJSONObject(0)
                ?: error("资料没有更新，请确认登录状态后重试。")
        }
    }

    suspend fun rpc(name: String, payload: JSONObject, sessionToken: String? = null): Result<JSONObject> = withContext(Dispatchers.IO) {
        authenticatedRequest(sessionToken = sessionToken) {
            Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/$name")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        }.mapCatching { raw ->
            when {
                raw.trimStart().startsWith('{') -> JSONObject(raw)
                raw.trimStart().startsWith('[') -> JSONArray(raw).optJSONObject(0) ?: JSONObject()
                else -> JSONObject().put("value", raw.trim().trim('"'))
            }
        }
    }

    suspend fun rpcArray(name: String, payload: JSONObject = JSONObject()): Result<JSONArray> = withContext(Dispatchers.IO) {
        authenticatedRequest {
            Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/$name")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        }.mapCatching { raw -> JSONArray(raw) }
    }

    fun publicMediaUrl(bucket: String, path: String): String {
        if (!isConfigured() || path.isBlank()) return ""
        val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
        return "$supabaseUrl/storage/v1/object/public/$bucket/$encodedPath"
    }

    suspend fun signedMediaUrl(bucket: String, path: String, expiresInSeconds: Int = 3600): Result<String> = withContext(Dispatchers.IO) {
        authenticatedRequest {
            val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
            Request.Builder()
                .url("$supabaseUrl/storage/v1/object/sign/$bucket/$encodedPath")
                .post(JSONObject().put("expiresIn", expiresInSeconds.coerceIn(1, 3600)).toString().toRequestBody("application/json".toMediaType()))
                .build()
        }.mapCatching { raw ->
            val signedPath = JSONObject(raw).getString("signedURL")
            "$supabaseUrl/storage/v1$signedPath"
        }
    }

    suspend fun uploadObject(bucket: String, path: String, bytes: ByteArray, contentType: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext Result.failure(IllegalArgumentException("图片内容为空。"))
        authenticatedRequest {
            val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
            Request.Builder()
                .url("$supabaseUrl/storage/v1/object/$bucket/$encodedPath")
                .header("x-upsert", "false")
                .post(bytes.toRequestBody(contentType.toMediaType()))
                .build()
        }.map { Unit }
    }

    suspend fun deleteObject(bucket: String, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        authenticatedRequest {
            val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
            Request.Builder().url("$supabaseUrl/storage/v1/object/$bucket/$encodedPath").delete().build()
        }.map { Unit }
    }

    suspend fun signIn(email: String, password: String): Result<AuthSession> = authRequest(
        grant = "password",
        payload = JSONObject().put("email", email.trim()).put("password", password),
    )

    suspend fun signUp(email: String, password: String, inviteCode: String): Result<AuthSignUpResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("Supabase 尚未配置，暂时无法注册。"))
        val normalizedEmail = email.trim()
        val request = Request.Builder()
            .url("$supabaseUrl/auth/v1/signup")
            .header("apikey", supabaseAnonKey)
            .header("Content-Type", "application/json")
            .post(JSONObject().put("email", normalizedEmail).put("password", password)
                .put("data", JSONObject().put("invite_code", inviteCode.trim()))
                .toString().toRequestBody(jsonMediaType))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        val json = JSONObject(raw)
                        json.optString("msg").ifBlank { json.optString("error_description") }.ifBlank { json.optString("message") }
                    }.getOrDefault("")
                    val message = when {
                        response.code == 422 && detail.contains("registered", ignoreCase = true) -> "这个邮箱已经注册，请直接登录。"
                        response.code == 422 -> detail.ifBlank { "邮箱或密码不符合注册要求。" }
                        response.code == 429 -> "注册操作过于频繁，请稍后再试。"
                        response.code >= 500 -> "注册暂未完成，请确认邀请码仍有效，或稍后重试。"
                        else -> detail.ifBlank { "注册服务暂时不可用（${response.code}）。" }
                    }
                    error(message)
                }
                parseSignUpResponse(raw, normalizedEmail)
            }
        }
    }

    suspend fun refresh(refreshToken: String): Result<AuthSession> = authRequest(
        grant = "refresh_token",
        payload = JSONObject().put("refresh_token", refreshToken),
    )

    private suspend fun authRequest(grant: String, payload: JSONObject): Result<AuthSession> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("Supabase 尚未配置，暂时无法登录。"))
        val request = Request.Builder()
            .url("$supabaseUrl/auth/v1/token?grant_type=$grant")
            .header("apikey", supabaseAnonKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw authResponseError(grant, response.code, raw)
                }
                val json = JSONObject(raw)
                AuthSession(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.getString("refresh_token"),
                    email = json.optJSONObject("user")?.optString("email").orEmpty(),
                    userId = json.optJSONObject("user")?.optString("id").orEmpty(),
                )
            }
        }
    }

    private suspend fun authenticatedRequest(
        callTimeoutSeconds: Long? = null,
        sessionToken: String? = null,
        build: () -> Request,
    ): Result<String> {
        if (!isConfigured()) return Result.failure(IllegalStateException("Supabase 尚未配置。"))
        val generation = sessionGeneration
        if (ensureSession?.invoke() == false) return Result.failure(IllegalStateException(
            if (userJwt.isBlank()) "登录已失效，请重新登录。" else "暂时无法续期登录，请检查网络后重试。",
        ))
        if (generation != sessionGeneration) return Result.failure(IllegalStateException("登录状态已变化，请重新同步。"))
        val token = sessionToken ?: userJwt
        if (sessionToken != null && sessionToken != userJwt) return Result.failure(IllegalStateException("登录状态已变化，请重新同步。"))
        if (token.isBlank()) return Result.failure(IllegalStateException("请先登录，再读取你的同步数据。"))
        return runCatching {
            val unsigned = build()
            val request = unsigned.newBuilder()
                .header("apikey", supabaseAnonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", unsigned.body?.contentType()?.toString() ?: "application/json")
                .build()
            val call = client.newCall(request)
            callTimeoutSeconds?.takeIf { it > 0L }?.let { seconds ->
                call.timeout().timeout(seconds, TimeUnit.SECONDS)
            }
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        val json = JSONObject(raw)
                        json.optString("message").ifBlank { json.optString("hint") }
                    }.getOrDefault("")
                    error(detail.ifBlank { "服务暂时不可用（${response.code}）。" })
                }
                raw
            }
        }
    }
}

internal fun parseSignUpResponse(raw: String, fallbackEmail: String): AuthSignUpResult {
    val json = JSONObject(raw)
    val user = json.optJSONObject("user") ?: json
    val email = user.optString("email").ifBlank { fallbackEmail }
    val userId = user.optString("id")
    val accessToken = json.optString("access_token")
    val refreshToken = json.optString("refresh_token")
    val session = if (accessToken.isNotBlank() && refreshToken.isNotBlank()) AuthSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        email = email,
        userId = userId,
    ) else null
    return AuthSignUpResult(session, email, userId)
}

internal class RefreshSessionRejectedException : IllegalStateException("登录已失效，请重新登录。")

internal fun authResponseError(grant: String, status: Int, raw: String): Exception {
    val json = runCatching { JSONObject(raw) }.getOrNull()
    val code = json?.optString("error_code").orEmpty()
    val detail = json?.optString("msg").orEmpty()
        .ifBlank { json?.optString("error_description").orEmpty() }
        .ifBlank { json?.optString("message").orEmpty() }
    if (grant == "refresh_token") {
        val rejected = status in listOf(400, 401, 403) && (
            code in setOf("refresh_token_not_found", "refresh_token_already_used", "session_not_found", "session_expired", "user_not_found", "user_banned") ||
                detail.contains("Invalid Refresh Token", ignoreCase = true) ||
                detail.contains("Session expired", ignoreCase = true))
        if (rejected) return RefreshSessionRejectedException()
        return IllegalStateException("登录续期暂未完成（$status），请稍后重试。")
    }
    return IllegalStateException(if (status == 400 || status == 401) "邮箱或密码不正确。"
        else detail.ifBlank { "登录服务暂时不可用（$status）。" })
}
