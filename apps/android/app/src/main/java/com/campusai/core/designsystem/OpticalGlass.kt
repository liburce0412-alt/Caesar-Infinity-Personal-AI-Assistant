package com.campusai.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicLong

/**
 * A screen-space request for renderer-owned refraction.
 *
 * The shared SPECTRA renderer can refract only its own scene texture. Compose content is drawn
 * afterwards, so labels and icons remain sharp and are never fed through the dispersion shader.
 */
internal data class OpticalGlassRegion(
    val id: Long,
    val boundsInWindow: Rect,
    val cornerRadiusPx: Float,
    val refractionPx: Float,
    val dispersionPx: Float,
    val flowPx: Float,
    val bodyOpacity: Float,
    val priority: Int,
    val interaction: Float = 0f,
    val touch: Offset = Offset(.5f, .5f),
) {
    val area: Float get() = boundsInWindow.width * boundsInWindow.height
}

/** Identifies both the renderer that owns the scene texture and one atomic route generation. */
internal data class OpticalGlassScope(
    val rendererOwnerId: Long,
    val routeGeneration: Long,
    /** Changes only when navigation enters a different route, including A -> B -> A. */
    val routeVisitGeneration: Long,
    val routeKey: String,
)

internal fun compatibleOpticalScope(
    previous: OpticalGlassScope?,
    active: OpticalGlassScope,
): OpticalGlassScope? = when {
    previous == null || previous == active -> active
    previous.routeKey == active.routeKey &&
        previous.routeVisitGeneration == active.routeVisitGeneration -> active
    else -> null
}

/**
 * Process-local bridge between Compose layout and the single GLSurfaceView renderer.
 *
 * Region updates are accepted only for the exact active owner/generation pair. Switching a
 * full-screen route clears the old generation atomically, so an `onGloballyPositioned` callback
 * arriving from a leaving route cannot put its glass back over the new page.
 */
internal object OpticalGlassRegistry {
    private const val MAX_HIGH_QUALITY_REGIONS = 3
    private const val INITIAL_ROUTE = "app:boot"
    private val ids = AtomicLong(1L)
    private val lock = Any()
    private val regions = LinkedHashMap<Long, ScopedOpticalGlassRegion>()
    private var rendererOwnerId: Long? = null
    private var routeGeneration = 0L
    private var routeVisitGeneration = 0L
    private var routeKey = INITIAL_ROUTE

    private data class ScopedOpticalGlassRegion(
        val scope: OpticalGlassScope,
        val region: OpticalGlassRegion,
    )

    fun nextId(): Long = ids.getAndIncrement()

    fun nextRendererOwnerId(): Long = ids.getAndIncrement()

    fun claimRenderer(ownerId: Long): OpticalGlassScope = synchronized(lock) {
        if (rendererOwnerId != ownerId) {
            rendererOwnerId = ownerId
            routeGeneration += 1L
            regions.clear()
        }
        currentScopeLocked()!!
    }

    fun releaseRenderer(ownerId: Long) = synchronized(lock) {
        if (rendererOwnerId == ownerId) {
            regions.clear()
            rendererOwnerId = null
            routeGeneration += 1L
        }
    }

    /** Starts a new Compose/Activity host even when it restores the same logical route key. */
    fun beginRouteHost(newRouteKey: String): OpticalGlassScope? = synchronized(lock) {
        require(newRouteKey.isNotBlank()) { "Optical route key must not be blank" }
        routeKey = newRouteKey
        routeGeneration += 1L
        routeVisitGeneration += 1L
        regions.clear()
        currentScopeLocked()
    }

    /** Returns a new scope only when the logical route actually changes. */
    fun switchRouteScope(newRouteKey: String): OpticalGlassScope? = synchronized(lock) {
        require(newRouteKey.isNotBlank()) { "Optical route key must not be blank" }
        if (routeKey != newRouteKey) {
            routeKey = newRouteKey
            routeGeneration += 1L
            routeVisitGeneration += 1L
            regions.clear()
        }
        currentScopeLocked()
    }

    fun currentScope(): OpticalGlassScope? = synchronized(lock) { currentScopeLocked() }

    fun update(scope: OpticalGlassScope, region: OpticalGlassRegion): Boolean = synchronized(lock) {
        if (scope != currentScopeLocked()) return@synchronized false
        if (region.boundsInWindow.width < 2f || region.boundsInWindow.height < 2f) {
            regions.remove(region.id)
        } else {
            regions[region.id] = ScopedOpticalGlassRegion(scope, region)
        }
        true
    }

    fun remove(id: Long): Unit = synchronized(lock) {
        regions.remove(id)
        Unit
    }

    fun snapshot(
        rendererOwnerId: Long,
        viewLeft: Float,
        viewTop: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): List<OpticalGlassRegion> = synchronized(lock) {
        val activeScope = currentScopeLocked()
        if (activeScope == null || activeScope.rendererOwnerId != rendererOwnerId) {
            return@synchronized emptyList()
        }
        val viewRight = viewLeft + viewWidth
        val viewBottom = viewTop + viewHeight
        regions.values
            .asSequence()
            .filter { it.scope == activeScope }
            .map { it.region }
            .filter { region ->
                region.boundsInWindow.right > viewLeft &&
                    region.boundsInWindow.left < viewRight &&
                    region.boundsInWindow.bottom > viewTop &&
                    region.boundsInWindow.top < viewBottom
            }
            .sortedWith(
                compareByDescending<OpticalGlassRegion> { it.priority }
                    .thenByDescending { it.area }
                    .thenBy { it.id },
            )
            .take(MAX_HIGH_QUALITY_REGIONS)
            .toList()
    }

    private fun currentScopeLocked(): OpticalGlassScope? = rendererOwnerId?.let { ownerId ->
        OpticalGlassScope(
            rendererOwnerId = ownerId,
            routeGeneration = routeGeneration,
            routeVisitGeneration = routeVisitGeneration,
            routeKey = routeKey,
        )
    }
}

/**
 * Registers this Compose surface with the shared SPECTRA refraction pass.
 *
 * Registration is a Modifier node rather than a composition effect. `onDetach` is therefore the
 * final lifecycle authority even when a route leaves during animation or layout callbacks race.
 */
fun Modifier.opticalGlassRegion(
    enabled: Boolean = true,
    radius: Dp = 16.dp,
    priority: Int = 0,
    refraction: Dp = 4.dp,
    dispersion: Dp = .35.dp,
    flow: Dp = .8.dp,
    bodyOpacity: Float = .065f,
    interaction: Float = 0f,
    touch: Offset = Offset(.5f, .5f),
): Modifier = this.then(
    OpticalGlassElement(
        enabled = enabled,
        radius = radius,
        priority = priority,
        refraction = refraction,
        dispersion = dispersion,
        flow = flow,
        bodyOpacity = bodyOpacity.coerceIn(.06f, .30f),
        interaction = interaction.coerceIn(-.35f, 1.15f),
        touch = touch,
    ),
)

private data class OpticalGlassElement(
    val enabled: Boolean,
    val radius: Dp,
    val priority: Int,
    val refraction: Dp,
    val dispersion: Dp,
    val flow: Dp,
    val bodyOpacity: Float,
    val interaction: Float,
    val touch: Offset,
) : ModifierNodeElement<OpticalGlassModifierNode>() {
    override fun create(): OpticalGlassModifierNode = OpticalGlassModifierNode(
        scope = OpticalGlassRegistry.currentScope(),
        enabled = enabled,
        radius = radius,
        priority = priority,
        refraction = refraction,
        dispersion = dispersion,
        flow = flow,
        bodyOpacity = bodyOpacity,
        interaction = interaction,
        touch = touch,
    )

    override fun update(node: OpticalGlassModifierNode) {
        node.updateParameters(enabled, radius, priority, refraction, dispersion, flow, bodyOpacity, interaction, touch)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "opticalGlassRegion"
        properties["enabled"] = enabled
        properties["radius"] = radius
        properties["priority"] = priority
        properties["refraction"] = refraction
        properties["dispersion"] = dispersion
        properties["flow"] = flow
        properties["bodyOpacity"] = bodyOpacity
    }
}

private class OpticalGlassModifierNode(
    private var scope: OpticalGlassScope?,
    private var enabled: Boolean,
    private var radius: Dp,
    private var priority: Int,
    private var refraction: Dp,
    private var dispersion: Dp,
    private var flow: Dp,
    private var bodyOpacity: Float,
    private var interaction: Float,
    private var touch: Offset,
) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private val id = OpticalGlassRegistry.nextId()
    private var coordinates: LayoutCoordinates? = null

    override fun onAttach() {
        if (scope == null) scope = OpticalGlassRegistry.currentScope()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        updateRegion()
    }

    override fun onDetach() {
        OpticalGlassRegistry.remove(id)
        coordinates = null
        scope = null
    }

    fun updateParameters(
        enabled: Boolean,
        radius: Dp,
        priority: Int,
        refraction: Dp,
        dispersion: Dp,
        flow: Dp,
        bodyOpacity: Float,
        interaction: Float,
        touch: Offset,
    ) {
        this.enabled = enabled
        this.radius = radius
        this.priority = priority
        this.refraction = refraction
        this.dispersion = dispersion
        this.flow = flow
        this.bodyOpacity = bodyOpacity
        this.interaction = interaction
        this.touch = touch
        if (isAttached) updateRegion()
    }

    private fun updateRegion() {
        val layoutCoordinates = coordinates
        if (!enabled || layoutCoordinates == null || !layoutCoordinates.isAttached) {
            OpticalGlassRegistry.remove(id)
            return
        }
        // A renderer recreation on the same logical route gets a fresh owner/generation. Adopt it
        // so OFF→ON keeps glass alive, while refusing late callbacks from a different route.
        val activeScope = OpticalGlassRegistry.currentScope() ?: return
        val pinnedScope = compatibleOpticalScope(scope, activeScope)?.also { scope = it } ?: return
        val density = requireDensity()
        OpticalGlassRegistry.update(
            pinnedScope,
            OpticalGlassRegion(
                id = id,
                boundsInWindow = layoutCoordinates.boundsInWindow(),
                cornerRadiusPx = with(density) { radius.toPx() },
                refractionPx = with(density) { refraction.toPx() },
                dispersionPx = with(density) { dispersion.toPx() },
                flowPx = with(density) { flow.toPx() },
                bodyOpacity = bodyOpacity,
                priority = priority,
                interaction = interaction,
                touch = touch,
            ),
        )
    }
}
