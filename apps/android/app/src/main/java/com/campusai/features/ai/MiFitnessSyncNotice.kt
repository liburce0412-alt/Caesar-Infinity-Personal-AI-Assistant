package com.campusai.features.ai

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.campusai.core.health.mifitness.MiFitnessDeviceSync
import com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus

@Composable
internal fun MiFitnessSyncNotice(status: MiFitnessDeviceSyncStatus?, syncing: Boolean) {
    if (status == null) return
    val context = LocalContext.current
    Column {
        Text(
            miFitnessDeviceSyncMessage(status, syncing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f),
        )
        if (!syncing) TextButton(onClick = {
            if (!MiFitnessDeviceSync(context).openApp()) {
                Toast.makeText(context, "无法打开小米运动健康，请确认已安装并启用。", Toast.LENGTH_SHORT).show()
            }
        }) {
            Text("打开小米运动健康")
        }
    }
}

internal fun miFitnessDeviceSyncMessage(status: MiFitnessDeviceSyncStatus, syncing: Boolean): String = when (status) {
    MiFitnessDeviceSyncStatus.REQUESTED -> if (syncing) {
        "已发送手环同步请求，稍候刷新云端数据。"
    } else {
        "已发送后台同步请求；尚未确认手环同步完成，云端上传可能稍有延迟。如数据未更新，可打开小米运动健康检查连接并同步。"
    }
    MiFitnessDeviceSyncStatus.APP_UNAVAILABLE -> "小米运动健康未安装或未启用，本次仅尝试刷新云端数据。"
    MiFitnessDeviceSyncStatus.APP_STOPPED -> "小米运动健康已停止，请先打开它并连接手环。本次仅尝试刷新云端数据。"
    MiFitnessDeviceSyncStatus.BLOCKED -> "系统未允许发送手环同步请求，本次仅尝试刷新云端数据。可打开小米运动健康手动同步。"
}
