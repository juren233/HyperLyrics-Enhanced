package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMetadataSurfaceCoordinatorTest {

    @Test
    fun `new page generation clears previous page and visibility`() {
        val first = Any()
        val second = Any()
        val coordinator = coordinator()

        val firstPage = coordinator.onSurfaceResumed(first)
        coordinator.markCurrentPage(listOf("1", "2"))
        coordinator.markVisible(listOf("2"))
        val secondPage = coordinator.onSurfaceResumed(second)

        assertTrue(secondPage.generation > firstPage.generation)
        assertEquals(emptySet<String>(), secondPage.activePageMediaIds)
        assertEquals(emptySet<String>(), secondPage.visibleMediaIds)
    }

    @Test
    fun `visible outranks current page and background`() {
        val coordinator = coordinator()
        coordinator.onSurfaceResumed(Any())
        coordinator.markCurrentPage(listOf("1", "2"))
        coordinator.markVisible(listOf("2"))

        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            coordinator.requestContext("1").priority,
        )
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            coordinator.requestContext("2").priority,
        )
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
            coordinator.requestContext("3").priority,
        )
    }

    @Test
    fun `stale page completion cannot refresh the new page`() {
        val coordinator = coordinator()
        coordinator.onSurfaceResumed(Any())
        coordinator.markCurrentPage(listOf("1"))
        val oldRequest = coordinator.requestContext("1")

        coordinator.onSurfaceResumed(Any())
        coordinator.markCurrentPage(listOf("2"))

        assertFalse(coordinator.allowsRefresh(oldRequest.generation, "1"))
        assertFalse(coordinator.allowsRefresh(oldRequest.generation, "2"))
    }

    @Test
    fun `active playback remains visible and refreshable across page changes`() {
        val coordinator = coordinator()
        coordinator.onSurfaceResumed(Any())
        coordinator.setPlaybackMediaId("9")
        val request = coordinator.requestContext("9")

        coordinator.onSurfaceResumed(Any())

        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            coordinator.requestContext("9").priority,
        )
        assertTrue(coordinator.allowsRefresh(request.generation, "9"))
    }

    @Test
    fun `expired visibility falls back to current page priority`() {
        var now = 0L
        val coordinator = AppleMetadataSurfaceCoordinator(
            clock = { now },
            visibleTtlMs = 100L,
        )
        coordinator.onSurfaceResumed(Any())
        coordinator.markCurrentPage(listOf("5"))
        coordinator.markVisible(listOf("5"))

        now = 101L

        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            coordinator.requestContext("5").priority,
        )
    }

    @Test
    fun `current page scope evicts the least recently touched media id`() {
        val coordinator = AppleMetadataSurfaceCoordinator(
            clock = { 0L },
            maxPageMediaIds = 3,
        )
        coordinator.onSurfaceResumed(Any())
        coordinator.markCurrentPage(listOf("1", "2", "3"))
        coordinator.markCurrentPage(listOf("1"))

        val snapshot = coordinator.markCurrentPage(listOf("4"))

        assertEquals(setOf("1", "3", "4"), snapshot.activePageMediaIds)
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
            coordinator.requestContext("2").priority,
        )
    }

    @Test
    fun `default current page scope retains a typical long playlist`() {
        val coordinator = coordinator()
        coordinator.onSurfaceResumed(Any())
        val mediaIds = (1..100).map(Int::toString)

        coordinator.markCurrentPage(mediaIds)

        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            coordinator.requestContext("1").priority,
        )
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            coordinator.requestContext("100").priority,
        )
    }

    @Test
    fun `visible resolution lease outlives the short coordinator visibility ttl`() {
        var now = 0L
        val leases = AppleVisibleMetadataResolutionLeases(
            clock = { now },
            ttlMs = 30_000L,
        )

        leases.mark(listOf("6769714078"))
        now = 4_300L

        assertTrue(leases.contains("6769714078"))
    }

    @Test
    fun `visible resolution lease expires after the catalog timeout window`() {
        var now = 0L
        val leases = AppleVisibleMetadataResolutionLeases(
            clock = { now },
            ttlMs = 30_000L,
        )

        leases.mark(listOf("6769714078"))
        now = 30_001L

        assertFalse(leases.contains("6769714078"))
    }

    private fun coordinator() = AppleMetadataSurfaceCoordinator(clock = { 0L })
}
