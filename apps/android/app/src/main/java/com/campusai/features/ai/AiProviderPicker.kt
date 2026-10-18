package com.campusai.features.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.CaesarSlidingSelector
import com.campusai.core.designsystem.SpectraModalBottomSheet
import com.campusai.core.model.AiProvider
import com.campusai.core.model.AiMode

fun AiProvider.displayName(): String = when (this) {
    AiProvider.CAESAR_CLOUD_FREE -> "Caesar cloud free"
    AiProvider.LOCAL -> "本地模型"
    AiProvider.AUTO -> "自动 · 本机优先"
    AiProvider.DEEPSEEK -> "DeepSeek"
    AiProvider.GOOGLE_GEMINI -> "Google Gemini"
    AiProvider.CODEX -> "Codex"
}

@Composable
internal fun AiCloudAnswerMode(
    provider: AiProvider,
    mode: AiMode,
    enabled: Boolean,
    motionEnabled: Boolean,
    onSelect: (AiMode) -> Unit,
) {
    Text("回答方式", style = MaterialTheme.typography.labelLarge)
    if (provider == AiProvider.CAESAR_CLOUD_FREE) {
        Text("快速回答 · GLM-4-Flash-250414", style = MaterialTheme.typography.bodyMedium)
        Text("免费文本不提供深度思考模式；图片使用 GLM-4V-Flash。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        CaesarSlidingSelector(
            options = listOf("快速", "深度"),
            selectedIndex = if (mode == AiMode.FAST) 0 else 1,
            enabled = enabled,
            motionEnabled = motionEnabled,
            onSelected = { onSelect(if (it == 0) AiMode.FAST else AiMode.DEEP) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A single entry in the page; the complete names live in a scrollable radio list. */
@Composable
fun AiProviderPicker(
    selected: AiProvider,
    onSelect: (AiProvider) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    GlassPanel(modifier.fillMaxWidth(), radius = 20, shadowed = false,
        onClick = if (enabled) ({ expanded = true }) else null) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Cloud, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("当前 AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected.displayName(), style = MaterialTheme.typography.titleSmall)
            }
            Icon(Icons.Rounded.ExpandMore, "切换 AI")
        }
    }
    if (expanded) SpectraModalBottomSheet(onDismissRequest = { expanded = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("选择 AI", style = MaterialTheme.typography.titleLarge)
            AiProviderChoices(selected, enabled) {
                onSelect(it)
                expanded = false
            }
        }
    }
}

@Composable
internal fun AiProviderChoices(selected: AiProvider, enabled: Boolean, onSelect: (AiProvider) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(AiProvider.CAESAR_CLOUD_FREE, AiProvider.LOCAL, AiProvider.AUTO,
            AiProvider.CODEX, AiProvider.DEEPSEEK, AiProvider.GOOGLE_GEMINI).forEach { provider ->
            val chosen = selected == provider
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else MaterialTheme.colorScheme.surface.copy(alpha = .22f))
                .selectable(chosen, enabled, Role.RadioButton) { onSelect(provider) }
                .heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(provider.displayName(), style = MaterialTheme.typography.titleSmall)
                    Text(when (provider) {
                        AiProvider.CAESAR_CLOUD_FREE -> "文字与图片 · 无需个人 Key"
                        AiProvider.LOCAL -> "离线使用 · 内容留在本机"
                        AiProvider.AUTO -> "本机不可用时，由你选择云端"
                        AiProvider.CODEX -> "使用你的私有服务"
                        else -> "使用你的个人 Key"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RadioButton(selected = chosen, onClick = null, enabled = enabled)
            }
        }
    }
}
