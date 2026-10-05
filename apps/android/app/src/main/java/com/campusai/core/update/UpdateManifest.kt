package com.campusai.core.update

import com.campusai.core.model.AppUpdate
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal const val UPDATE_ORIGIN = "https://campusai.campus3ai.xyz/updates"
internal const val RELEASE_ORIGIN = "https://github.com/liburce0412-alt/Caesar-Infinity-Personal-AI-Assistant/releases"
internal const val MAX_APK_BYTES = 512L * 1024 * 1024

internal fun parseUpdateManifest(text: String): AppUpdate {
    require(text.length <= 32_768) { "更新信息过大" }
    val json = JSONObject(text)
    val name = json.getString("versionName")
    require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(name)) { "版本信息无效" }
    val code = json.getInt("versionCode")
    val size = json.getLong("sizeBytes")
    val hash = json.getString("sha256").lowercase()
    require(code > 0 && size in 1..MAX_APK_BYTES && Regex("[a-f0-9]{64}").matches(hash)) { "更新校验信息无效" }
    val apkName = "caesar-v$name.apk"
    val primary = json.getString("apkUrl")
    val github = json.getString("githubUrl")
    require(primary == "$UPDATE_ORIGIN/releases/v$name/$apkName") { "服务器下载地址无效" }
    require(github == "$RELEASE_ORIGIN/download/v$name/$apkName") { "GitHub 下载地址无效" }
    require(primary.toHttpUrl().isHttps && github.toHttpUrl().isHttps)
    return AppUpdate(code, name, json.optString("updateLog").take(12_000), primary,
        sha256 = hash, sizeBytes = size, githubUrl = github)
}

internal fun verifiedUpdateFile(file: File, release: AppUpdate): Boolean {
    if (!file.isFile || file.length() != release.sizeBytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } == release.sha256
}
