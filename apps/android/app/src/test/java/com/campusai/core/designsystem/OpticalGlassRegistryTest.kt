package com.campusai.core.designsystem

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalGlassRegistryTest {
    @Test fun focalOrbPreservesThreeOpticalPanelsAndIsRemovedOnRouteChange() {
        val owner = OpticalGlassRegistry.nextRendererOwnerId()
        val scope = OpticalGlassRegistry.claimRenderer(owner)
        try {
            repeat(4) { OpticalGlassRegistry.update(scope, region(OpticalGlassRegistry.nextId(), it)) }
            repeat(2) { OpticalGlassRegistry.update(scope, region(OpticalGlassRegistry.nextId()).copy(orb = CaesarOrbSignal())) }
            val visible = OpticalGlassRegistry.snapshot(owner, 0f, 0f, 500f, 500f)
            assertEquals(3, visible.count { it.orb == null })
            assertEquals(1, visible.count { it.orb != null })
            assertEquals(listOf(3, 2, 1), visible.filter { it.orb == null }.map { it.priority })
            OpticalGlassRegistry.switchRouteScope("test:orb-leave")
            assertTrue(OpticalGlassRegistry.snapshot(owner, 0f, 0f, 500f, 500f).isEmpty())
        } finally { OpticalGlassRegistry.releaseRenderer(owner) }
    }

    @Test
    fun routeSwitchClearsRegionsAndRejectsLateUpdates() {
        val owner = OpticalGlassRegistry.nextRendererOwnerId()
        OpticalGlassRegistry.claimRenderer(owner)
        val homeScope = OpticalGlassRegistry.switchRouteScope("test:home")!!
        val homeRegion = region(id = OpticalGlassRegistry.nextId())

        try {
            assertTrue(OpticalGlassRegistry.update(homeScope, homeRegion))
            assertEquals(listOf(homeRegion), OpticalGlassRegistry.snapshot(owner, 0f, 0f, 500f, 500f))

            val aiScope = OpticalGlassRegistry.switchRouteScope("test:ai")!!
            assertEquals(emptyList<OpticalGlassRegion>(), OpticalGlassRegistry.snapshot(owner, 0f, 0f, 500f, 500f))
            assertFalse(OpticalGlassRegistry.update(homeScope, homeRegion))

            val aiRegion = region(id = OpticalGlassRegistry.nextId(), priority = 2)
            assertTrue(OpticalGlassRegistry.update(aiScope, aiRegion))
            assertEquals(listOf(aiRegion), OpticalGlassRegistry.snapshot(owner, 0f, 0f, 500f, 500f))
        } finally {
            OpticalGlassRegistry.releaseRenderer(owner)
        }
    }

    @Test
    fun rendererOwnershipFiltersSnapshotsAndOldOwnerCannotReleaseNewOne() {
        val firstOwner = OpticalGlassRegistry.nextRendererOwnerId()
        val secondOwner = OpticalGlassRegistry.nextRendererOwnerId()
        OpticalGlassRegistry.claimRenderer(firstOwner)
        OpticalGlassRegistry.switchRouteScope("test:first")
        val secondScope = OpticalGlassRegistry.claimRenderer(secondOwner)
        val secondRegion = region(id = OpticalGlassRegistry.nextId())

        try {
            assertTrue(OpticalGlassRegistry.update(secondScope, secondRegion))
            assertEquals(emptyList<OpticalGlassRegion>(), OpticalGlassRegistry.snapshot(firstOwner, 0f, 0f, 500f, 500f))
            OpticalGlassRegistry.releaseRenderer(firstOwner)
            assertEquals(listOf(secondRegion), OpticalGlassRegistry.snapshot(secondOwner, 0f, 0f, 500f, 500f))
        } finally {
            OpticalGlassRegistry.releaseRenderer(secondOwner)
        }
    }

    @Test
    fun sameRouteRendererRecreationAdoptsNewScopeButRouteChangeDoesNot() {
        val old = OpticalGlassScope(1L, 10L, 7L, "test:home")
        val recreated = OpticalGlassScope(2L, 11L, 7L, "test:home")
        val otherRoute = OpticalGlassScope(2L, 12L, 8L, "test:ai")

        assertEquals(recreated, compatibleOpticalScope(old, recreated))
        assertEquals(null, compatibleOpticalScope(old, otherRoute))
    }

    @Test
    fun revisitingTheSameRouteRejectsNodesFromThePreviousVisit() {
        val owner = OpticalGlassRegistry.nextRendererOwnerId()
        OpticalGlassRegistry.claimRenderer(owner)
        val firstHome = OpticalGlassRegistry.switchRouteScope("test:home")!!

        try {
            OpticalGlassRegistry.switchRouteScope("test:ai")!!
            val secondHome = OpticalGlassRegistry.switchRouteScope("test:home")!!

            assertEquals(null, compatibleOpticalScope(firstHome, secondHome))
            assertFalse(OpticalGlassRegistry.update(firstHome, region(OpticalGlassRegistry.nextId())))
        } finally {
            OpticalGlassRegistry.releaseRenderer(owner)
        }
    }

    @Test
    fun aNewRouteHostRejectsNodesFromARecreatedActivityOnTheSameRoute() {
        val owner = OpticalGlassRegistry.nextRendererOwnerId()
        OpticalGlassRegistry.claimRenderer(owner)
        val previousHost = OpticalGlassRegistry.beginRouteHost("test:home")!!

        try {
            val recreatedHost = OpticalGlassRegistry.beginRouteHost("test:home")!!

            assertEquals(null, compatibleOpticalScope(previousHost, recreatedHost))
            assertFalse(OpticalGlassRegistry.update(previousHost, region(OpticalGlassRegistry.nextId())))
        } finally {
            OpticalGlassRegistry.releaseRenderer(owner)
        }
    }

    private fun region(id: Long, priority: Int = 0): OpticalGlassRegion = OpticalGlassRegion(
        id = id,
        boundsInWindow = Rect(20f, 30f, 220f, 180f),
        cornerRadiusPx = 24f,
        refractionPx = 5f,
        dispersionPx = 1f,
        flowPx = 2f,
        bodyOpacity = .12f,
        priority = priority,
    )
}
