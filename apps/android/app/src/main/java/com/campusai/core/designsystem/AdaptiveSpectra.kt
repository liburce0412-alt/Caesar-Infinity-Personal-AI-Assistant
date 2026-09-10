package com.campusai.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Immutable
data class SpectraSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

@Immutable
data class SpectraRadii(
    val input: Dp = 12.dp,
    val card: Dp = 16.dp,
    val hero: Dp = 24.dp,
    val pill: Dp = 999.dp,
)

/** Finite motion timings only. An off policy resolves every duration to zero. */
@Immutable
data class SpectraMotion(
    val enabled: Boolean = true,
    val microMillis: Int = 120,
    val shortMillis: Int = 200,
    val longMillis: Int = 420,
) {
    fun resolve(durationMillis: Int): Int = if (enabled) durationMillis else 0

    fun disabled(): SpectraMotion = copy(
        enabled = false,
        microMillis = 0,
        shortMillis = 0,
        longMillis = 0,
    )
}

@Immutable
data class SpectraAlpha(
    val secondaryText: Float = .66f,
    val tertiaryText: Float = .48f,
    val subtleSurface: Float = .12f,
    val glass: Float = .17f,
    val emphasizedGlass: Float = .25f,
    val disabled: Float = .38f,
)

@Immutable
data class SpectraComponentSizes(
    val minimumTouchTarget: Dp = 48.dp,
    val iconAction: Dp = 48.dp,
    val primaryButton: Dp = 52.dp,
    val navigationDock: Dp = 64.dp,
    val icon: Dp = 20.dp,
)

@Immutable
data class SpectraTokens(
    val glassEffects: GlassEffects = GlassEffects(),
    val spacing: SpectraSpacing = SpectraSpacing(),
    val radii: SpectraRadii = SpectraRadii(),
    val motion: SpectraMotion = SpectraMotion(),
    val alpha: SpectraAlpha = SpectraAlpha(),
    val sizes: SpectraComponentSizes = SpectraComponentSizes(),
)

/**
 * Layout rhythm for the two complete Caesar interface systems.
 *
 * CLASSIC keeps the denser card-led composition. FLUID uses fewer, larger optical volumes,
 * wider vertical breathing room, and a lower floating dock. Keeping this in the shared design
 * layer makes the choice affect every page instead of being an AI-screen-only skin.
 */
@Immutable
data class SpectraLayoutTokens(
    val pageHorizontalPadding: Dp,
    val pageTopSpacing: Dp,
    val pageBottomSpacing: Dp,
    val sectionGap: Dp,
    val compactGap: Dp,
    val dockHorizontalPadding: Dp,
    val dockVerticalPadding: Dp,
    val dockHeight: Dp,
)

private val ClassicSpectraLayout = SpectraLayoutTokens(
    pageHorizontalPadding = 20.dp,
    pageTopSpacing = 12.dp,
    pageBottomSpacing = 104.dp,
    sectionGap = 14.dp,
    compactGap = 10.dp,
    dockHorizontalPadding = 16.dp,
    dockVerticalPadding = 10.dp,
    dockHeight = 64.dp,
)

private val FluidSpectraLayout = SpectraLayoutTokens(
    pageHorizontalPadding = 16.dp,
    pageTopSpacing = 20.dp,
    pageBottomSpacing = 112.dp,
    sectionGap = 22.dp,
    compactGap = 12.dp,
    dockHorizontalPadding = 28.dp,
    dockVerticalPadding = 12.dp,
    dockHeight = 58.dp,
)

val DefaultSpectraTokens = SpectraTokens()

val LocalSpectraTokens = staticCompositionLocalOf { DefaultSpectraTokens }
val LocalSpectraVisualStyle = staticCompositionLocalOf { SpectraVisualStyle.CLASSIC }
val LocalSpectraLayout = staticCompositionLocalOf { ClassicSpectraLayout }

object SpectraTheme {
    val tokens: SpectraTokens
        @Composable get() = LocalSpectraTokens.current
    val visualStyle: SpectraVisualStyle
        @Composable get() = LocalSpectraVisualStyle.current
    val layout: SpectraLayoutTokens
        @Composable get() = LocalSpectraLayout.current
    val isFluid: Boolean
        @Composable get() = visualStyle == SpectraVisualStyle.FLUID
}

@Composable
fun ProvideSpectraTokens(tokens: SpectraTokens, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSpectraTokens provides tokens, content = content)
}

@Composable
fun ProvideSpectraExperience(style: SpectraVisualStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSpectraVisualStyle provides style,
        LocalSpectraLayout provides if (style == SpectraVisualStyle.FLUID) FluidSpectraLayout else ClassicSpectraLayout,
        content = content,
    )
}

fun spectraTokensForStyle(
    base: SpectraTokens = DefaultSpectraTokens,
    style: SpectraVisualStyle,
): SpectraTokens = when (style) {
    SpectraVisualStyle.CLASSIC -> base
    SpectraVisualStyle.FLUID -> base.copy(
        spacing = base.spacing.copy(md = 18.dp, lg = 24.dp, xl = 30.dp, xxl = 40.dp),
        radii = base.radii.copy(input = 18.dp, card = 26.dp, hero = 34.dp),
        motion = base.motion.copy(microMillis = 110, shortMillis = 240, longMillis = 520),
        alpha = base.alpha.copy(subtleSurface = .09f, glass = .13f, emphasizedGlass = .21f),
        sizes = base.sizes.copy(navigationDock = 58.dp),
    )
}

enum class PageMood {
    NEUTRAL,
    GROWTH,
    FOCUS,
    SOCIAL,
    COMMERCE,
    PERSONAL,
    CAESAR,
    HEALTH,
}

enum class SpectraWidthClass { COMPACT, MEDIUM, EXPANDED }

fun spectraWidthClassFor(width: Dp): SpectraWidthClass = when {
    width < 600.dp -> SpectraWidthClass.COMPACT
    width < 840.dp -> SpectraWidthClass.MEDIUM
    else -> SpectraWidthClass.EXPANDED
}

val LocalSpectraWidthClass = staticCompositionLocalOf { SpectraWidthClass.COMPACT }

enum class SpectraStatusTone { NEUTRAL, INFO, SUCCESS, WARNING, ERROR, STALE }

enum class SpectraStateKind { LOADING, EMPTY, ERROR, OFFLINE }

@Composable
private fun PageMood.accent(): Color = when (this) {
    PageMood.NEUTRAL -> MaterialTheme.colorScheme.primary
    PageMood.GROWTH -> SpectraColors.Success
    PageMood.FOCUS -> SpectraColors.Focus
    PageMood.SOCIAL -> SpectraColors.Rose
    PageMood.COMMERCE -> SpectraColors.Warm
    PageMood.PERSONAL -> SpectraColors.Cyan
    PageMood.CAESAR -> SpectraColors.Violet
    PageMood.HEALTH -> SpectraColors.Success
}

@Composable
private fun SpectraStatusTone.color(): Color = when (this) {
    SpectraStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    SpectraStatusTone.INFO -> SpectraColors.Focus
    SpectraStatusTone.SUCCESS -> SpectraColors.Success
    SpectraStatusTone.WARNING -> SpectraColors.Warning
    SpectraStatusTone.ERROR -> MaterialTheme.colorScheme.error
    SpectraStatusTone.STALE -> SpectraColors.Warm
}

@Composable
fun SpectraSurface(
    modifier: Modifier = Modifier,
    mood: PageMood = PageMood.NEUTRAL,
    emphasized: Boolean = false,
    shadowed: Boolean = true,
    opticalPriority: Int = if (emphasized) 1 else 0,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(SpectraTheme.tokens.spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = SpectraTheme.tokens
    val fluid = SpectraTheme.isFluid
    GlassPanel(
        modifier = modifier,
        radius = tokens.radii.card.value.roundToInt(),
        emphasized = emphasized,
        onClick = onClick,
        shadowed = shadowed && !fluid,
        opticalPriority = opticalPriority,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun SpectraStatus(
    text: String,
    modifier: Modifier = Modifier,
    tone: SpectraStatusTone = SpectraStatusTone.NEUTRAL,
) {
    val tokens = SpectraTheme.tokens
    val fluid = SpectraTheme.isFluid
    val color = tone.color()
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { stateDescription = text },
        shape = CircleShape,
        color = color.copy(alpha = tokens.alpha.subtleSurface),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = if (fluid) .18f else .32f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.spacing.sm, vertical = tokens.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(if (fluid) 18.dp else 8.dp)
                    .height(if (fluid) 2.dp else 8.dp)
                    .background(color.copy(alpha = if (fluid) .78f else 1f), CircleShape),
            )
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SpectraAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    mood: PageMood = PageMood.CAESAR,
    icon: ImageVector? = null,
) {
    val tokens = SpectraTheme.tokens
    val fluid = SpectraTheme.isFluid
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    val isSelected = selected == true
    val visuallyEmphasized = isSelected || emphasized
    val contentColor = when {
        visuallyEmphasized && dark -> Color.White.copy(alpha = if (enabled) 1f else .6f)
        visuallyEmphasized -> SpectraColors.Ink.copy(alpha = if (enabled) 1f else .54f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .78f else .42f)
    }
    GlassPanel(
        modifier = modifier
            .defaultMinSize(
                minWidth = tokens.sizes.minimumTouchTarget,
                minHeight = tokens.sizes.minimumTouchTarget,
            )
            .semantics {
                role = Role.Button
                selected?.let {
                    this.selected = it
                    stateDescription = if (it) "已选择" else "未选择"
                }
                if (!enabled) disabled()
            },
        radius = 50,
        emphasized = visuallyEmphasized,
        shadowed = false,
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(tokens.sizes.icon), tint = contentColor)
            Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor)
        }
    }
}

@Composable
fun SpectraIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = SpectraTheme.tokens
    GlassPanel(
        onClick = if (enabled) onClick else null,
        modifier = modifier
            .defaultMinSize(tokens.sizes.iconAction, tokens.sizes.iconAction)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
            },
        radius = 50,
        shadowed = false,
    ) {
        Icon(icon, contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(tokens.sizes.icon),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
    }
}

@Composable
fun SpectraStatePane(
    kind: SpectraStateKind,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = SpectraTheme.tokens
    val tone = when (kind) {
        SpectraStateKind.LOADING -> SpectraStatusTone.INFO
        SpectraStateKind.EMPTY -> SpectraStatusTone.NEUTRAL
        SpectraStateKind.ERROR -> SpectraStatusTone.ERROR
        SpectraStateKind.OFFLINE -> SpectraStatusTone.STALE
    }
    SpectraSurface(modifier = modifier, shadowed = false) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = actionLabel == null) {
                stateDescription = "$title。$detail"
            },
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        ) {
            SpectraStatus(
                text = when (kind) {
                    SpectraStateKind.LOADING -> "正在准备"
                    SpectraStateKind.EMPTY -> "暂无内容"
                    SpectraStateKind.ERROR -> "需要处理"
                    SpectraStateKind.OFFLINE -> "离线"
                },
                tone = tone,
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = tokens.alpha.secondaryText),
            )
            if (actionLabel != null && onAction != null) {
                SpectraAction(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun SpectraPageScaffold(
    modifier: Modifier = Modifier,
    mood: PageMood = PageMood.NEUTRAL,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // CampusApp owns the single living field. Page scaffolds stay optically quiet so content,
    // rather than a second colour wash, establishes hierarchy.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthClass = spectraWidthClassFor(maxWidth)
        CompositionLocalProvider(LocalSpectraWidthClass provides widthClass) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = topBar,
                bottomBar = bottomBar,
                snackbarHost = snackbarHost,
                floatingActionButton = floatingActionButton,
                content = content,
            )
        }
    }
}
