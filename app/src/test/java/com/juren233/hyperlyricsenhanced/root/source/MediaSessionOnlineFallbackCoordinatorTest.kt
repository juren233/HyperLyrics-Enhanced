package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences.Settings
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSessionOnlineFallbackCoordinatorTest {
    private val a = TrackIdentity("web.player", mediaId = "a", title = "A", artist = "Artist", durationMs = 180_000)
    private val b = a.copy(mediaId = "b", title = "B")
    private val song = Song(name = "A", lyrics = listOf(RichLyricLine(begin = 0, end = 180_000, duration = 180_000, text = "Line")))

    private class FakeTracks(override var current: TrackIdentity?) : MediaSessionOnlineFallbackCoordinator.Tracks {
        override var usable = true
        var listener: (() -> Unit)? = null
        override fun listen(onChanged: () -> Unit) { listener = onChanged }
        override fun unlisten() { listener = null }
        fun change(track: TrackIdentity?) { current = track; listener?.invoke() }
    }

    private inner class Fixture(scope: TestScope) {
        val tracks = FakeTracks(a)
        var settings = Settings(listOf(Source.NE, Source.QM), setOf(a.packageName))
        var primary = false
        var primaryListener: (() -> Unit)? = null
        var closed = false
        var calls = 0
        val submissions = mutableListOf<TimelineContent>()
        var fetch: suspend (TrackIdentity, List<Source>) -> Song? = { _, _ -> song }
        val coordinator = MediaSessionOnlineFallbackCoordinator(
            tracks = tracks,
            timeline = object : MediaSessionOnlineFallbackCoordinator.Timeline {
                override fun hasPrimary(track: TrackIdentity) = primary
                override fun hasFallback(track: TrackIdentity) = submissions.lastOrNull()?.track?.let {
                    MediaSessionOnlineFallbackPolicy.sameLookupTrack(it, track)
                } == true
                override fun authorize(settings: Settings, sessionUsable: Boolean) = Unit
                override fun submit(content: TimelineContent) { submissions += content }
                override fun listen(onPrimaryChanged: () -> Unit) { primaryListener = onPrimaryChanged }
                override fun close() { closed = true; primaryListener = null }
            },
            settings = { settings },
            fetchSong = { track, sources -> calls++; fetch(track, sources) },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            nowMs = { scope.testScheduler.currentTime },
        )
    }

    @Test fun `eligible track waits for primary then fetches using configured source order`() = runTest {
        val f = Fixture(this)
        f.fetch = { track, sources -> assertEquals(a, track); assertEquals(f.settings.sources, sources); song }
        f.coordinator.start()
        advanceTimeBy(1_499)
        assertEquals(0, f.calls)
        advanceUntilIdle()
        assertEquals(1, f.calls)
        assertEquals(a, f.submissions.single().track)
        assertTrue(f.submissions.single().strictIdentity)
        f.coordinator.stop()
    }

    @Test fun `empty player list invalid and placeholder tracks never fetch`() = runTest {
        val f = Fixture(this)
        f.settings = f.settings.copy(packages = emptySet())
        f.coordinator.start()
        advanceUntilIdle()
        f.settings = f.settings.copy(packages = setOf(a.packageName))
        f.tracks.usable = false
        f.coordinator.refresh()
        advanceUntilIdle()
        f.tracks.usable = true
        f.tracks.change(a.copy(title = "Playing~"))
        advanceUntilIdle()
        assertEquals(0, f.calls)
        f.coordinator.stop()
    }

    @Test fun `unchanged metadata album refresh and pause resume do not restart pending lookup`() = runTest {
        val f = Fixture(this)
        val result = CompletableDeferred<Song?>()
        f.fetch = { _, _ -> result.await() }
        f.coordinator.start()
        advanceTimeBy(1_500); runCurrent()
        f.tracks.change(a.copy(album = "Album", durationMs = 180_001))
        // PAUSED remains usable; no position or seek event changes the query identity.
        repeat(4) { f.coordinator.refresh() }
        runCurrent()
        assertEquals(1, f.calls)
        result.complete(song)
        advanceUntilIdle()
        repeat(4) { f.coordinator.refresh() }
        advanceUntilIdle()
        assertEquals(1, f.calls)
        assertEquals(1, f.submissions.size)
        f.coordinator.stop()
    }

    @Test fun `delayed A cannot overwrite B even if fetch ignores cancellation`() = runTest {
        val f = Fixture(this)
        val result = CompletableDeferred<Song?>()
        f.fetch = { track, _ -> if (track == a) withContext(NonCancellable) { result.await() } else song }
        f.coordinator.start()
        advanceTimeBy(1_500); runCurrent()
        f.tracks.change(b)
        advanceTimeBy(1_500); runCurrent()
        result.complete(song)
        advanceUntilIdle()
        assertEquals(listOf(b), f.submissions.map { it.track })
        f.coordinator.stop()
    }

    @Test fun `reused media id with changed title invalidates pending query`() = runTest {
        val f = Fixture(this)
        f.coordinator.start()
        advanceTimeBy(500)
        f.tracks.change(a.copy(title = "New song"))
        advanceUntilIdle()
        assertEquals(1, f.calls)
        assertEquals("New song", f.submissions.single().track?.title)
        f.coordinator.stop()
    }

    @Test fun `primary during grace avoids fetch and primary during request cancels it`() = runTest {
        val f = Fixture(this)
        f.coordinator.start()
        f.primary = true
        f.primaryListener?.invoke()
        advanceUntilIdle()
        assertEquals(0, f.calls)
        f.primary = false
        f.fetch = { _, _ -> awaitCancellation() }
        f.primaryListener?.invoke()
        advanceTimeBy(1_500); runCurrent()
        assertEquals(1, f.calls)
        f.primary = true
        f.primaryListener?.invoke()
        advanceUntilIdle()
        assertTrue(f.submissions.isEmpty())
        f.coordinator.stop()
    }

    @Test fun `no result leaves empty state and failures are suppressed with bounded expiry`() = runTest {
        val f = Fixture(this)
        f.fetch = { _, _ -> null }
        f.coordinator.start()
        advanceUntilIdle()
        f.tracks.change(null)
        f.tracks.change(a)
        advanceUntilIdle()
        assertEquals(1, f.calls)
        assertTrue(f.submissions.isEmpty())
        advanceTimeBy(60_001)
        f.tracks.change(null)
        f.tracks.change(a)
        advanceUntilIdle()
        assertEquals(2, f.calls)
        f.coordinator.stop()
    }

    @Test fun `success cache restores revisited track without another request`() = runTest {
        val f = Fixture(this)
        f.coordinator.start()
        advanceUntilIdle()
        f.tracks.change(b)
        advanceUntilIdle()
        f.tracks.change(a)
        advanceUntilIdle()
        assertEquals(2, f.calls)
        assertEquals(listOf(a, b, a), f.submissions.map { it.track })
        f.coordinator.stop()
    }

    @Test fun `clearing player list and stopping session cancel pending requests`() = runTest {
        val f = Fixture(this)
        var cancelled = 0
        f.fetch = { _, _ -> try { awaitCancellation() } finally { cancelled++ } }
        f.coordinator.start()
        advanceTimeBy(1_500); runCurrent()
        f.settings = f.settings.copy(packages = emptySet())
        f.coordinator.refresh(); runCurrent()
        assertEquals(1, cancelled)
        f.settings = f.settings.copy(packages = setOf(a.packageName))
        f.coordinator.refresh()
        advanceTimeBy(1_500); runCurrent()
        f.tracks.usable = false
        f.tracks.listener?.invoke(); runCurrent()
        assertEquals(2, cancelled)
        assertTrue(f.submissions.isEmpty())
        f.coordinator.stop()
    }

    @Test fun `session disappears or another player takes over cancels old work`() = runTest {
        val f = Fixture(this)
        var cancelled = 0
        f.fetch = { _, _ -> try { awaitCancellation() } finally { cancelled++ } }
        f.coordinator.start()
        advanceTimeBy(1_500); runCurrent()
        f.tracks.change(null); runCurrent()
        f.tracks.change(a)
        advanceTimeBy(1_500); runCurrent()
        f.tracks.change(a.copy(packageName = "native.player")); runCurrent()
        assertEquals(2, cancelled)
        assertTrue(f.submissions.isEmpty())
        f.coordinator.stop()
    }

    @Test fun `runtime stop releases listeners and cancels jobs`() = runTest {
        val f = Fixture(this)
        var cancelled = false
        f.fetch = { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        f.coordinator.start()
        advanceTimeBy(1_500); runCurrent()
        f.coordinator.stop()
        f.coordinator.stop()
        f.coordinator.refresh()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(f.closed)
        assertNull(f.tracks.listener)
        assertNull(f.primaryListener)
        assertTrue(f.submissions.isEmpty())
    }

    @Test fun `provider timeout produces no content and does not leak a job`() = runTest {
        val f = Fixture(this)
        f.fetch = { _, _ -> awaitCancellation() }
        f.coordinator.start()
        advanceUntilIdle()
        assertEquals(1, f.calls)
        assertTrue(f.submissions.isEmpty())
        f.coordinator.stop()
    }

    @Test fun `switching between selected browser PWA and native players fetches each track`() = runTest {
        val f = Fixture(this)
        val packages = listOf("com.android.chrome", "org.chromium.webapk.example", "native.player")
        f.settings = f.settings.copy(packages = packages.toSet())
        val observed = mutableListOf<String>()
        f.fetch = { track, _ -> observed += track.packageName; song }
        f.tracks.current = a.copy(packageName = packages.first())
        f.coordinator.start()
        advanceUntilIdle()
        packages.drop(1).forEach { pkg ->
            f.tracks.change(a.copy(packageName = pkg))
            advanceUntilIdle()
        }
        assertEquals(packages, observed)
        assertEquals(packages, f.submissions.map { it.track?.packageName })
        f.coordinator.stop()
    }

    @Test fun `no enabled online sources means no lookup for any player`() = runTest {
        val f = Fixture(this)
        f.settings = f.settings.copy(sources = emptyList())
        f.coordinator.start()
        advanceUntilIdle()
        assertEquals(0, f.calls)
        assertTrue(f.submissions.isEmpty())
        f.coordinator.stop()
    }

    @Test fun `changing provider order cancels old query and uses new order`() = runTest {
        val f = Fixture(this)
        val result = CompletableDeferred<Song?>()
        val originalSources = f.settings.sources
        val observed = mutableListOf<List<Source>>()
        f.fetch = { _, sources ->
            observed += sources
            if (sources == originalSources) withContext(NonCancellable) { result.await() } else song
        }
        f.coordinator.start()
        advanceTimeBy(1_500); runCurrent()
        f.settings = f.settings.copy(sources = originalSources.reversed())
        f.coordinator.refresh()
        advanceTimeBy(1_500); runCurrent()
        result.complete(song)
        advanceUntilIdle()
        assertEquals(listOf(originalSources, originalSources.reversed()), observed)
        assertEquals(1, f.submissions.size)
        f.coordinator.stop()
    }

    @Test fun `unselected package never fetches and removing selected package cancels pending work`() = runTest {
        val f = Fixture(this)
        var cancelled = false
        f.settings = f.settings.copy(packages = emptySet())
        f.coordinator.start()
        advanceUntilIdle()
        assertEquals(0, f.calls)
        f.fetch = { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        f.settings = f.settings.copy(packages = setOf(a.packageName))
        f.coordinator.refresh()
        advanceTimeBy(1_500); runCurrent()
        assertEquals(1, f.calls)
        f.settings = f.settings.copy(packages = emptySet())
        f.coordinator.refresh()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(f.submissions.isEmpty())
        f.coordinator.stop()
    }

    @Test fun `material duration refresh restarts lookup and rejects delayed old version`() = runTest {
        val f = Fixture(this)
        val result = CompletableDeferred<Song?>()
        val corrected = a.copy(durationMs = 240_000)
        f.fetch = { track, _ -> if (track == a) withContext(NonCancellable) { result.await() } else song }
        f.coordinator.start()
        advanceTimeBy(1_500); runCurrent()
        f.tracks.change(corrected)
        advanceTimeBy(1_500); runCurrent()
        result.complete(song)
        advanceUntilIdle()
        assertEquals(2, f.calls)
        assertEquals(listOf(corrected), f.submissions.map { it.track })
        f.coordinator.stop()
    }

    @Test fun `success cached with outdated duration cannot be reused for corrected metadata`() = runTest {
        val f = Fixture(this)
        f.coordinator.start()
        advanceUntilIdle()
        f.tracks.change(a.copy(durationMs = 240_000))
        advanceUntilIdle()
        assertEquals(2, f.calls)
        assertEquals(240_000L, f.submissions.last().track?.durationMs)
        f.coordinator.stop()
    }

    @Test fun `late noncancellable result after stop is discarded`() = runTest {
        val f = Fixture(this)
        val result = CompletableDeferred<Song?>()
        f.fetch = { _, _ -> withContext(NonCancellable) { result.await() } }
        f.coordinator.start()
        advanceTimeBy(1_500); runCurrent()
        f.coordinator.stop()
        result.complete(song)
        advanceUntilIdle()
        assertTrue(f.submissions.isEmpty())
        assertNull(f.tracks.listener)
    }
}
