package com.campusai.core.network

import com.campusai.BuildConfig
import com.campusai.core.ai.AiRoutingException
import org.junit.Assert.*
import org.junit.Test

class CaesarCloudEndpointTest {
    @Test fun `all variants retain the HTTPS backend without an override`() {
        for (buildType in listOf("debug", "release", "benchmark")) {
            assertEquals("https://backend.example/caesar-cloud/v1/chat",
                caesarCloudChatEndpoint("https://backend.example/", buildType))
        }
    }

    @Test fun `even debug rejects HTTP backend configuration without the explicit override`() {
        for (buildType in listOf("debug", "release", "benchmark")) {
            for (base in listOf("http://backend.example", "http://127.0.0.1:18789", "")) {
                rejected { caesarCloudChatEndpoint(base, buildType) }
            }
        }
    }

    @Test fun `debug may select only the fixed full loopback endpoint`() {
        assertEquals(CAESAR_CLOUD_LOOPBACK_ENDPOINT,
            caesarCloudChatEndpoint("https://backend.example", "debug", CAESAR_CLOUD_LOOPBACK_ENDPOINT))
    }

    @Test fun `non-debug variants fail closed even if an override is supplied`() {
        for (buildType in listOf("release", "benchmark", "production", "Debug", "")) {
            rejected { caesarCloudChatEndpoint("https://backend.example", buildType, CAESAR_CLOUD_LOOPBACK_ENDPOINT) }
        }
    }

    @Test fun `debug rejects aliases other hosts ports paths credentials and URL suffixes`() {
        for (target in listOf(
            "http://localhost:18789/caesar-cloud/v1/chat",
            "http://127.0.0.2:18789/caesar-cloud/v1/chat",
            "http://127.0.0.1:18790/caesar-cloud/v1/chat",
            "http://127.0.0.1:18789/other",
            "http://user@127.0.0.1:18789/caesar-cloud/v1/chat",
            "https://127.0.0.1:18789/caesar-cloud/v1/chat",
            "$CAESAR_CLOUD_LOOPBACK_ENDPOINT/",
            "$CAESAR_CLOUD_LOOPBACK_ENDPOINT?redirect=https://other.example",
            "$CAESAR_CLOUD_LOOPBACK_ENDPOINT#fragment",
            " $CAESAR_CLOUD_LOOPBACK_ENDPOINT",
            " ",
        )) {
            rejected { caesarCloudChatEndpoint("https://backend.example", "debug", target) }
        }
    }

    @Test fun `the compiled variant cannot carry a non-debug or arbitrary override`() {
        if (BuildConfig.BUILD_TYPE != "debug") assertEquals("", BuildConfig.CAESAR_CLOUD_TEST_ENDPOINT)
        if (BuildConfig.CAESAR_CLOUD_TEST_ENDPOINT.isNotEmpty()) {
            assertEquals("debug", BuildConfig.BUILD_TYPE)
            assertEquals(CAESAR_CLOUD_LOOPBACK_ENDPOINT, BuildConfig.CAESAR_CLOUD_TEST_ENDPOINT)
        }
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected endpoint rejection")
        } catch (failure: AiRoutingException) {
            assertEquals("cloud_not_configured", failure.code)
        }
    }
}
