package com.campusai.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.campusai.BuildConfig
import com.campusai.core.model.AppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class UpdateSource(val label: String) { SERVER("服务器"), GITHUB("GitHub") }
enum class UpdateStage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, CURRENT, ERROR }
data class UpdateState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val release: AppUpdate? = null,
    val source: UpdateSource = UpdateSource.SERVER,
    val downloaded: Long = 0,
    val message: String = "",
    val visible: Boolean = false,
)

/** Process-scoped transfer: changing tabs or closing the sheet does not restart a download. */
class AppUpdateManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var activeCall: okhttp3.Call? = null
    private val root get() = File(context.cacheDir, "app-updates").also { it.mkdirs() }

    fun open() {
        mutableState.update { it.copy(visible = true) }
        if (job?.isActive != true && state.value.stage != UpdateStage.READY) check(manual = true)
    }

    fun dismiss() {
        state.value.release?.let { preferences.edit().putInt("dismissedVersion", it.versionCode).apply() }
        mutableState.update { it.copy(visible = false) }
    }

    fun selectSource(source: UpdateSource) {
        if (job?.isActive != true) mutableState.update { it.copy(source = source) }
    }

    fun check(manual: Boolean = false) {
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && now - preferences.getLong("lastCheck", 0) in 0 until TimeUnit.HOURS.toMillis(24)) return
        mutableState.update { it.copy(stage = UpdateStage.CHECKING, message = "", visible = manual || it.visible) }
        job = scope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    var result: AppUpdate? = null
                    for (url in listOf("$UPDATE_ORIGIN/latest.json", "$RELEASE_ORIGIN/latest/download/update.json")) {
                        try {
                            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                if (!response.isSuccessful) throw IOException("更新服务暂不可用")
                                val bytes = response.body?.byteStream()?.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(4096)
                                    while (output.size() <= 32_768) {
                                        val count = input.read(buffer, 0, minOf(buffer.size, 32_769 - output.size()))
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                                    ?: throw IOException("更新信息为空")
                                require(bytes.size <= 32_768)
                                result = parseUpdateManifest(bytes.toString(Charsets.UTF_8))
                            }
                            break
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Try the independent manifest mirror. */ }
                    }
                    result ?: throw IOException("暂时无法检查更新，请检查网络后重试")
                }
                preferences.edit().putLong("lastCheck", now).apply()
                val newer = release.versionCode > BuildConfig.VERSION_CODE
                val remind = newer && preferences.getInt("dismissedVersion", 0) != release.versionCode
                mutableState.update { it.copy(release = release,
                    stage = if (newer) UpdateStage.AVAILABLE else UpdateStage.CURRENT,
                    visible = it.visible || remind,
                    message = if (newer) "" else "已是最新版本") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(stage = UpdateStage.ERROR, message = "暂时无法检查更新，请检查网络后重试") }
            }
        }
    }

    fun download() {
        val release = state.value.release ?: return
        if (job?.isActive == true || release.versionCode <= BuildConfig.VERSION_CODE) return
        val preferred = state.value.source
        mutableState.update { it.copy(stage = UpdateStage.DOWNLOADING, downloaded = 0, message = "") }
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val final = File(root, "update-${release.versionCode}.apk")
                    if (verifiedUpdateFile(final, release)) return@withContext
                    require(root.usableSpace > release.sizeBytes + 32L * 1024 * 1024) { "空间不足，请清理后重试" }
                    var failure: Exception? = null
                    for ((index, source) in listOf(preferred, if (preferred == UpdateSource.SERVER) UpdateSource.GITHUB else UpdateSource.SERVER).withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val partial = File(root, "update-${release.versionCode}.part")
                        mutableState.update { it.copy(stage = UpdateStage.DOWNLOADING, source = source, downloaded = 0,
                            message = if (index == 0) "" else "原下载源未完成，已切换到${source.label}") }
                        try {
                            val url = if (source == UpdateSource.SERVER) release.apkUrl else release.githubUrl
                            val call = client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build())
                            activeCall = call
                            call.execute().use { response ->
                                if (!response.isSuccessful || !response.request.url.isHttps) throw IOException("下载源暂不可用")
                                val body = response.body ?: throw IOException("下载内容为空")
                                var bytes = 0L
                                var lastUpdate = 0L
                                body.byteStream().use { input -> partial.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        bytes += count
                                        if (bytes > release.sizeBytes) throw IOException("文件大小不匹配")
                                        output.write(buffer, 0, count)
                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdate >= 250) {
                                            mutableState.update { it.copy(downloaded = bytes) }
                                            lastUpdate = now
                                        }
                                    }
                                } }
                            }
                            mutableState.update { it.copy(stage = UpdateStage.VERIFYING, downloaded = release.sizeBytes) }
                            if (!verifiedUpdateFile(partial, release)) throw IOException("安装包校验失败")
                            if (final.exists()) final.delete()
                            if (!partial.renameTo(final)) throw IOException("无法保存安装包")
                            failure = null
                            break
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failure = error }
                        finally { activeCall = null; partial.delete() }
                    }
                    failure?.let { throw IOException("两个下载源均未完成，请稍后重试", it) }
                }
                mutableState.update { it.copy(stage = UpdateStage.READY, downloaded = release.sizeBytes, message = "下载完成，校验通过") }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(stage = UpdateStage.AVAILABLE, downloaded = 0, message = "下载已取消") }
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(stage = UpdateStage.ERROR, message = error.message ?: "下载未完成，请重试") }
            }
        }
    }

    fun cancelDownload() { job?.cancel(); activeCall?.cancel() }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun install() {
        val release = state.value.release ?: return
        if (state.value.stage != UpdateStage.READY || job?.isActive == true) return
        job = scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    val apk = File(root, "update-${release.versionCode}.apk")
                    require(verifiedUpdateFile(apk, release)) { "安装包已失效，请重新下载" }
                    validateApk(apk, release)
                    Intent(Intent.ACTION_VIEW).setDataAndType(
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk),
                        "application/vnd.android.package-archive",
                    ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(stage = UpdateStage.ERROR, message = error.message ?: "无法打开安装界面") } }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: AppUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("安装包格式无效")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && version == release.versionCode.toLong() && version > BuildConfig.VERSION_CODE) { "安装包版本或应用标识不匹配" }
        fun certificates(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        }
        require(certificates(installed).isNotEmpty() && certificates(installed) == certificates(archive)) { "安装包签名不匹配，已阻止安装" }
    }

    companion object {
        @Volatile private var instance: AppUpdateManager? = null
        fun get(context: Context): AppUpdateManager = instance ?: synchronized(this) {
            instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
        }
    }
}
