package com.campusai.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.campusai.core.designsystem.SpectraTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.campusai.core.designsystem.BrandMark
import com.campusai.core.designsystem.PageMood
import com.campusai.core.designsystem.SlideConfirm
import com.campusai.core.designsystem.SpectraAction
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraIconAction
import com.campusai.core.designsystem.SpectraModalBottomSheet
import com.campusai.core.designsystem.SpectraPageScaffold
import com.campusai.core.designsystem.SpectraStateKind
import com.campusai.core.designsystem.SpectraStatePane
import com.campusai.core.designsystem.SpectraStatus
import com.campusai.core.designsystem.SpectraStatusTone
import com.campusai.core.designsystem.SpectraSurface
import com.campusai.core.designsystem.SpectraTheme
import com.campusai.core.designsystem.Tomorrow
import com.campusai.core.model.UiState
import com.campusai.features.community.CampusMessage
import com.campusai.features.community.CampusRemoteState
import com.campusai.features.community.CampusViewModel
import com.campusai.features.community.ConversationSummary
import com.campusai.features.community.MarketplaceOrder
import com.campusai.features.community.allowedOrderTransitions

@Composable
fun MessageCenterScreen(
    state: CampusRemoteState,
    userId: String,
    viewModel: CampusViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.refreshConversations() }
    val activeId = state.activeConversationId
    if (activeId != null) {
        val summary = conversationValues(state.conversations).firstOrNull { it.id == activeId }
        MessageThreadScreen(state, userId, summary, viewModel)
        return
    }

    OverlayPage(PageMood.COMMERCE) {
        PageHeader("消息", "围绕心愿卡的每次沟通，都清楚而可追溯", onBack)
        state.operationError?.let { message -> item { InlineOperationError(message, PageMood.COMMERCE, viewModel::clearOperationError) } }
        when (val conversations = state.conversations) {
            UiState.Loading -> item {
                SpectraStatePane(
                    kind = SpectraStateKind.LOADING,
                    title = "正在读取会话",
                    detail = "将同步最新消息和未读数。",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            UiState.Empty -> item {
                CommerceEmpty(
                    title = "还没有消息",
                    detail = "从心愿卡联系发布者后，会话会出现在这里。",
                    actionLabel = "重新检查",
                    onAction = viewModel::refreshConversations,
                )
            }
            is UiState.Error -> item { CommerceError(conversations.message, viewModel::refreshConversations) }
            is UiState.Data -> items(conversations.value, key = { it.id }) { summary -> ConversationRow(summary) { viewModel.openMessageThread(summary.id) } }
            is UiState.Offline -> {
                item {
                    SpectraStatePane(
                        kind = SpectraStateKind.OFFLINE,
                        title = "显示上次同步的会话",
                        detail = "未读数和最新消息可能已变化。",
                        modifier = Modifier.fillMaxWidth(),
                        actionLabel = "重新读取",
                        onAction = viewModel::refreshConversations,
                    )
                }
                items(conversations.value, key = { it.id }) { summary -> ConversationRow(summary) { viewModel.openMessageThread(summary.id) } }
            }
        }
    }
}

@Composable
private fun ConversationRow(summary: ConversationSummary, onClick: () -> Unit) {
    SpectraSurface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        mood = PageMood.COMMERCE,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).background(Brush.linearGradient(listOf(SpectraColors.Warm, SpectraColors.Rose, SpectraColors.Violet)), CircleShape),
                contentAlignment = Alignment.Center,
            ) { BrandMark(Modifier.size(32.dp), Color.White) }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(summary.otherName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(commerceTime(summary.lastMessageAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.5f))
                }
                if (summary.listingTitle.isNotBlank()) Text(summary.listingTitle, style = MaterialTheme.typography.labelMedium, color = SpectraColors.Focus, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary.lastMessage.ifBlank { "开始这段对话" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(.62f))
            }
            if (summary.unreadCount > 0) {
                Spacer(Modifier.size(8.dp))
                Box(Modifier.size(24.dp).background(SpectraColors.Rose, CircleShape), contentAlignment = Alignment.Center) {
                    Text(summary.unreadCount.coerceAtMost(99).toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MessageThreadScreen(
    state: CampusRemoteState,
    userId: String,
    summary: ConversationSummary?,
    viewModel: CampusViewModel,
) {
    val conversationId = state.activeConversationId ?: return
    var draft by rememberSaveable(conversationId) { mutableStateOf("") }
    val messages = messageValues(state.messages)
    val listState = rememberLazyListState()
    val layout = SpectraTheme.layout
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    SpectraPageScaffold(mood = PageMood.COMMERCE) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = layout.pageHorizontalPadding, vertical = layout.compactGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpectraIconAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                label = "返回消息列表",
                onClick = viewModel::closeMessageThread,
            )
            Column(Modifier.weight(1f)) {
                Text(summary?.otherName ?: "心愿会话", style = MaterialTheme.typography.titleLarge)
                if (!summary?.listingTitle.isNullOrBlank()) Text(summary?.listingTitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = SpectraColors.Focus)
            }
            SpectraIconAction(
                icon = Icons.Rounded.Refresh,
                label = "刷新消息",
                onClick = { viewModel.openMessageThread(conversationId) },
            )
        }
        state.operationError?.let { Box(Modifier.padding(horizontal = layout.pageHorizontalPadding)) { InlineOperationError(it, PageMood.COMMERCE, viewModel::clearOperationError) } }
        when (val remote = state.messages) {
            UiState.Loading -> Box(Modifier.weight(1f).padding(horizontal = layout.pageHorizontalPadding, vertical = layout.pageTopSpacing)) {
                SpectraStatePane(
                    kind = SpectraStateKind.LOADING,
                    title = "正在读取消息",
                    detail = "草稿仍保留在本机。",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            UiState.Empty -> Box(
                Modifier.weight(1f).padding(horizontal = layout.pageHorizontalPadding, vertical = layout.pageTopSpacing),
                contentAlignment = Alignment.Center,
            ) {
                CommerceEmpty(
                    title = "还没有消息",
                    detail = "这段会话尚无内容；你可以在下方发一句清楚的开场白。",
                    actionLabel = "重新检查",
                    onAction = { viewModel.openMessageThread(conversationId) },
                )
            }
            is UiState.Error -> Box(Modifier.weight(1f).padding(horizontal = layout.pageHorizontalPadding, vertical = layout.pageTopSpacing)) { CommerceError(remote.message) { viewModel.openMessageThread(conversationId) } }
            is UiState.Data, is UiState.Offline -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = layout.pageHorizontalPadding, vertical = layout.compactGap),
                verticalArrangement = Arrangement.spacedBy(layout.compactGap),
            ) {
                if (remote is UiState.Offline) {
                    item {
                        SpectraStatePane(
                            kind = SpectraStateKind.OFFLINE,
                            title = "显示上次同步的消息",
                            detail = "恢复网络后再发送新消息。",
                            modifier = Modifier.fillMaxWidth(),
                            actionLabel = "重新读取",
                            onAction = { viewModel.openMessageThread(conversationId) },
                        )
                    }
                }
                items(messages, key = { it.id }) { message -> MessageBubble(message, message.senderId == userId) }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(.12f))
                .padding(horizontal = layout.pageHorizontalPadding, vertical = layout.compactGap),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(layout.compactGap),
        ) {
            SpectraTextField(
                value = draft,
                onValueChange = { draft = it.take(4000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                shape = RoundedCornerShape(20.dp),
                maxLines = 4,
            )
            IconButton(
                onClick = { viewModel.sendMessage(conversationId, draft) { draft = "" } },
                enabled = draft.isNotBlank() && !state.operationBusy,
                modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            ) { Icon(Icons.AutoMirrored.Rounded.Send, "发送", tint = MaterialTheme.colorScheme.onPrimary) }
        }
        }
    }
}

@Composable
private fun MessageBubble(message: CampusMessage, own: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        SpectraSurface(
            modifier = Modifier.widthIn(max = 310.dp),
            mood = PageMood.COMMERCE,
            shadowed = false,
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(
                Modifier
                    .background(
                        if (own) Brush.linearGradient(listOf(SpectraColors.Focus.copy(.16f), SpectraColors.Violet.copy(.14f)))
                        else Brush.linearGradient(listOf(SpectraColors.Warm.copy(.16f), SpectraColors.Rose.copy(.12f))),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(message.body, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(commerceTime(message.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.48f))
            }
        }
    }
}

@Composable
fun OrdersScreen(
    state: CampusRemoteState,
    userId: String,
    viewModel: CampusViewModel,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    var pending by remember { mutableStateOf<Pair<MarketplaceOrder, OrderAction>?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshOrders() }
    OverlayPage(PageMood.COMMERCE) {
        PageHeader("订单", "交易状态由双方确认，关键变化会留下审计记录", onBack)
        state.operationError?.let { message -> item { InlineOperationError(message, PageMood.COMMERCE, viewModel::clearOperationError) } }
        when (val orders = state.orders) {
            UiState.Loading -> item {
                SpectraStatePane(
                    kind = SpectraStateKind.LOADING,
                    title = "正在读取订单",
                    detail = "交易状态和版本号会一起更新。",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            UiState.Empty -> item {
                CommerceEmpty(
                    title = "还没有订单",
                    detail = "确认购买商品后，订单进度会出现在这里。",
                    actionLabel = "重新检查",
                    onAction = viewModel::refreshOrders,
                )
            }
            is UiState.Error -> item { CommerceError(orders.message, viewModel::refreshOrders) }
            is UiState.Data -> items(orders.value, key = { it.id }) { order ->
                OrderCard(
                    order = order,
                    userId = userId,
                    onContact = {
                        val other = if (userId == order.buyerId) order.sellerId else order.buyerId
                        viewModel.openConversation(other, order.listingId, onOpenConversation)
                    },
                    onAction = { pending = order to it },
                )
            }
            is UiState.Offline -> {
                item {
                    SpectraStatePane(
                        kind = SpectraStateKind.OFFLINE,
                        title = "显示上次同步的订单",
                        detail = "状态可能已变化；离线时不能推进订单。",
                        modifier = Modifier.fillMaxWidth(),
                        actionLabel = "重新读取",
                        onAction = viewModel::refreshOrders,
                    )
                }
                items(orders.value, key = { it.id }) { order ->
                    OrderCard(order, userId, onContact = {}, onAction = {}, actionsEnabled = false)
                }
            }
        }
    }

    pending?.let { (order, action) ->
        SpectraModalBottomSheet(onDismissRequest = { if (!state.operationBusy) pending = null }) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
                SpectraSurface(
                    modifier = Modifier.fillMaxWidth(),
                    mood = PageMood.COMMERCE,
                    emphasized = true,
                ) {
                    Text(action.label, style = MaterialTheme.typography.titleLarge)
                    Text(action.detail, color = MaterialTheme.colorScheme.onSurface.copy(.64f))
                    SlideConfirm(
                        text = if (state.operationBusy) "正在确认…" else "滑动确认",
                        enabled = !state.operationBusy,
                        onConfirm = {
                            viewModel.transitionOrder(order.id, order.version, action.nextStatus) { pending = null }
                        },
                    )
                    SpectraAction(
                        text = "暂不操作",
                        onClick = { pending = null },
                        modifier = Modifier.fillMaxWidth(),
                        mood = PageMood.COMMERCE,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderCard(
    order: MarketplaceOrder,
    userId: String,
    onContact: () -> Unit,
    onAction: (OrderAction) -> Unit,
    actionsEnabled: Boolean = true,
) {
    val isBuyer = userId == order.buyerId
    val counterpart = if (isBuyer) order.sellerName else order.buyerName
    val actions = orderActions(order, isBuyer)
    SpectraSurface(Modifier.fillMaxWidth(), mood = PageMood.COMMERCE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(SpectraColors.Warm.copy(.24f), SpectraColors.Rose.copy(.12f)))), contentAlignment = Alignment.Center) {
                    if (order.listingMediaUrl.isNotBlank()) AsyncImage(order.listingMediaUrl, "商品图片", Modifier.fillMaxSize().aspectRatio(1f))
                    else BrandMark(Modifier.size(42.dp))
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(order.listingTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("¥${"%.2f".format(order.priceCents / 100.0)}", fontFamily = Tomorrow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                    Text("${if (isBuyer) "卖家" else "买家"} · $counterpart", color = MaterialTheme.colorScheme.onSurface.copy(.58f))
                }
                SpectraStatus(
                    text = orderStatusText(order.status),
                    tone = when (order.status) {
                        "completed" -> SpectraStatusTone.SUCCESS
                        "cancelled", "disputed" -> SpectraStatusTone.WARNING
                        else -> SpectraStatusTone.INFO
                    },
                )
            }
            OrderProgress(order.status)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectraAction(
                    text = "联系对方",
                    onClick = onContact,
                    modifier = Modifier.weight(1f),
                    enabled = actionsEnabled,
                    mood = PageMood.COMMERCE,
                    icon = Icons.AutoMirrored.Rounded.Chat,
                )
                actions.firstOrNull()?.let { action ->
                    SpectraAction(
                        text = action.label,
                        onClick = { onAction(action) },
                        modifier = Modifier.weight(1f),
                        emphasized = true,
                        enabled = actionsEnabled,
                        mood = PageMood.COMMERCE,
                    )
                }
            }
            if (actions.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.drop(1).forEach { action ->
                        SpectraAction(
                            text = action.label,
                            onClick = { onAction(action) },
                            modifier = Modifier.weight(1f),
                            enabled = actionsEnabled,
                            mood = PageMood.COMMERCE,
                        )
                    }
                }
            }
    }
}

@Composable
private fun OrderProgress(status: String) {
    val steps = listOf("pending_payment", "paid", "meeting", "completed")
    val activeIndex = steps.indexOf(status).coerceAtLeast(0)
    val motion = SpectraTheme.tokens.motion
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, step ->
            val active = status !in listOf("cancelled", "disputed") && index <= activeIndex
            val dotColor by animateColorAsState(
                targetValue = if (active) SpectraColors.Warm else SpectraColors.Silver,
                animationSpec = tween(motion.resolve(motion.shortMillis)),
                label = "order-step-$step",
            )
            Box(Modifier.size(16.dp).background(dotColor, CircleShape))
            if (index < steps.lastIndex) {
                val railColor by animateColorAsState(
                    targetValue = if (active && index < activeIndex) SpectraColors.Warm else SpectraColors.Silver.copy(.7f),
                    animationSpec = tween(motion.resolve(motion.shortMillis)),
                    label = "order-rail-$step",
                )
                Spacer(Modifier.weight(1f).height(3.dp).background(railColor, CircleShape))
            }
        }
    }
}

private data class OrderAction(val label: String, val nextStatus: String, val detail: String)

private fun orderActions(order: MarketplaceOrder, isBuyer: Boolean): List<OrderAction> =
    allowedOrderTransitions(order.status, isBuyer).map { nextStatus ->
        when (nextStatus) {
            "paid" -> OrderAction("确认付款", nextStatus, "仅在已经完成付款后确认；该操作会推进订单状态。")
            "cancelled" -> OrderAction("取消订单", nextStatus, "取消后商品将重新回到在售状态。")
            "meeting" -> OrderAction("进入面交", nextStatus, "确认已经与买家约定并开始线下交付。")
            "completed" -> OrderAction("确认完成", nextStatus, "确认收到商品后，订单将完成且商品标记为已售。")
            else -> OrderAction("发起争议", "disputed", "订单会暂停流转，等待管理员处理。")
        }
    }

@Composable
private fun OverlayPage(
    mood: PageMood,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val layout = SpectraTheme.layout
    SpectraPageScaffold(mood = mood) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = layout.pageHorizontalPadding,
                end = layout.pageHorizontalPadding,
                top = layout.pageTopSpacing,
                bottom = layout.pageTopSpacing + layout.compactGap,
            ),
            verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
            content = content,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.PageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SpectraIconAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                label = "返回",
                onClick = onBack,
            )
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineLarge); Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(.58f)) }
        }
    }
}

@Composable
private fun CommerceEmpty(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    SpectraSurface(
        modifier = Modifier.fillMaxWidth(),
        mood = PageMood.COMMERCE,
        emphasized = true,
    ) {
        SpectraStatus("暂无内容", tone = SpectraStatusTone.NEUTRAL)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
        SpectraAction(
            text = actionLabel,
            onClick = onAction,
            modifier = Modifier.fillMaxWidth(),
            emphasized = true,
            mood = PageMood.COMMERCE,
        )
    }
}

@Composable
private fun CommerceError(message: String, onRetry: () -> Unit) {
    SpectraSurface(Modifier.fillMaxWidth(), mood = PageMood.COMMERCE) {
        SpectraStatus("需要处理", tone = SpectraStatusTone.ERROR)
        Text(message, color = MaterialTheme.colorScheme.error)
        SpectraAction(
            text = "重新读取",
            onClick = onRetry,
            mood = PageMood.COMMERCE,
        )
    }
}

@Composable
private fun InlineOperationError(message: String, mood: PageMood, onDismiss: () -> Unit) {
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

private fun conversationValues(state: UiState<List<ConversationSummary>>): List<ConversationSummary> = when (state) {
    is UiState.Data -> state.value
    is UiState.Offline -> state.value
    else -> emptyList()
}

private fun messageValues(state: UiState<List<CampusMessage>>): List<CampusMessage> = when (state) {
    is UiState.Data -> state.value
    is UiState.Offline -> state.value
    else -> emptyList()
}

private fun commerceTime(value: String): String = value.take(16).replace('T', ' ').ifBlank { "刚刚" }
private fun orderStatusText(value: String): String = when (value) {
    "pending_payment" -> "待付款"
    "paid" -> "已付款"
    "meeting" -> "面交中"
    "completed" -> "已完成"
    "cancelled" -> "已取消"
    "disputed" -> "争议中"
    else -> value
}
