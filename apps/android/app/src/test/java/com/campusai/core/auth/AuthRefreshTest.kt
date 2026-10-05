package com.campusai.core.auth

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.campusai.BuildConfig
import com.campusai.core.network.AuthSession
import com.campusai.core.network.RefreshSessionRejectedException
import com.campusai.core.network.SupabaseClient
import com.campusai.core.network.authResponseError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthRefreshTest {
    private val scope = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val access = "supabase_access_token:$scope"
    private val refresh = "supabase_refresh_token:$scope"
    private val email = "supabase_email:$scope"
    private val owner = "supabase_user_id:$scope"
    private var now = 10_000L
    private fun jwt(expiry: Long): String = "header." + Base64.encodeToString(
        """{"sub":"alice","exp":$expiry}""".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    private fun stored() = mutableMapOf(access to jwt(now - 1), refresh to "old-refresh", email to "alice@example.test", owner to "alice")
    private fun renewed() = AuthSession(jwt(now + 3600), "new-refresh", "alice@example.test", "alice")
    private fun repository(values: MutableMap<String, String>, remote: suspend (String) -> Result<AuthSession>) = AuthRepository(
        readStored = { values[it].orEmpty() }, writeStored = { values.putAll(it); true }, refreshRemote = remote, nowSeconds = { now },
    )

    @After fun clear() { SupabaseClient.ensureSession = null; SupabaseClient.clearSession() }

    @Test fun `simultaneous UI and worker calls rotate once and share durable result`() = runTest {
        val values = stored()
        var requests = 0
        val repo = repository(values) { token ->
            assertEquals("old-refresh", token)
            requests++
            delay(100)
            Result.success(renewed())
        }
        val results = List(20) { async { repo.refresh() } }.awaitAll()
        assertTrue(results.all { it })
        assertEquals(1, requests)
        assertEquals("new-refresh", values[refresh])
        assertEquals(values[access], SupabaseClient.userJwt)
        assertTrue(repo.state.value.signedIn)
        now += 3600
        // The next expiry must use the saved replacement, never the old refresh token.
        val restarted = repository(values) { token -> assertEquals("new-refresh", token); Result.success(renewed()) }
        assertTrue(restarted.refresh())
    }

    @Test fun `fresh access token does not rotate merely because a page or worker starts`() = runTest {
        val values = stored().apply { put(access, jwt(now + 3600)) }
        val repo = repository(values) { error("No refresh expected") }
        repeat(10) { assertTrue(repo.refresh()) }
    }

    @Test fun `permanent rejection logs out once without retrying revoked token`() = runTest {
        val values = stored()
        var calls = 0
        val repo = repository(values) { calls++; Result.failure(RefreshSessionRejectedException()) }
        assertFalse(repo.refresh())
        assertFalse(repo.refresh())
        assertEquals(1, calls)
        assertFalse(repo.state.value.signedIn)
        assertTrue(repo.state.value.requiresSignIn)
        assertEquals("alice@example.test", repo.state.value.email)
        assertEquals("", values[refresh])
        assertEquals("", SupabaseClient.userJwt)
    }

    @Test fun `network failure retains tokens and throttles retries then recovers`() = runTest {
        val values = stored()
        var calls = 0
        val repo = repository(values) {
            calls++
            if (calls == 1) Result.failure(IOException("offline")) else Result.success(renewed())
        }
        assertFalse(repo.refresh())
        assertFalse(repo.refresh())
        assertEquals(1, calls)
        assertTrue(repo.state.value.signedIn)
        assertFalse(repo.state.value.requiresSignIn)
        assertEquals("old-refresh", values[refresh])
        now += 5
        assertTrue(repo.refresh())
        assertEquals(2, calls)
    }

    @Test fun `cancelled caller still durably saves completed rotation`() = runTest {
        val values = stored()
        val response = CompletableDeferred<Result<AuthSession>>()
        val repo = repository(values) { response.await() }
        val job = launch { repo.refresh() }
        runCurrent()
        job.cancel()
        response.complete(Result.success(renewed()))
        job.join()
        assertEquals("new-refresh", values[refresh])
        assertEquals(values[access], SupabaseClient.userJwt)
    }

    @Test fun `late refresh response cannot resurrect signed out session`() = runTest {
        val values = stored()
        val response = CompletableDeferred<Result<AuthSession>>()
        val repo = repository(values) { response.await() }
        val job = async { repo.refresh() }
        runCurrent()
        repo.signOut()
        response.complete(Result.success(renewed()))
        assertFalse(job.await())
        assertFalse(repo.state.value.signedIn)
        assertEquals("", values[refresh])
        assertEquals("", SupabaseClient.userJwt)
    }

    @Test fun `pair is saved in one batch before becoming usable and save failure invalidates`() = runTest {
        val values = stored()
        var batches = 0
        val repo = AuthRepository(
            readStored = { values[it].orEmpty() },
            writeStored = { batch ->
                batches++
                assertEquals(setOf(access, refresh, email, owner), batch.keys)
                if (batch[refresh] == "new-refresh") {
                    assertEquals(jwt(now - 1), SupabaseClient.userJwt)
                    false
                } else { values.putAll(batch); true }
            },
            refreshRemote = { Result.success(renewed()) }, nowSeconds = { now },
        )
        assertFalse(repo.refresh())
        assertEquals(2, batches)
        assertTrue(repo.state.value.requiresSignIn)
        assertEquals("", SupabaseClient.userJwt)
    }

    @Test fun `refresh errors distinguish permanent rejection from outages and password errors`() {
        assertTrue(authResponseError("refresh_token", 400, """{"error_code":"refresh_token_already_used"}""") is RefreshSessionRejectedException)
        assertTrue(authResponseError("refresh_token", 400, """{"error_description":"Invalid Refresh Token: Already Used"}""") is RefreshSessionRejectedException)
        for (status in listOf(429, 500, 503)) assertFalse(authResponseError("refresh_token", status, "{}") is RefreshSessionRejectedException)
        assertFalse(authResponseError("refresh_token", 400, "{}") is RefreshSessionRejectedException)
        assertEquals("邮箱或密码不正确。", authResponseError("password", 400, "{}").message)
    }

    @Test fun `foreground and worker obtain the same process repository`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertSame(AuthRepository.getInstance(context), AuthRepository.getInstance(context))
    }
}
