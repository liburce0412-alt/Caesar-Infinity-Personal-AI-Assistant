package com.campusai.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.campusai.BuildConfig
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.designsystem.SpectraPrimaryButton
import com.campusai.core.update.AppUpdateManager
import com.campusai.core.update.UpdateSource
import com.campusai.core.update.UpdateStage
import com.campusai.core.update.UpdateState
import java.util.Locale

@Composable
internal fun AppUpdateHost(enabled: Boolean) {
    val context = LocalContext.current
    val manager = remember(context.applicationContext) { AppUpdateManager.get(context) }
    val state by manager.state.collectAsState()
    LaunchedEffect(enabled) { if (enabled) manager.check() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (manager.canInstall()) manager.install()
    }
    if (enabled && state.visible) SpectraDialog(onDismissRequest = manager::dismiss) {
        UpdateDialogContent(state, manager::dismiss, { manager.check(manual = true) }, manager::download,
            manager::cancelDownload, manager::selectSource) {
            if (manager.canInstall()) manager.install()
            else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }
    }
}

@Composable
internal fun UpdateDialogContent(
    state: UpdateState,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSource: (UpdateSource) -> Unit,
    onInstall: () -> Unit,
) {
    val release = state.release
    val newer = release != null && release.versionCode > BuildConfig.VERSION_CODE
    val transferring = state.stage == UpdateStage.DOWNLOADING || state.stage == UpdateStage.VERIFYING
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (newer) "发现新版本 ${release?.versionName}" else "应用更新", style = MaterialTheme.typography.headlineSmall)
        Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        if (newer && release != null) {
            Text("更新包 ${String.format(Locale.ROOT, "%.1f", release.sizeBytes / 1048576.0)} MB · 覆盖更新保留应用数据", style = MaterialTheme.typography.bodySmall)
            if (release.updateLog.isNotBlank()) Text(release.updateLog, style = MaterialTheme.typography.bodyMedium)
            if (state.stage != UpdateStage.READY) {
                Text("下载来源", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    UpdateSource.entries.forEach { source ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = source == state.source, onClick = { onSource(source) }, enabled = !transferring)
                            Text(source.label)
                        }
                    }
                }
            }
        }
        if (state.stage == UpdateStage.CHECKING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在检查更新…")
        }
        if (transferring) {
            val fraction = if ((release?.sizeBytes ?: 0) > 0) state.downloaded.toFloat() / release!!.sizeBytes else 0f
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(if (state.stage == UpdateStage.VERIFYING) "正在校验安装包…" else "${state.source.label} · ${(fraction * 100).toInt()}%")
            Text("请保持应用运行；下载源失败时会自动尝试另一来源。", style = MaterialTheme.typography.bodySmall)
        }
        if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodyMedium)
        when (state.stage) {
            UpdateStage.READY -> {
                Text("安装由系统确认。首次更新可能需要允许 Caesar 安装应用。", style = MaterialTheme.typography.bodySmall)
                SpectraPrimaryButton("安装更新", onInstall, Modifier.fillMaxWidth())
            }
            UpdateStage.AVAILABLE -> SpectraPrimaryButton("立即更新", onDownload, Modifier.fillMaxWidth())
            UpdateStage.ERROR -> SpectraPrimaryButton(if (newer) "重新下载" else "重试检查", if (newer) onDownload else onCheck, Modifier.fillMaxWidth())
            UpdateStage.CURRENT, UpdateStage.IDLE -> TextButton(onClick = onCheck) { Text("重新检查") }
            else -> Unit
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (transferring) TextButton(onClick = onCancel) { Text("取消下载") }
            TextButton(onClick = onDismiss) { Text(if (transferring) "收起" else if (newer) "稍后" else "关闭") }
        }
    }
}
