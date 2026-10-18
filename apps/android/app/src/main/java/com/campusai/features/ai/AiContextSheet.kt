package com.campusai.features.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.SpectraAlertDialog
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.designsystem.SpectraModalBottomSheet
import com.campusai.core.model.AiProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiContextSheet(
    selection: AiContextSelection,
    provider: AiProvider,
    healthState: CaesarHealthUiState,
    memories: List<CaesarMemoryUiItem>,
    onChange: (AiContextSelection) -> Unit,
    onConfirmMemory: (String) -> Unit,
    onUpdateMemory: (String, String) -> Unit,
    onForgetMemory: (String) -> Unit,
    onForgetAllMemories: () -> Unit,
    onRefreshHealth: () -> Unit,
    onSyncMiFitnessSteps: () -> Unit,
    onHealthPermissions: () -> Unit,
    onDismiss: () -> Unit,
    memoryScopeKey: String = "local_user",
    viewingLocalMemories: Boolean = true,
    onShowLocalMemories: (Boolean) -> Unit = {},
) {
    var editingMemory by remember(memoryScopeKey) { mutableStateOf<CaesarMemoryUiItem?>(null) }
    var editedContent by remember(memoryScopeKey) { mutableStateOf("") }
    var deletingMemory by remember(memoryScopeKey) { mutableStateOf<CaesarMemoryUiItem?>(null) }
    var confirmForgetAll by remember(memoryScopeKey) { mutableStateOf(false) }
    SpectraModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text("本次对话依据", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (provider == AiProvider.LOCAL) "所选内容仅在本机处理，不会离开设备。"
                else "所选的学习上下文可能发送给当前云端 Provider；健康摘要只有在本次请求明确勾选后才会附带。",
                Modifier.padding(top = 5.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.62f),
            )
            HealthStatus(
                state = healthState,
                onRefresh = onRefreshHealth,
                onCloudRefresh = onSyncMiFitnessSteps,
                onPermissions = onHealthPermissions,
            )
            Spacer(Modifier.height(14.dp))
            GlassPanel(Modifier.fillMaxWidth(), radius = 16, shadowed = false) {
                Column {
                    ContextToggle("时间记录与精确统计", selection.timeRecords) { onChange(selection.copy(timeRecords = it)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                    ContextToggle("课程与已计算冲突", selection.courses) { onChange(selection.copy(courses = it)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                    ContextToggle("我自己的相关动态", selection.ownPosts) { onChange(selection.copy(ownPosts = it)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                    ContextToggle("明确加入公共树洞动态", selection.publicPosts) { onChange(selection.copy(publicPosts = it)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                    ContextToggle("本次附带今日健康摘要（发送后自动关闭）", selection.healthSummary) {
                        onChange(selection.copy(healthSummary = it))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (viewingLocalMemories) "本机旧记忆" else "当前账号记忆", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { onShowLocalMemories(!viewingLocalMemories) }) {
                    Text(if (viewingLocalMemories) "查看当前账号" else "管理本机旧记忆")
                }
            }
            if (viewingLocalMemories) {
                Text("旧记忆保留在本机，不会自动加入登录账号的对话。", style = MaterialTheme.typography.bodySmall)
            }
            MemoryManagerSection(
                memories = memories,
                onConfirm = onConfirmMemory,
                onEdit = { memory -> editingMemory = memory; editedContent = memory.content },
                onDelete = { deletingMemory = it },
                onDeleteAll = { confirmForgetAll = true },
            )
            Text(
                "私聊、账号安全信息、原始健康序列与他人私有数据永不加入。",
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.52f),
            )
        }
    }

    editingMemory?.let { memory ->
        SpectraDialog(onDismissRequest = { editingMemory = null }) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("编辑记忆", style = MaterialTheme.typography.titleLarge)
                    Text(memoryTypeLabel(memory.type), style = MaterialTheme.typography.labelLarge, color = SpectraColors.Focus)
                    GlassPanel(Modifier.fillMaxWidth(), radius = 16, shadowed = false) {
                        BasicTextField(
                            value = editedContent,
                            onValueChange = { editedContent = it.take(1_000) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(14.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(SpectraColors.Focus),
                        )
                    }
                    Text("${editedContent.length}/1000", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.5f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editingMemory = null }) { Text("取消") }
                    TextButton(
                        enabled = editedContent.isNotBlank(),
                        onClick = {
                            onUpdateMemory(memory.id, editedContent)
                            editingMemory = null
                        },
                    ) { Text("保存") }
                }
            }
        }
    }
    deletingMemory?.let { memory ->
        SpectraAlertDialog(
            onDismissRequest = { deletingMemory = null },
            title = "删除这条记忆？",
            message = memory.content,
            confirmLabel = "删除",
            dismissLabel = "取消",
            destructive = true,
            onConfirm = { onForgetMemory(memory.id); deletingMemory = null },
        )
    }
    if (confirmForgetAll) {
        SpectraAlertDialog(
            onDismissRequest = { confirmForgetAll = false },
            title = if (viewingLocalMemories) "清空本机旧记忆？" else "清空当前账号记忆？",
            message = "清空后 Caesar∞ 将不再使用此范围的偏好、事实、目标与习惯。",
            confirmLabel = "清空此范围",
            dismissLabel = "取消",
            destructive = true,
            onConfirm = { onForgetAllMemories(); confirmForgetAll = false },
        )
    }
}
