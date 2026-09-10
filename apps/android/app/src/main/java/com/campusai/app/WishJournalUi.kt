package com.campusai.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.campusai.core.designsystem.*
import com.campusai.core.model.UiState
import com.campusai.features.community.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WishDateChoice(value: String, onChange: (String) -> Unit, enabled: Boolean) {
    var choosing by remember { mutableStateOf(false) }
    SpectraSurface(Modifier.fillMaxWidth()) {
        Text("希望实现的日子（可选）", style = MaterialTheme.typography.titleMedium)
        Text("给期待一个日子，也可以让它慢慢发生。", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpectraAction(value.ifBlank { "选个日子" }, { choosing = true }, enabled = enabled)
            if (value.isNotBlank()) TextButton(enabled = enabled, onClick = { onChange("") }) { Text("清除日期") }
        }
    }
    if (choosing) {
        val initial = runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        SpectraDialog(onDismissRequest = { choosing = false }) {
            Column {
                DatePicker(picker, colors = DatePickerDefaults.colors(containerColor = Color.Transparent))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { choosing = false }) { Text("取消") }
                    TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                        picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString()) }
                        choosing = false
                    }) { Text("确定") }
                }
            }
        }
    }
}

@Composable
internal fun OwnedContentDialog(title: String, busy: Boolean, error: String?, onClose: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SpectraDialog(onDismissRequest = { if (!busy) onClose() }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (confirming) "删除这条内容？" else title, style = MaterialTheme.typography.titleLarge)
            if (confirming) Text("删除后，它和下面的留言会从页面中移除。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!confirming) SpectraAction("修改内容", onEdit, Modifier.fillMaxWidth(), enabled = !busy)
            SpectraAction(if (busy) "正在删除" else if (confirming) "确认删除" else "删除内容",
                { if (confirming) onDelete() else confirming = true }, Modifier.fillMaxWidth(), enabled = !busy)
            TextButton(enabled = !busy, onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("取消") }
        }
    }
}

@Composable
internal fun ListingDetails(
    listing: MarketplaceListing, ownListing: Boolean,
    comments: UiState<List<CommunityComment>>, busy: Boolean, error: String?,
    onClose: () -> Unit, onFavorite: () -> Unit, onContact: () -> Unit,
    onManage: () -> Unit, onComplete: () -> Unit, onRetry: () -> Unit,
    onComment: (String, () -> Unit) -> Unit,
) {
    var draft by rememberSaveable(listing.id) { mutableStateOf("") }
    SpectraFullScreenDialog(onDismissRequest = onClose, mood = PageMood.COMMERCE) {
        SpectraPageScaffold(mood = PageMood.COMMERCE) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(bottom = 48.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpectraIconAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回心愿墙", onClose)
                    Text("留给未来的心愿", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (ownListing) TextButton(enabled = !busy, onClick = onManage) { Text("管理") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        SpectraSurface(Modifier.fillMaxWidth(), mood = PageMood.COMMERCE) {
                            if (listing.mediaUrl.isNotBlank()) AsyncImage(listing.mediaUrl, "心愿图片", Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                            Text(listing.title, style = MaterialTheme.typography.headlineMedium)
                            Text(wishRecordedTime(listing.createdAt, full = true), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
                            if (listing.description.isNotBlank()) Text(listing.description, style = MaterialTheme.typography.bodyLarge)
                            listing.priceCents?.let { Text("期待的预算 · ${wishPriceLabel(it)}", style = MaterialTheme.typography.bodyMedium) }
                            if (listing.location.isNotBlank()) Text(listing.location, style = MaterialTheme.typography.bodyMedium)
                            if (listing.targetDate.isNotBlank()) {
                                Text("希望实现于 ${listing.targetDate}", style = MaterialTheme.typography.bodyMedium)
                                if (listing.completedAt.isBlank()) wishDateMessage(listing.targetDate)?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ownListing) SpectraAction(if (listing.completedAt.isBlank()) "实现了，留个纪念" else "编辑留念", onComplete, enabled = !busy)
                                else SpectraAction("联系发布者", onContact, enabled = !busy)
                                SpectraAction("收藏", onFavorite, enabled = !busy)
                            }
                        }
                    }
                    if (listing.completedAt.isNotBlank()) item {
                        SpectraSurface(Modifier.fillMaxWidth(), emphasized = true) {
                            Text("后来，它真的发生了", style = MaterialTheme.typography.titleLarge)
                            Text(wishRecordedTime(listing.completedAt, full = true), style = MaterialTheme.typography.bodySmall)
                            Text(listing.completionNote.ifBlank { "这一刻，值得被记住。" }, style = MaterialTheme.typography.bodyLarge)
                            if (listing.completionMediaUrl.isNotBlank()) AsyncImage(listing.completionMediaUrl, "实现心愿时的照片", Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                        }
                    }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    item { Text(if (ownListing) "评论 · 也可以写给自己" else "给心愿留句话", style = MaterialTheme.typography.titleLarge) }
                    when (comments) {
                        UiState.Loading -> item { Text("正在打开留言…") }
                        UiState.Empty -> item { Text(if (ownListing) "今天，离它近了一点吗？把这一小步记在这里。" else "一句真诚的回应，也能让期待多一点温度。", color = MaterialTheme.colorScheme.onSurface.copy(.65f)) }
                        is UiState.Error -> item { Text(comments.message, color = MaterialTheme.colorScheme.error); SpectraAction("重试", onRetry) }
                        is UiState.Data -> items(comments.value, key = { it.id }) { CommentRow(it) }
                        is UiState.Offline -> {
                            item { SpectraAction("重新读取留言", onRetry) }
                            items(comments.value, key = { it.id }) { CommentRow(it) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpectraTextField(draft, { draft = it.take(2000) }, Modifier.weight(1f), placeholder = { Text("写下评论…") }, maxLines = 4)
                    SpectraIconAction(Icons.AutoMirrored.Rounded.Send, "发布心愿评论", { onComment(draft) { draft = "" } }, enabled = draft.isNotBlank() && !busy)
                }
            }
        }
    }
}

@Composable
internal fun WishCompletionDialog(wish: MarketplaceListing, busy: Boolean, error: String?, onClose: () -> Unit, onSave: (String, UploadImage?) -> Unit) {
    var note by rememberSaveable(wish.id) { mutableStateOf(wish.completionNote) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { imageUri = it }
    SpectraFullScreenDialog(onDismissRequest = { if (!busy && !preparing) onClose() }, mood = PageMood.COMMERCE) {
        SpectraPageScaffold(mood = PageMood.COMMERCE) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(enabled = !busy && !preparing, onClick = onClose) { Text("返回心愿") }
                Text("后来，它是这样实现的", style = MaterialTheme.typography.headlineMedium)
                Text(wish.title, style = MaterialTheme.typography.titleLarge)
                Text("留一句当时的感受，或放一张照片。空着也没关系，这一天已经值得纪念。")
                SpectraTextField(note, { note = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text("此刻的感受（可选）") }, minLines = 4)
                val preview = imageUri?.toString() ?: wish.completionMediaUrl
                if (preview.isNotBlank()) AsyncImage(preview, "留念照片", Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Crop)
                SpectraAction("加一张留念照片", { picker.launch("image/*") }, enabled = !busy && !preparing)
                (mediaError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                SpectraPrimaryButton(if (busy || preparing) "正在保存" else "把这一刻留下", enabled = !busy && !preparing, onClick = {
                    preparing = true
                    scope.launch {
                        try {
                            val image = imageUri?.let { uri -> runCatching { readUploadImage(context, uri) }.getOrElse { mediaError = it.message; return@launch } }
                            onSave(note, image)
                        } finally { preparing = false }
                    }
                })
            }
        }
    }
}

@Composable
internal fun WishRevisitCard(wish: MarketplaceListing, onClick: () -> Unit) {
    SpectraSurface(Modifier.fillMaxWidth(), emphasized = true, onClick = onClick) {
        Text("回望一下", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(wish.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text("${wishRecordedTime(wish.createdAt)}的心愿，现在又有了什么新的故事？", style = MaterialTheme.typography.bodyMedium)
    }
}
