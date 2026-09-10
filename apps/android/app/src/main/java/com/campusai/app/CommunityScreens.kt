package com.campusai.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.campusai.core.designsystem.SpectraTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import com.campusai.features.community.wishPriceCents
import com.campusai.features.community.wishPriceValid
import com.campusai.features.community.wishPriceLabel
import com.campusai.features.community.wishRecordedTime
import com.campusai.features.community.wishDateMessage
import com.campusai.features.community.wishToRevisit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.campusai.core.designsystem.BrandMark
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.PageMood
import com.campusai.core.designsystem.SpectraAction
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.designsystem.SpectraFullScreenDialog
import com.campusai.core.designsystem.SpectraIconAction
import com.campusai.core.designsystem.SpectraPageScaffold
import com.campusai.core.designsystem.SpectraPrimaryButton
import com.campusai.core.designsystem.SpectraStateKind
import com.campusai.core.designsystem.SpectraStatePane
import com.campusai.core.designsystem.SpectraStatus
import com.campusai.core.designsystem.SpectraStatusTone
import com.campusai.core.designsystem.SpectraSurface
import com.campusai.core.designsystem.SpectraTheme
import com.campusai.core.designsystem.SlideConfirm
import com.campusai.core.designsystem.Tomorrow
import com.campusai.core.model.UiState
import com.campusai.features.community.CampusRemoteState
import com.campusai.features.community.CampusViewModel
import com.campusai.features.community.CommunityComment
import com.campusai.features.community.CommunityPost
import com.campusai.features.community.MarketplaceListing
import com.campusai.features.community.UploadImage
import com.campusai.features.community.CommunityFeedScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CampusScreen(
    state: CampusRemoteState,
    signedIn: Boolean,
    userId: String,
    displayName: String,
    viewModel: CampusViewModel,
    onLogin: () -> Unit,
    contentPadding: PaddingValues,
    onScopeChange: (CommunityFeedScope) -> Unit = viewModel::selectPostsScope,
) {
    val ownerName = displayName.trim().ifBlank { "我" }.take(16)
    val layout = SpectraTheme.layout
    val pageBottom = maxOf(contentPadding.calculateBottomPadding(), layout.pageBottomSpacing)
    var composing by rememberSaveable { mutableStateOf(false) }
    var selectedPost by remember { mutableStateOf<CommunityPost?>(null) }
    var reportingPost by remember { mutableStateOf<CommunityPost?>(null) }
    var editingPost by remember { mutableStateOf<CommunityPost?>(null) }
    var managingPost by remember { mutableStateOf<CommunityPost?>(null) }
    SpectraPageScaffold(mood = PageMood.SOCIAL) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = layout.pageHorizontalPadding,
                    end = layout.pageHorizontalPadding,
                    top = contentPadding.calculateTopPadding() + layout.pageTopSpacing,
                    bottom = pageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
            ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(if (state.postsScope == CommunityFeedScope.MINE) "${ownerName}的树洞" else "树洞广场", style = MaterialTheme.typography.headlineLarge); Text("收起喧闹，留下对你真正重要的声音", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.6f)) }
                    SpectraAction(
                        text = "刷新动态",
                        onClick = { if (signedIn) viewModel.refreshPosts() else onLogin() },
                        emphasized = true,
                        mood = PageMood.SOCIAL,
                        icon = Icons.Rounded.Refresh,
                    )
                }
            }
            item {
                CommunityScopeChoice(state.postsScope, onScopeChange, enabled = signedIn)
            }
            state.operationError?.let { message -> item { ErrorBar(message, PageMood.SOCIAL) { viewModel.clearOperationError() } } }
            when (val posts = state.posts) {
                UiState.Loading -> item {
                    SpectraStatePane(
                        kind = SpectraStateKind.LOADING,
                        title = "正在打开树洞",
                        detail = "正在同步最新留言；完成前不会用占位内容替代。",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                UiState.Empty -> item {
                    EmptyRemote(
                        title = if (state.postsScope == CommunityFeedScope.MINE) "还没有写下自己的故事" else "公开广场还很安静",
                        detail = if (state.postsScope == CommunityFeedScope.MINE) "你发表的私密与公开内容，都会留在这里。" else "审核通过的公开内容，会出现在这里。",
                        action = "写进树洞",
                        mood = PageMood.SOCIAL,
                    ) { composing = true }
                }
                is UiState.Error -> item { RemoteError(posts.message, signedIn, onLogin, viewModel::refreshPosts, PageMood.SOCIAL) }
                is UiState.Data -> items(posts.value.size) { index ->
                    val post = posts.value[index]
                    PostCard(post, { viewModel.toggleLike(post.id) }, { viewModel.toggleBookmark(post.id) }, { selectedPost = post; viewModel.openPostComments(post.id) }) { if (post.authorId == userId) managingPost = post else reportingPost = post }
                }
                is UiState.Offline -> {
                    item {
                        SpectraStatePane(
                            kind = SpectraStateKind.OFFLINE,
                            title = "显示上次同步的动态",
                            detail = "当前无法联网；点赞、收藏和发布需要恢复网络后才能确认。",
                            modifier = Modifier.fillMaxWidth(),
                            actionLabel = "重新读取",
                            onAction = viewModel::refreshPosts,
                        )
                    }
                    items(posts.value.size) { index ->
                        val post = posts.value[index]
                        PostCard(post, { viewModel.toggleLike(post.id) }, { viewModel.toggleBookmark(post.id) }, { selectedPost = post; viewModel.openPostComments(post.id) }) { if (post.authorId == userId) managingPost = post else reportingPost = post }
                    }
                }
            }
            }
            GlassPanel(
                onClick = { if (signedIn) composing = true else onLogin() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = layout.pageHorizontalPadding, bottom = pageBottom + layout.compactGap),
                radius = 32,
                emphasized = true,
                opticalPriority = 2,
            ) {
                Row(Modifier.padding(horizontal = 20.dp).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Add, null)
                    Text("写进树洞", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    if (composing) PostComposer(
        busy = state.operationBusy,
        onClose = { composing = false },
        error = state.operationError,
        onPublish = { body, topic, anonymous, image, isPublic, _ -> viewModel.publishPost(userId, body, topic, anonymous, image, isPublic) { composing = false } },
    )
    editingPost?.let { post -> PostComposer(
        busy = state.operationBusy, initial = post, error = state.operationError,
        onClose = { editingPost = null },
        onPublish = { body, topic, anonymous, image, isPublic, removeImage ->
            viewModel.updatePost(post, body, topic, anonymous, image, removeImage, isPublic) { editingPost = null; selectedPost = null }
        },
    ) }
    managingPost?.let { post -> OwnedContentDialog(
        title = "管理树洞", busy = state.operationBusy, error = state.operationError,
        onClose = { managingPost = null },
        onEdit = { managingPost = null; selectedPost = null; editingPost = post; viewModel.clearOperationError() },
        onDelete = { viewModel.deletePost(post.id) { managingPost = null; selectedPost = null } },
    ) }
    selectedPost?.let { post ->
        PostDetails(
            post = post,
            comments = state.comments,
            busy = state.operationBusy,
            error = state.operationError,
            onDismiss = { viewModel.closePostComments(); selectedPost = null },
            onRetry = { viewModel.openPostComments(post.id) },
            onClearError = viewModel::clearOperationError,
            onPublish = { body, onSuccess -> viewModel.publishComment(post.id, body, onSuccess) },
            onManage = if (post.authorId == userId) ({ managingPost = post }) else null,
        )
    }
    reportingPost?.let { post -> ReportDialog(
        targetLabel = post.body.take(42),
        busy = state.operationBusy,
        onDismiss = { reportingPost = null },
        onSubmit = { reason, details -> viewModel.submitReport(userId, "post", post.id, reason, details) { reportingPost = null } },
    ) }
}

@Composable
internal fun PostCard(post: CommunityPost, onLike: () -> Unit, onBookmark: () -> Unit, onComments: () -> Unit, onReport: () -> Unit) {
    val hasImage = post.mediaUrl.isNotBlank()
    GlassPanel(Modifier.fillMaxWidth().then(if (hasImage) Modifier.aspectRatio(1f) else Modifier), onClick = onComments) {
        if (hasImage) {
            AsyncImage(post.mediaUrl, "树洞图片", Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(.88f), Color.Transparent, MaterialTheme.colorScheme.surface.copy(.96f)))))
        }
        Column(Modifier.then(if (hasImage) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(post.author, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(remoteTime(post.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
                }
                SpectraIconAction(Icons.Rounded.MoreHoriz, "更多操作", onReport)
            }
            if (hasImage) Spacer(Modifier.weight(1f))
            if (post.topic.isNotBlank()) Text("# ${post.topic}", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(post.body, style = MaterialTheme.typography.bodyLarge, maxLines = if (hasImage) 2 else 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SpectraAction(post.likes.toString(), onLike, selected = post.likedByMe, icon = if (post.likedByMe) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder)
                SpectraAction(if (post.comments == 0) "评论" else "${post.comments} 条评论", onComments, Modifier.weight(1f), icon = Icons.Rounded.ChatBubbleOutline)
                SpectraIconAction(Icons.Rounded.BookmarkBorder, "收藏", onBookmark)
            }
        }
    }
}

@Composable
private fun ReportDialog(targetLabel: String, busy: Boolean, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf("") }
    SpectraDialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("举报内容", style = MaterialTheme.typography.titleLarge)
            Text(targetLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
            SpectraTextField(reason, { reason = it.take(120) }, label = { Text("原因") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            SpectraTextField(details, { details = it.take(1000) }, label = { Text("补充说明") }, minLines = 3, shape = RoundedCornerShape(12.dp))
            Text("举报会进入审核队列，不会直接删除内容。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
                TextButton(enabled = reason.isNotBlank() && !busy, onClick = { onSubmit(reason, details) }) { Text(if (busy) "正在提交" else "提交举报") }
            }
        }
    }
}

@Composable
private fun PostDetails(
    post: CommunityPost,
    comments: UiState<List<CommunityComment>>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onClearError: () -> Unit,
    onPublish: (String, () -> Unit) -> Unit,
    onManage: (() -> Unit)? = null,
) {
    var draft by rememberSaveable(post.id) { mutableStateOf("") }
    SpectraFullScreenDialog(onDismissRequest = onDismiss, mood = PageMood.SOCIAL) {
        Box(Modifier.fillMaxSize()) {
            SpectraPageScaffold(mood = PageMood.SOCIAL) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .imePadding()
                        .padding(bottom = 48.dp),
                ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpectraIconAction(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        label = "返回树洞",
                        onClick = onDismiss,
                    )
                    Column(Modifier.weight(1f)) { Text("帖子与评论", style = MaterialTheme.typography.titleLarge); Text("${post.author} · ${remoteTime(post.createdAt)}", color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
                    SpectraIconAction(
                        icon = Icons.Rounded.Refresh,
                        label = "刷新评论",
                        onClick = onRetry,
                    )
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SpectraSurface(
                            modifier = Modifier.fillMaxWidth(),
                            mood = PageMood.SOCIAL,
                            emphasized = true,
                        ) {
                            if (post.topic.isNotBlank()) Text("# ${post.topic}", color = SpectraColors.Focus, style = MaterialTheme.typography.labelMedium)
                            if (post.mediaUrl.isNotBlank()) AsyncImage(post.mediaUrl, "树洞图片", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                            Text(post.body, style = MaterialTheme.typography.bodyLarge)
                            onManage?.let { SpectraAction("管理我的内容", it) }
                        }
                    }
                    error?.let { message -> item { ErrorBar(message, PageMood.SOCIAL, onClearError) } }
                    item { Text("评论", style = MaterialTheme.typography.titleLarge) }
                    when (comments) {
                        UiState.Loading -> item {
                            SpectraStatePane(
                                kind = SpectraStateKind.LOADING,
                                title = "正在读取评论",
                                detail = "会话不会因加载而自动发布草稿。",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        UiState.Empty -> item {
                            EmptyRemote(
                                title = "还没有评论",
                                detail = "这条动态尚无评论；你可以在下方写下具体而友善的回应。",
                                action = "重新检查",
                                mood = PageMood.SOCIAL,
                                onAction = onRetry,
                            )
                        }
                        is UiState.Error -> item { RemoteError(comments.message, true, {}, onRetry, PageMood.SOCIAL) }
                        is UiState.Data -> items(comments.value, key = { it.id }) { comment -> CommentRow(comment) }
                        is UiState.Offline -> {
                            item {
                                SpectraStatePane(
                                    kind = SpectraStateKind.OFFLINE,
                                    title = "显示上次同步的评论",
                                    detail = "恢复网络后再发布新评论。",
                                    modifier = Modifier.fillMaxWidth(),
                                    actionLabel = "重新读取",
                                    onAction = onRetry,
                                )
                            }
                            items(comments.value, key = { it.id }) { comment -> CommentRow(comment) }
                        }
                    }
                }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(.12f))
                            .navigationBarsPadding()
                            .heightIn(min = 76.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SpectraTextField(
                            value = draft,
                            onValueChange = { draft = it.take(2000) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("写下评论…") },
                            shape = RoundedCornerShape(20.dp),
                            maxLines = 4,
                        )
                        IconButton(
                            onClick = { onPublish(draft) { draft = "" } },
                            enabled = draft.isNotBlank() && !busy,
                            modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        ) { Icon(Icons.AutoMirrored.Rounded.Send, "发布评论", tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CommentRow(comment: CommunityComment) {
    SpectraSurface(Modifier.fillMaxWidth(), mood = PageMood.SOCIAL) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(comment.author, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (comment.moderationStatus == "pending") {
                SpectraStatus("待审核", tone = SpectraStatusTone.WARNING)
            }
        }
        Text(comment.body, style = MaterialTheme.typography.bodyLarge)
        Text(remoteTime(comment.createdAt), color = MaterialTheme.colorScheme.onSurface.copy(.48f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MarketScreen(
    state: CampusRemoteState,
    signedIn: Boolean,
    userId: String,
    viewModel: CampusViewModel,
    onLogin: () -> Unit,
    onOpenConversation: (String) -> Unit,
    contentPadding: PaddingValues,
    onScopeChange: (CommunityFeedScope) -> Unit = viewModel::selectListingsScope,
) {
    val layout = SpectraTheme.layout
    val pageBottom = maxOf(contentPadding.calculateBottomPadding(), layout.pageBottomSpacing)
    var composing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MarketplaceListing?>(null) }
    var editing by remember { mutableStateOf<MarketplaceListing?>(null) }
    var managing by remember { mutableStateOf<MarketplaceListing?>(null) }
    var completing by remember { mutableStateOf<MarketplaceListing?>(null) }
    val visibleWishes = when (val listings = state.listings) {
        is UiState.Data -> listings.value
        is UiState.Offline -> listings.value
        else -> emptyList()
    }
    LaunchedEffect(signedIn, state.listingsRefreshing, state.listingsHasSynced, state.listingsSyncError) {
        if (signedIn && !state.listingsRefreshing && !state.listingsHasSynced && state.listingsSyncError == null) {
            viewModel.refreshListings()
        }
    }
    SpectraPageScaffold(mood = PageMood.COMMERCE) {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(168.dp),
                contentPadding = PaddingValues(
                    start = layout.pageHorizontalPadding,
                    end = layout.pageHorizontalPadding,
                    top = contentPadding.calculateTopPadding() + layout.pageTopSpacing,
                    bottom = pageBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(layout.compactGap),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
            ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column { Text("心愿墙", style = MaterialTheme.typography.headlineLarge); Text("把想遇见、想交换的东西，认真留在这里", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.6f)); Spacer(Modifier.height(layout.compactGap)) }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                CommunityScopeChoice(state.listingsScope, onScopeChange, enabled = signedIn)
            }
            state.operationError?.let { message -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { ErrorBar(message, PageMood.COMMERCE) { viewModel.clearOperationError() } } }
            wishToRevisit(visibleWishes, userId)?.let { memory ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    WishRevisitCard(memory) { selected = memory; viewModel.openWishComments(memory.id) }
                }
            }
            when (val listings = state.listings) {
                UiState.Loading -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    MarketEmptyState(
                        refreshing = true,
                        hasSynced = false,
                        syncError = null,
                        onPublish = { composing = true },
                        onRetry = viewModel::refreshListings,
                    )
                }
                UiState.Empty -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    if (state.listingsHasSynced && !state.listingsRefreshing && state.listingsSyncError == null) {
                        EmptyRemote(
                            title = if (state.listingsScope == CommunityFeedScope.MINE) "给自己留一个心愿" else "还没有公开的心愿",
                            detail = if (state.listingsScope == CommunityFeedScope.MINE) "你记下的心愿和实现后的留念，都会在这里。" else "审核通过的公开心愿，会在这里相遇。",
                            action = "记下心愿",
                            mood = PageMood.COMMERCE,
                        ) { composing = true }
                    } else {
                    MarketEmptyState(
                        refreshing = state.listingsRefreshing,
                        hasSynced = state.listingsHasSynced,
                        syncError = state.listingsSyncError,
                        onPublish = { composing = true },
                        onRetry = viewModel::refreshListings,
                    )
                    }
                }
                is UiState.Error -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { RemoteError(listings.message, signedIn, onLogin, viewModel::refreshListings, PageMood.COMMERCE) }
                is UiState.Data -> items(listings.value, key = { it.id }, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(if (it.mediaUrl.isBlank()) maxLineSpan else 1) }) { listing -> ListingCardView(listing) { selected = listing; viewModel.openWishComments(listing.id) } }
                is UiState.Offline -> {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        SpectraStatePane(
                            kind = SpectraStateKind.OFFLINE,
                            title = "显示上次同步的心愿卡",
                            detail = "内容可能已变化；恢复网络后再联系发布者。",
                            modifier = Modifier.fillMaxWidth(),
                            actionLabel = "重新读取",
                            onAction = viewModel::refreshListings,
                        )
                    }
                    items(listings.value, key = { it.id }, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(if (it.mediaUrl.isBlank()) maxLineSpan else 1) }) { listing -> ListingCardView(listing) { selected = listing; viewModel.openWishComments(listing.id) } }
                }
            }
            }
            GlassPanel(
                onClick = { if (signedIn) composing = true else onLogin() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = layout.pageHorizontalPadding, bottom = pageBottom + layout.compactGap),
                radius = 32,
                emphasized = true,
                opticalPriority = 2,
            ) {
                Row(Modifier.padding(horizontal = 20.dp).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Add, null)
                    Text("记下心愿", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    if (composing) ListingComposer(
        busy = state.operationBusy,
        onClose = { composing = false },
        error = state.operationError,
        onPublish = { title, description, cents, location, image, isPublic, _, date -> viewModel.publishListing(userId, title, description, cents, location, image, isPublic, date) { composing = false } },
    )
    editing?.let { wish -> ListingComposer(
        busy = state.operationBusy, initial = wish, error = state.operationError, onClose = { editing = null },
        onPublish = { title, description, cents, location, image, isPublic, removeImage, date ->
            viewModel.updateListing(wish, title, description, cents, location, image, removeImage, date, isPublic) { editing = null }
        },
    ) }
    managing?.let { wish -> OwnedContentDialog(
        title = "管理心愿", busy = state.operationBusy, error = state.operationError, onClose = { managing = null },
        onEdit = { managing = null; selected = null; editing = wish; viewModel.clearOperationError() },
        onDelete = { viewModel.deleteListing(wish.id) { managing = null; selected = null } },
    ) }
    completing?.let { wish -> WishCompletionDialog(wish, state.operationBusy, state.operationError,
        onClose = { completing = null }, onSave = { note, image -> viewModel.completeWish(wish, note, image) { completing = null } }) }
    selected?.let { listing ->
        ListingDetails(
            listing = visibleWishes.firstOrNull { it.id == listing.id } ?: listing,
            ownListing = listing.sellerId == userId,
            onClose = { selected = null; viewModel.closeWishComments() },
            comments = state.wishComments,
            busy = state.operationBusy, error = state.operationError,
            onRetry = { viewModel.openWishComments(listing.id) },
            onComment = { body, success -> viewModel.publishWishComment(listing.id, body, success) },
            onManage = { managing = visibleWishes.firstOrNull { it.id == listing.id } ?: listing; viewModel.clearOperationError() },
            onComplete = { completing = visibleWishes.firstOrNull { it.id == listing.id } ?: listing; selected = null; viewModel.clearOperationError() },
            onFavorite = { viewModel.toggleFavorite(listing.id) },
            onContact = {
                viewModel.openConversation(listing.sellerId, listing.id) { conversationId ->
                    selected = null
                    onOpenConversation(conversationId)
                }
            },
        )
    }
}

@Composable
internal fun ListingCardView(listing: MarketplaceListing, onClick: () -> Unit) {
    val hasImage = listing.mediaUrl.isNotBlank()
    GlassPanel(Modifier.fillMaxWidth().then(if (hasImage) Modifier.aspectRatio(1f) else Modifier), onClick = onClick) {
        if (hasImage) {
            AsyncImage(listing.mediaUrl, "心愿图片", Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(.96f)))))
        }
        Column(Modifier.then(if (hasImage) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(if (hasImage) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasImage) Spacer(Modifier.weight(1f))
            Text(listing.title, maxLines = if (hasImage) 2 else 1, overflow = TextOverflow.Ellipsis,
                style = if (hasImage) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
            if (!hasImage && listing.description.isNotBlank()) Text(listing.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.76f))
            if (listing.completedAt.isNotBlank()) Text("已实现 · 把这一刻留下", style = MaterialTheme.typography.labelMedium)
            else if (!hasImage) wishDateMessage(listing.targetDate)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            Text(wishRecordedTime(listing.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
        }
    }
}

@Composable
internal fun PostComposer(busy: Boolean, onClose: () -> Unit, onPublish: (String, String, Boolean, UploadImage?, Boolean, Boolean) -> Unit, initial: CommunityPost? = null, error: String? = null) {
    var body by rememberSaveable(initial?.id) { mutableStateOf(initial?.body.orEmpty()) }
    var topic by rememberSaveable(initial?.id) { mutableStateOf(initial?.topic.orEmpty()) }
    var anonymous by rememberSaveable(initial?.id) { mutableStateOf(initial?.anonymous ?: false) }
    var isPublic by rememberSaveable(initial?.id) { mutableStateOf(initial?.isPublic ?: false) }
    var removeImage by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> imageUri = uri; mediaError = null }
    ComposerDialog(
        title = if (initial == null) "写进树洞" else "修改树洞",
        subtitle = "一句真话就够了。完成后点击保存，未保存的修改不会提交。",
        editing = initial != null,
        progress = "${body.length} / 5000",
        primaryText = if (busy || preparing) "正在保存" else "保存",
        primaryEnabled = body.isNotBlank() && !busy && !preparing,
        onClose = { if (!busy && !preparing) onClose() },
        mood = PageMood.SOCIAL,
        onPrimary = {
            preparing = true
            scope.launch {
                try {
                val image = imageUri?.let { uri -> runCatching { readUploadImage(context, uri) }.getOrElse { mediaError = it.message ?: "图片无法读取。"; return@launch } }
                onPublish(body, topic, anonymous, image, isPublic, removeImage)
                } finally { preparing = false }
            }
        },
    ) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        VisibilityChoice(isPublic, { isPublic = it }, enabled = !busy && !preparing)
        ComposerSection("01", "此刻想说的", "先把句子写完，排版和话题可以稍后再想。", PageMood.SOCIAL) {
            SpectraTextField(
                value = body,
                onValueChange = { body = it.take(5000) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp),
                placeholder = { Text("今天有什么想被记住？") },
                shape = RoundedCornerShape(18.dp),
            )
        }
        ComposerSection("02", "给它一个线索", "话题可选，日后回看时更容易找到。", PageMood.SOCIAL) {
            SpectraTextField(
                value = topic,
                onValueChange = { topic = it.take(40) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("话题") },
                placeholder = { Text("例如：今夜、灵感、想说") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                supportingText = { Text("${topic.length} / 40") },
            )
        }
        ComposerSection("03", "谁在说话", "这是一个可滑动的选择；匿名内容不会向其他人展示你的名字。", PageMood.SOCIAL) {
            com.campusai.core.designsystem.CaesarSlidingSelector(
                options = listOf("以本人发布", "匿名发布"),
                selectedIndex = if (anonymous) 1 else 0,
                onSelected = { anonymous = it == 1 },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ComposerSection("04", "加一张画面", "不加图也可以发布。", PageMood.SOCIAL) {
            if (imageUri == null && !removeImage && initial?.mediaUrl?.isNotBlank() == true) {
                AsyncImage(initial.mediaUrl, "原来的树洞图片", Modifier.fillMaxWidth().height(140.dp), contentScale = ContentScale.Crop)
            }
            MediaPickerSlot(
                uri = imageUri,
                emptyLabel = "从相册选一张图",
                contentDescription = "待发布的树洞图片",
                mood = PageMood.SOCIAL,
                onClick = { picker.launch("image/*") },
            )
            mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (imageUri != null || (!removeImage && initial?.mediaPaths?.isNotEmpty() == true)) TextButton(onClick = { imageUri = null; removeImage = true }) { Text("移除图片") }
        }
    }
}

@Composable
internal fun ListingComposer(busy: Boolean, onClose: () -> Unit, onPublish: (String, String, Int?, String, UploadImage?, Boolean, Boolean, String) -> Unit, initial: MarketplaceListing? = null, error: String? = null) {
    var title by rememberSaveable(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var description by rememberSaveable(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var price by rememberSaveable(initial?.id) { mutableStateOf(initial?.priceCents?.toBigDecimal()?.movePointLeft(2)?.toPlainString().orEmpty()) }
    var location by rememberSaveable(initial?.id) { mutableStateOf(initial?.location.orEmpty()) }
    var isPublic by rememberSaveable(initial?.id) { mutableStateOf(initial?.isPublic ?: false) }
    var removeImage by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var targetDate by rememberSaveable(initial?.id) { mutableStateOf(initial?.targetDate.orEmpty()) }
    var preparing by remember { mutableStateOf(false) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> imageUri = uri; mediaError = null }
    val cents = wishPriceCents(price)
    ComposerDialog(
        title = if (initial == null) "贴一张心愿" else "修改心愿",
        subtitle = "想做的事、想遇见的人，都可以成为心愿。价格和公开分享由你决定。",
        editing = initial != null,
        progress = if (title.isBlank()) "草稿" else "可以保存",
        primaryText = if (busy || preparing) "正在保存" else "保存",
        primaryEnabled = title.isNotBlank() && wishPriceValid(price) && !busy && !preparing,
        onClose = { if (!busy && !preparing) onClose() },
        mood = PageMood.COMMERCE,
        onPrimary = {
            preparing = true
            scope.launch {
                try {
                val image = imageUri?.let { uri -> runCatching { readUploadImage(context, uri) }.getOrElse { mediaError = it.message ?: "图片无法读取。"; return@launch } }
                onPublish(title, description, cents, location, image, isPublic, removeImage, targetDate)
                } finally { preparing = false }
            }
        },
    ) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        VisibilityChoice(isPublic, { isPublic = it }, enabled = !busy && !preparing)
        WishDateChoice(targetDate, { targetDate = it }, enabled = !busy && !preparing)
        ComposerSection("01", "这是什么", "先给心愿一个一眼能懂的名字。", PageMood.COMMERCE) {
            SpectraTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("心愿标题") }, singleLine = true, shape = RoundedCornerShape(18.dp))
            SpectraTextField(description, { description = it.take(2000) }, Modifier.fillMaxWidth().heightIn(min = 132.dp), label = { Text("细节（可选）") }, placeholder = { Text("为什么想实现它、期待怎样的回应…") }, shape = RoundedCornerShape(18.dp))
        }
        ComposerSection("02", "期待的条件", "不涉及金钱就留空；填写 0 元表示明确免费。地点也可留空。", PageMood.COMMERCE) {
            SpectraTextField(price, { price = it.filter { char -> char.isDigit() || char == '.' }.take(10) }, Modifier.fillMaxWidth(), label = { Text("期待价格（元，可选）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, shape = RoundedCornerShape(18.dp), isError = !wishPriceValid(price), supportingText = { Text(if (wishPriceValid(price)) "可以留空" else "请输入有效金额，最多两位小数") })
            SpectraTextField(location, { location = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("碰面地点（可选）") }, singleLine = true, shape = RoundedCornerShape(18.dp))
        }
        ComposerSection("03", "让它被看见", "清楚的图片会让回应更准确。", PageMood.COMMERCE) {
            if (imageUri == null && !removeImage && initial?.mediaUrl?.isNotBlank() == true) {
                AsyncImage(initial.mediaUrl, "原来的心愿图片", Modifier.fillMaxWidth().height(140.dp), contentScale = ContentScale.Crop)
            }
            MediaPickerSlot(
                uri = imageUri,
                emptyLabel = "选一张代表它的图",
                contentDescription = "待发布的心愿图片",
                mood = PageMood.COMMERCE,
                onClick = { picker.launch("image/*") },
            )
            mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (imageUri != null || (!removeImage && initial?.mediaPaths?.isNotEmpty() == true)) TextButton(onClick = { imageUri = null; removeImage = true }) { Text("移除图片") }
        }
    }
}

@Composable
internal fun VisibilityChoice(isPublic: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    SpectraSurface(modifier = Modifier.fillMaxWidth(), emphasized = true) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
                .toggleable(value = isPublic, enabled = enabled, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("分享给其他人", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isPublic) "审核通过后，其他人可以看到" else "想让别人看见时，再打开它",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(.68f),
                )
            }
            Switch(checked = isPublic, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun ComposerDialog(
    title: String,
    subtitle: String,
    progress: String,
    editing: Boolean,
    primaryText: String,
    primaryEnabled: Boolean,
    onClose: () -> Unit,
    mood: PageMood,
    onPrimary: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SpectraFullScreenDialog(onDismissRequest = onClose, mood = mood) {
        Box(
            Modifier
                .fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SpectraIconAction(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "返回", onClick = onClose)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium)
                        Text(if (editing) "修改后保存生效" else "尚未保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.52f))
                    }
                    Text(progress, style = MaterialTheme.typography.labelSmall)
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(.66f))
                    content()
                    Spacer(Modifier.height(8.dp))
                }
                    SpectraSurface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        mood = mood,
                        emphasized = true,
                        contentPadding = PaddingValues(10.dp),
                    ) {
                        SpectraPrimaryButton(primaryText, onPrimary, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = primaryEnabled)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerSection(
    number: String,
    title: String,
    detail: String,
    mood: PageMood,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(3.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.54f))
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.09f))
    }
}

@Composable
private fun MediaPickerSlot(
    uri: Uri?,
    emptyLabel: String,
    contentDescription: String,
    mood: PageMood,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        radius = 22,
        emphasized = uri != null,
        shadowed = false,
    ) {
        Box(
            Modifier.fillMaxWidth().height(176.dp).background(MaterialTheme.colorScheme.onSurface.copy(.035f)),
            contentAlignment = Alignment.Center,
        ) {
            if (uri != null) {
                AsyncImage(uri, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                SpectraStatus("点击更换", modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), tone = SpectraStatusTone.INFO)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.onSurface.copy(.08f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSurface.copy(.68f))
                    }
                    Text(emptyLabel, style = MaterialTheme.typography.labelLarge)
                    Text("JPG · PNG · WEBP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.46f))
                }
            }
        }
    }
}

@Composable
private fun FullScreenDialog(
    title: String,
    onClose: () -> Unit,
    mood: PageMood,
    content: @Composable ColumnScope.() -> Unit,
) {
    SpectraFullScreenDialog(onDismissRequest = onClose, mood = mood) {
        Box(Modifier.fillMaxSize()) {
            SpectraPageScaffold(mood = mood) {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SpectraIconAction(
                                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                label = "返回",
                                onClick = onClose,
                            )
                            Text(title, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                        item {
                            SpectraSurface(
                                modifier = Modifier.fillMaxWidth(),
                                mood = mood,
                                emphasized = true,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketEmptyState(
    refreshing: Boolean,
    hasSynced: Boolean,
    syncError: String?,
    onPublish: () -> Unit,
    onRetry: () -> Unit,
) {
    val presentation = marketEmptyPresentation(
        refreshing = refreshing,
        hasSynced = hasSynced,
        hasError = syncError != null,
    )
    val tone = when {
        refreshing -> SpectraStatusTone.INFO
        syncError != null -> SpectraStatusTone.STALE
        else -> SpectraStatusTone.NEUTRAL
    }
    SpectraSurface(
        modifier = Modifier.fillMaxWidth(),
        mood = PageMood.COMMERCE,
        emphasized = true,
    ) {
        SpectraStatus(presentation.status, tone = tone)
        Text(presentation.title, style = MaterialTheme.typography.titleLarge)
        Text(presentation.detail, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
        SpectraAction(
            text = "贴一张心愿",
            onClick = onPublish,
            modifier = Modifier.fillMaxWidth(),
            emphasized = true,
            mood = PageMood.COMMERCE,
        )
        if (syncError != null || (!refreshing && !hasSynced)) {
            SpectraAction(
                text = if (syncError != null) "重新读取" else "读取目录",
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                mood = PageMood.COMMERCE,
                icon = Icons.Rounded.Refresh,
            )
        }
    }
}

internal data class MarketEmptyPresentation(
    val status: String,
    val title: String,
    val detail: String,
)

internal fun marketEmptyPresentation(
    refreshing: Boolean,
    hasSynced: Boolean,
    hasError: Boolean,
): MarketEmptyPresentation = when {
    refreshing && !hasSynced -> MarketEmptyPresentation(
        status = "正在确认",
        title = "正在确认心愿墙",
        detail = "本地暂时没有可显示的心愿卡；正在后台获取最新结果。",
    )
    refreshing -> MarketEmptyPresentation(
        status = "正在更新",
        title = "上次同步时心愿墙是空的",
        detail = "正在后台确认最新内容；你仍可直接贴上一张心愿。",
    )
    hasError && !hasSynced -> MarketEmptyPresentation(
        status = "同步未完成",
        title = "暂时无法确认心愿墙",
        detail = "尚未取得远端结果；当前空白不代表没有心愿卡。",
    )
    hasError -> MarketEmptyPresentation(
        status = "刷新未完成",
        title = "上次同步时心愿墙是空的",
        detail = "本次刷新失败，目录可能已经变化；网络恢复后可重试。",
    )
    hasSynced -> MarketEmptyPresentation(
        status = "暂无内容",
        title = "心愿墙还很安静",
        detail = "本次同步结果为空；新心愿提交后会先进入审核。",
    )
    else -> MarketEmptyPresentation(
        status = "暂无内容",
        title = "暂时没有显示心愿",
        detail = "正在等待首次同步；你也可以直接贴上一张心愿。",
    )
}

@Composable
private fun EmptyRemote(
    title: String,
    detail: String,
    action: String,
    mood: PageMood,
    onAction: () -> Unit,
) {
    SpectraSurface(
        modifier = Modifier.fillMaxWidth(),
        mood = mood,
        emphasized = true,
    ) {
        SpectraStatus("暂无内容", tone = SpectraStatusTone.NEUTRAL)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
        SpectraAction(
            text = action,
            onClick = onAction,
            modifier = Modifier.fillMaxWidth(),
            emphasized = true,
            mood = mood,
        )
    }
}

@Composable
private fun RemoteError(
    message: String,
    signedIn: Boolean,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
    mood: PageMood,
) {
    SpectraSurface(Modifier.fillMaxWidth(), mood = mood) {
        SpectraStatus("需要处理", tone = SpectraStatusTone.ERROR)
        Text(message, style = MaterialTheme.typography.titleMedium)
        Text(
            if (signedIn) "请检查网络与权限后重试。" else "当前未登录；本地时间和课程表仍可使用。",
            color = MaterialTheme.colorScheme.onSurface.copy(.6f),
        )
        SpectraAction(
            text = if (signedIn) "重新读取" else "安全登录",
            onClick = if (signedIn) onRetry else onLogin,
            mood = mood,
        )
    }
}

@Composable
private fun ErrorBar(message: String, mood: PageMood, onDismiss: () -> Unit) {
    SpectraSurface(
        modifier = Modifier.fillMaxWidth(),
        mood = mood,
        shadowed = false,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            SpectraIconAction(
                icon = Icons.Rounded.Close,
                label = "关闭错误",
                onClick = onDismiss,
            )
        }
    }
}

private fun remoteTime(value: String): String = value.take(16).replace('T', ' ').ifBlank { "刚刚" }
private fun moderationText(value: String): String = when (value) { "pending" -> "待审核"; "approved" -> "已通过"; "rejected" -> "未通过"; else -> value }

internal suspend fun readUploadImage(context: Context, uri: Uri): UploadImage = withContext(Dispatchers.IO) {
    val contentType = context.contentResolver.getType(uri).orEmpty().lowercase()
    require(contentType in setOf("image/jpeg", "image/png", "image/webp")) { "只支持 JPEG、PNG 或 WebP 图片。" }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("图片无法读取。")
    require(bytes.isNotEmpty()) { "图片内容为空。" }
    require(bytes.size <= 15 * 1024 * 1024) { "图片不能超过 15MB。" }
    UploadImage(bytes, contentType)
}
