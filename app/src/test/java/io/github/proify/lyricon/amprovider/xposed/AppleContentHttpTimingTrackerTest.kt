package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleContentHttpTimingTrackerTest {

    @Test
    fun `summaries separate native and module traffic`() {
        var now = 0L
        val tracker = AppleContentHttpTimingTracker(
            clock = { now },
            summaryIntervalMs = 1_000L,
            slowRequestMs = 600L,
        )
        val native = Any()
        val module = Any()

        tracker.start(
            native,
            descriptor(
                source = AppleContentHttpTimingTracker.Source.NATIVE,
                category = "artists",
                pending = 0,
            ),
        )
        tracker.start(
            module,
            descriptor(
                source = AppleContentHttpTimingTracker.Source.MODULE,
                category = "songs",
                pending = 3,
            ),
        )
        now = 400L
        assertFalse(requireNotNull(tracker.finish(native, 200)).isSlow)
        now = 1_000L
        val completion = requireNotNull(tracker.finish(module, 200))
        val summary = assertNotNull(completion.summary).let { requireNotNull(completion.summary) }

        assertTrue(completion.isSlow)
        assertEquals(1, summary.native.completed)
        assertEquals(400L, summary.native.averageElapsedMs)
        assertEquals(mapOf("artists" to 1), summary.native.categories)
        assertEquals(1, summary.module.completed)
        assertEquals(1_000L, summary.module.averageElapsedMs)
        assertEquals(mapOf("songs" to 1), summary.module.categories)
        assertEquals(0, summary.totalInFlight)
    }

    @Test
    fun `request keys use object identity`() {
        var now = 0L
        val tracker = AppleContentHttpTimingTracker(
            clock = { now },
            summaryIntervalMs = 10_000L,
        )
        val first = EqualKey("same")
        val second = EqualKey("same")

        val firstStart = tracker.start(
            first,
            descriptor(AppleContentHttpTimingTracker.Source.NATIVE, "songs", 0),
        )
        val secondStart = tracker.start(
            second,
            descriptor(AppleContentHttpTimingTracker.Source.NATIVE, "albums", 0),
        )

        assertEquals(1, firstStart.sourceInFlight)
        assertEquals(2, secondStart.sourceInFlight)
        now = 50L
        assertEquals("songs", requireNotNull(tracker.finish(first, 200)).descriptor.category)
        assertEquals("albums", requireNotNull(tracker.finish(second, 200)).descriptor.category)
    }

    @Test
    fun `unknown completions are ignored`() {
        val tracker = AppleContentHttpTimingTracker(clock = { 0L })

        assertEquals(null, tracker.finish(Any(), null))
    }

    private fun descriptor(
        source: AppleContentHttpTimingTracker.Source,
        category: String,
        pending: Int,
    ) = AppleContentHttpTimingTracker.RequestDescriptor(
        source = source,
        category = category,
        storefront = "us",
        pendingModuleRequests = pending,
    )

    private data class EqualKey(val value: String)
}
