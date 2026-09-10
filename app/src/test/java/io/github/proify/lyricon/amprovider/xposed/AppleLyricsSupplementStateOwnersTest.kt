/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsSupplementStateOwnersTest {
    private fun context(songId: String? = "a", languages: List<String> = emptyList()) =
        ApplePronunciationContext(songId = songId, pronunciationLanguages = languages)

    @Test
    fun `install dedup reports the first registration only and stays per family`() {
        val family = AppleLyricsMethodInstallDedup()
        val method = String::class.java.getDeclaredMethod("length")
        assertTrue(family.markInstalled(method))
        assertFalse(family.markInstalled(method))

        // A different family must not observe the other family's registration.
        val other = AppleLyricsMethodInstallDedup()
        assertTrue(other.markInstalled(method))
    }

    @Test
    fun `languages map is keyed by song and returns the remembered list`() {
        val state = AppleLyricsPronunciationState()
        assertNull(state.languages("a"))
        state.rememberLanguages("a", listOf("ja", "en"))
        assertEquals(listOf("ja", "en"), state.languages("a"))
        assertNull(state.languages("b"))
    }

    @Test
    fun `word render context nests and unwinds like a thread-local stack`() {
        val state = AppleLyricsPronunciationState()
        assertNull(state.currentWordRenderContext())
        val outer = wordContext("outer")
        val inner = wordContext("inner")
        state.pushWordRenderContext(outer)
        assertSame(outer, state.currentWordRenderContext())
        state.pushWordRenderContext(inner)
        assertSame(inner, state.currentWordRenderContext())
        state.popWordRenderContext()
        assertSame(outer, state.currentWordRenderContext())
        state.popWordRenderContext()
        assertNull(state.currentWordRenderContext())
        // Popping an empty stack is a no-op, not an error.
        state.popWordRenderContext()
        assertNull(state.currentWordRenderContext())
    }

    @Test
    fun `render plan is one-shot per vector identity`() {
        val state = AppleLyricsPronunciationState()
        val vector = Any()
        state.registerRenderPlan(vector, ApplePronunciationRenderPlan("ka"))
        assertEquals("ka", state.consumeRenderPlan(vector)?.pronunciation)
        // The first consumer wins; the plan is not reusable.
        assertNull(state.consumeRenderPlan(vector))

        state.registerRenderPlan(vector, ApplePronunciationRenderPlan("mi"))
        state.clearRenderPlans()
        assertNull(state.consumeRenderPlan(vector))
    }

    @Test
    fun `lyric object context is addressed by identity`() {
        class Line {
            override fun equals(other: Any?) = other is Line
            override fun hashCode() = 1
        }
        val state = AppleLyricsPronunciationState()
        val first = Line()
        val second = Line()
        state.putContext(first, context(songId = "first"))
        state.putContext(second, context(songId = "second"))
        // Equal-but-distinct native lines must not share a context.
        assertEquals("first", state.contextFor(first)?.songId)
        assertEquals("second", state.contextFor(second)?.songId)
    }

    @Test
    fun `view tracking de-duplicates each identity set independently`() {
        val tracking = AppleLyricsViewTracking()
        val view = Any()
        assertTrue(tracking.markLoadingViewSuppressedIfNew(view))
        assertFalse(tracking.markLoadingViewSuppressedIfNew(view))
        // The button set is a separate identity set.
        assertTrue(tracking.markTranslationButtonForcedIfNew(view))
        assertTrue(tracking.markTranslationButtonForcedIfNew(Any()))
    }

    @Test
    fun `pronunciation diagnostics de-duplicate per stream`() {
        val diagnostics = AppleLyricsPronunciationDiagnostics()
        assertTrue(diagnostics.markSongSnapshotLogged("song|ja|1|1|0|1"))
        assertFalse(diagnostics.markSongSnapshotLogged("song|ja|1|1|0|1"))

        assertTrue(diagnostics.markRuntimeReported("a", "stage"))
        assertFalse(diagnostics.markRuntimeReported("a", "stage"))
        assertTrue(diagnostics.markRuntimeReported("b", "stage"))

        assertTrue(diagnostics.markBindingReported("a", "line"))
        assertFalse(diagnostics.markBindingReported("a", "line"))
    }

    private fun wordContext(label: String) = ApplePronunciationWordRenderContext(
        displayTextByWord = emptyMap(),
        wordIdMethod = label,
        beginMethod = "",
        endMethod = "",
    )
}
