package com.campusai.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.campusai.core.designsystem.CaesarSlidingSelector
import com.campusai.core.designsystem.SpectraTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campusai.features.community.CommunityFeedScope

@Composable
internal fun CommunityScopeChoice(scope: CommunityFeedScope, onSelect: (CommunityFeedScope) -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        CaesarSlidingSelector(
            options = CommunityFeedScope.entries.map { if (it == CommunityFeedScope.PUBLIC) "公开广场" else "只看我的" },
            selectedIndex = CommunityFeedScope.entries.indexOf(scope),
            onSelected = { onSelect(CommunityFeedScope.entries[it]) },
            enabled = enabled,
            motionEnabled = SpectraTheme.tokens.motion.enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (scope == CommunityFeedScope.MINE) "我发表的全部内容，公开或私密都在这里" else "看看大家分享的公开内容",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f),
        )
    }
}
