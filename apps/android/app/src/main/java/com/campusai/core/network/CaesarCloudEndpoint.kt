package com.campusai.core.network

import com.campusai.core.ai.AiRoutingException

internal const val CAESAR_CLOUD_LOOPBACK_ENDPOINT = "http://127.0.0.1:18789/caesar-cloud/v1/chat"

internal fun caesarCloudChatEndpoint(
    baseUrl: String,
    buildType: String = "release",
    testEndpoint: String = "",
): String {
    if (testEndpoint.isNotEmpty()) {
        if (buildType != "debug" || testEndpoint != CAESAR_CLOUD_LOOPBACK_ENDPOINT) {
            throw cloudEndpointNotConfigured()
        }
        return testEndpoint
    }
    val base = baseUrl.trimEnd('/')
    if (!base.startsWith("https://")) throw cloudEndpointNotConfigured()
    return "$base/caesar-cloud/v1/chat"
}

private fun cloudEndpointNotConfigured() =
    AiRoutingException("cloud_not_configured", "Caesar cloud free 尚未配置。")
