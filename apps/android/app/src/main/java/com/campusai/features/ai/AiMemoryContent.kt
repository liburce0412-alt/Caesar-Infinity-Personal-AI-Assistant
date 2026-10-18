package com.campusai.features.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.PageMood
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraStatus
import com.campusai.core.designsystem.SpectraStatusTone
import com.campusai.core.designsystem.SpectraSurface

@Composable
internal fun MemoryManagerSection(
    memories: List<CaesarMemoryUiItem>,
    onConfirm: (String) -> Unit,
    onEdit: (CaesarMemoryUiItem) -> Unit,
    onDelete: (CaesarMemoryUiItem) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val now = System.currentTimeMillis()
    SpectraSurface(Modifier.fillMaxWidth(), mood = PageMood.PERSONAL) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("长期记忆", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已确认内容只用于本机对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                )
            }
            if (memories.isNotEmpty()) TextButton(onClick = onDeleteAll) { Text("清空") }
        }
        if (memories.isEmpty()) {
            Text("暂无记忆。你可以对 Caesar∞ 说“记住我喜欢……”，确认后才会保存。", style = MaterialTheme.typography.bodyMedium)
        } else {
            memories.forEachIndexed { index, memory ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                val expired = memory.expiresAt?.let { it <= now } == true
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(memoryTypeLabel(memory.type), style = MaterialTheme.typography.labelLarge, color = SpectraColors.Focus, modifier = Modifier.weight(1f))
                        SpectraStatus(
                            when {
                                expired -> "已过期"
                                memory.confirmed -> "已确认"
                                else -> "待确认"
                            },
                            tone = if (memory.confirmed && !expired) SpectraStatusTone.SUCCESS else SpectraStatusTone.NEUTRAL,
                        )
                    }
                    Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!memory.confirmed && !expired) TextButton(onClick = { onConfirm(memory.id) }) { Text("确认") }
                        TextButton(onClick = { onEdit(memory) }) { Text("编辑") }
                        TextButton(onClick = { onDelete(memory) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

internal fun memoryTypeLabel(type: String): String = when (type) {
    "preference" -> "偏好"
    "fact" -> "事实"
    "goal" -> "目标"
    "routine" -> "习惯"
    else -> "记忆"
}
