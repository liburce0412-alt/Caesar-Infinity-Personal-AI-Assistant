package com.campusai.core.auth

import com.campusai.core.network.SupabaseClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthRequestSessionTest {
    private val clientField = SupabaseClient::class.java.getDeclaredField("client").apply { isAccessible = true }
    private lateinit var originalClient: OkHttpClient
    private var requests = 0
    private var authorization: String? = null

    @Before fun setup() {
        originalClient = clientField.get(SupabaseClient) as OkHttpClient
        clientField.set(SupabaseClient, OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            authorization = chain.request().header("Authorization")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody()).build()
        }.build())
        SupabaseClient.installSession("old-access")
    }

    @After fun restore() {
        clientField.set(SupabaseClient, originalClient)
        SupabaseClient.ensureSession = null
        SupabaseClient.clearSession()
    }

    @Test fun `API uses refreshed access token before sending request`() = runTest {
        SupabaseClient.ensureSession = { SupabaseClient.installSession("new-access"); true }
        assertTrue(SupabaseClient.restGet("listings", emptyMap()).isSuccess)
        assertEquals(1, requests)
        assertEquals("Bearer new-access", authorization)
    }

    @Test fun `failed renewal never sends a known stale credential`() = runTest {
        SupabaseClient.ensureSession = { false }
        assertTrue(SupabaseClient.restGet("listings", emptyMap()).isFailure)
        assertEquals(0, requests)
    }

    @Test fun `account switch during renewal does not send previous users operation as new user`() = runTest {
        SupabaseClient.ensureSession = {
            SupabaseClient.clearSession()
            SupabaseClient.installSession("other-user-access")
            true
        }
        assertTrue(SupabaseClient.restGet("listings", emptyMap()).isFailure)
        assertEquals(0, requests)
    }
}
