/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppleLyricsStateOwnersTest {
    private fun song(id: String = "a", translation: String = "你好") = Song(
        id = id,
        lyrics = listOf(RichLyricLine(begin = 0, end = 1000, text = "Hello", translation = translation)),
    )

    @Test fun `knowledge keeps content and adam aliases and bounded clearing`() {
        val knowledge = AppleNativeLyricsKnowledge(2)
        knowledge.remember("1", "a")
        assertTrue(knowledge.contains("a", null, null))
        assertTrue(knowledge.contains("alias", "1", null))
        assertTrue(knowledge.contains("alias", null, "1"))
        assertFalse(knowledge.contains("b", null, null))
        knowledge.remember("2", "b")
        knowledge.remember("3", "c")
        assertFalse(knowledge.contains("a", "1", null))
        assertTrue(knowledge.contains("c", null, null))
    }

    @Test fun `manual selection survives same track but not a b a`() {
        val state = AppleLyricsSourceSelection()
        assertTrue(state.beginPlayback("a"))
        state.select("a", "LB")
        assertFalse(state.beginPlayback("a"))
        assertEquals("LB", state.selected("a"))
        assertTrue(state.beginPlayback("b"))
        assertNull(state.selected("a"))
        state.beginPlayback("a")
        assertNull(state.selected("a"))
        state.select("a", "apple")
        state.remove("a")
        assertNull(state.selected("a"))
    }

    @Test fun `native alternatives preserve exact pointer identity and track pruning`() {
        val alternatives = AppleLyricsNativeAlternatives(2)
        val a = Any()
        val b = Any()
        val stats = AppleNativeLyricsTimingStats(lineCount = 2, wordTimedLineCount = 1)
        alternatives.rememberPointer("a", a)
        alternatives.rememberPointer("b", b)
        alternatives.rememberTiming("1", "a", stats)
        assertSame(a, alternatives.pointer("a"))
        assertEquals(stats, alternatives.timing("1"))
        alternatives.retainTrack("a")
        assertSame(a, alternatives.pointer("a"))
        assertNull(alternatives.pointer("b"))
        assertNull(alternatives.timing("1"))
        assertEquals(stats, alternatives.timing("a"))
    }

    @Test fun `candidate availability is not permission to inject`() {
        val state = AppleMissingLyricsCandidateState()
        state.markAvailable("a")
        assertTrue(state.wasAvailable("a"))
        assertFalse(state.isAccepted("a"))
        assertTrue(state.accept("a"))
        assertFalse(state.accept("a"))
        state.revokeAcceptance("a")
        assertTrue(state.wasAvailable("a"))
        state.revokeAvailability("a")
        assertFalse(state.wasAvailable("a"))
    }

    @Test fun `disk restore attempt window is atomic and can be reset`() {
        val state = AppleMissingLyricsCandidateState()
        val pool = Executors.newFixedThreadPool(2)
        val start = CyclicBarrier(2)
        try {
            val results = (1..2).map {
                pool.submit(Callable { start.await(5, TimeUnit.SECONDS); state.beginRestore("a", "a") })
            }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
        } finally { pool.shutdownNow() }
        assertTrue(state.beginRestore("a", "b"))
        state.resetRestoreAttempts()
        assertTrue(state.beginRestore("a", "b"))
    }

    @Test fun `binding tickets reject a b a without retaining native objects`() {
        val binding = AppleLyricsPresentationBinding()
        val fragment = Any()
        val pointer = Any()
        binding.rememberFragment(fragment)
        binding.rememberPointer(pointer)
        binding.selectSong("a")
        val ticket = binding.ticket()
        val snapshot = binding.snapshot()
        assertSame(fragment, snapshot.fragment)
        assertSame(pointer, snapshot.pointer)
        assertTrue(binding.isCurrent(ticket))
        binding.selectSong("b")
        binding.selectSong("a")
        assertFalse(binding.isCurrent(ticket))
    }

    @Test fun `same song fragment and pointer replacement invalidate queued binding work`() {
        val binding = AppleLyricsPresentationBinding()
        val fragment = Any()
        binding.selectSong("a")
        binding.rememberFragment(fragment)
        val ticket = binding.ticket()
        binding.rememberFragment(fragment)
        assertTrue(binding.isCurrent(ticket))
        binding.rememberFragment(Any())
        assertFalse(binding.isCurrent(ticket))
        val next = binding.ticket()
        binding.rememberPointer(Any())
        assertFalse(binding.isCurrent(next))
        val withPointer = binding.ticket()
        binding.rememberPointer(null)
        assertFalse(binding.isCurrent(withPointer))
    }

    @Test fun `translation display revision advances without invalidating native model`() {
        val store = AppleMissingLyricsStore()
        val first = store.receive(song())
        val second = store.receive(song(translation = "您好"))
        assertEquals(first.nativeModelRevision, second.nativeModelRevision)
        assertTrue(second.presentation.revision > first.presentation.revision)
        assertFalse(store.isCurrentPresentation(first.presentation))
        assertTrue(store.isCurrentPresentation(second.presentation))
        assertEquals(second.presentation, store.receive(song(translation = "您好")).presentation)
    }

    @Test fun `clear and playback identity change invalidate queued translation event`() {
        val store = AppleMissingLyricsStore()
        val first = store.receive(song())
        store.clear("a")
        assertFalse(store.isCurrentPresentation(first.presentation))
        val second = store.receive(song())
        store.updatePlaybackIdentity(AppleMissingLyricsPlaybackIdentity("b", 2, 2))
        assertFalse(store.isCurrentPresentation(second.presentation))
    }

    @Test fun `primary fallback matrix and official late arrival use the actual selection function`() {
        for ((official, fallback, expected) in listOf(
            Triple("official", null, "official"),
            Triple(null, "fallback", "fallback"),
            Triple("official", "fallback", "official"),
            Triple(null, null, null),
        )) {
            var queried = false
            val selected = selectAppleLyricsText(official) { queried = true; fallback }
            assertEquals(expected, selected.text)
            assertEquals(official == null, queried)
            assertEquals(official == null && fallback != null, selected.fromFallback)
        }
        assertTrue(selectAppleLyricsText(null) { "fallback" }.fromFallback)
        assertFalse(selectAppleLyricsText("late official") { error("must not consult fallback") }.fromFallback)
    }

    @Test fun `translation and pronunciation availability are independent per song`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(song())
        assertTrue(store.hasTranslation("a"))
        assertFalse(store.hasPronunciation("a"))
        store.receive(Song(id = "b", lyrics = listOf(
            RichLyricLine(begin = 0, end = 1000, text = "君の名は", roma = "Kimi no na wa"),
        )))
        assertFalse(store.hasTranslation("a"))
        assertFalse(store.hasPronunciation("a"))
        assertFalse(store.hasTranslation("b"))
        assertTrue(store.hasPronunciation("b"))
    }

    @Test fun `native late arrival revokes automatic takeover without becoming a manual choice`() {
        val gate = AppleNativeLyricsTakeoverGate(clock = { 0L })
        val choices = AppleLyricsSourceSelection()
        val knowledge = AppleNativeLyricsKnowledge(10)
        choices.beginPlayback("a")
        gate.onNativeRequestStarted("a")
        gate.onNativeResult("a", hasLyrics = false)
        assertTrue(gate.decision("a").allowed)
        knowledge.remember("1", "a")
        gate.onNativeResult("a", hasLyrics = true)
        assertFalse(gate.decision("a").allowed)
        assertTrue(knowledge.contains("a", null, null))
        assertNull(choices.selected("a"))
        assertFalse(shouldRouteAppleTranslationAsMissingSupplement(true, true, null))
    }
}
