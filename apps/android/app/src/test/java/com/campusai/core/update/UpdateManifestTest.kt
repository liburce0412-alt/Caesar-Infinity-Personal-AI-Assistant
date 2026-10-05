package com.campusai.core.update

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateManifestTest {
    private fun descriptor() = JSONObject().put("versionName", "2.1.1").put("versionCode", 5)
        .put("sizeBytes", 3).put("sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        .put("apkUrl", "$UPDATE_ORIGIN/releases/v2.1.1/caesar-v2.1.1.apk")
        .put("githubUrl", "$RELEASE_ORIGIN/download/v2.1.1/caesar-v2.1.1.apk")

    @Test fun `accepts known download sources and checks actual file bytes`() {
        val release = parseUpdateManifest(descriptor().toString())
        val file = File.createTempFile("update-test", ".apk")
        try {
            file.writeText("abc")
            assertTrue(verifiedUpdateFile(file, release))
            file.writeText("abd")
            assertFalse(verifiedUpdateFile(file, release))
            file.writeText("abcde")
            assertFalse(verifiedUpdateFile(file, release))
        } finally { file.delete() }
    }

    @Test fun `rejects redirect domains paths oversized downloads and invalid digest`() {
        listOf("apkUrl" to "https://evil.example/a.apk", "githubUrl" to "$RELEASE_ORIGIN/download/v2.1.0/other.apk",
            "versionName" to "../2.1.1", "sizeBytes" to MAX_APK_BYTES + 1, "sha256" to "bad", "versionCode" to 0).forEach { (key, value) ->
            assertTrue(key, runCatching { parseUpdateManifest(descriptor().put(key, value).toString()) }.isFailure)
        }
    }
}
