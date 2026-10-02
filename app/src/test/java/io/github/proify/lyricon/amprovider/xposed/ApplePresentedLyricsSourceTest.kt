/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplePresentedLyricsSourceTest {
    private class Pointer(val address: Long)

    private val apple = Pointer(101)
    private val lb = Pointer(202)

    private fun displayedSource(
        pointer: Pointer?,
        requestedSong: String? = "a",
        modelSong: String? = "a",
        hasLines: Boolean = true,
        known: List<Any> = listOf(lb),
        current: Pointer? = lb,
        storedSource: String? = "LB",
        confirmedSource: String? = "LB",
    ): String? = effectiveOnlineSourceSelection(
        storedSource = storedSource,
        confirmedSource = confirmedSource,
        onlineContentConsumed = storedSource != null,
        isLyricsSource = true,
        presentedLyricsSource = presentedLyricsSource(
            requestedSongId = requestedSong,
            modelSongId = modelSong,
            hasLines = hasLines,
            pointer = pointer,
            knownSupplementPointers = known,
            currentSupplementPointer = current,
            supplementSource = storedSource,
            nativeAddress = { (it as Pointer).address },
        ),
    )

    @Test fun `native model wins over both a cached LB candidate and stale successful LB request`() {
        assertEquals("原生歌词", missingLyricsSourceMenuLabel(displayedSource(apple)))
    }

    @Test fun `native only does not need a supplement store to identify its source`() {
        assertEquals("APPLE", displayedSource(apple, known = emptyList(), current = null,
            storedSource = null, confirmedSource = null))
    }

    @Test fun `visible LB model wins over a stale successful Apple request`() {
        assertEquals("LB歌词", missingLyricsSourceMenuLabel(displayedSource(lb, confirmedSource = "APPLE")))
    }

    @Test fun `missing or empty visible model never uses cached or confirmed LB as its label`() {
        assertNull(displayedSource(null))
        assertNull(displayedSource(apple, hasLines = false))
        assertEquals("歌词来源", missingLyricsSourceMenuLabel(displayedSource(null)))
    }

    @Test fun `late native presentation changes the label without restarting or clearing LB`() {
        assertEquals("LB", displayedSource(lb))
        assertEquals("APPLE", displayedSource(apple))
        assertEquals("LB", displayedSource(lb))
    }

    @Test fun `a b a without opening the middle menu cannot resurrect the old LB confirmation`() {
        assertEquals("LB", displayedSource(lb))
        assertEquals("APPLE", displayedSource(apple, requestedSong = "b", modelSong = "b"))
        assertEquals("APPLE", displayedSource(apple))
    }

    @Test fun `old song or missing identity cannot supply the new songs source label`() {
        assertNull(displayedSource(lb, requestedSong = "b"))
        assertNull(displayedSource(apple, modelSong = null))
        assertNull(displayedSource(lb, requestedSong = " "))
    }

    @Test fun `retained supplement cannot inherit a new models source or be called native`() {
        val next = Pointer(303)
        assertNull(displayedSource(lb, known = listOf(lb, next), current = next, storedSource = "QM"))
        assertNull(displayedSource(lb, current = null))
        assertEquals("QM", displayedSource(next, known = listOf(lb, next), current = next,
            storedSource = "QM"))
    }

    @Test fun `native pointer wrappers match by verified nonzero address and never by zero`() {
        assertEquals("LB", displayedSource(Pointer(202)))
        val zero = Pointer(0)
        assertEquals("APPLE", displayedSource(Pointer(0), known = listOf(zero), current = zero))
        assertEquals("LB", displayedSource(zero, known = listOf(zero), current = zero))
    }

    @Test fun `supplement with unknown source does not masquerade as Apple or LB`() {
        assertNull(displayedSource(lb, storedSource = null))
        assertNull(displayedSource(lb, storedSource = "APPLE"))
    }

    @Test fun `translation and pronunciation still require actual online consumption`() {
        assertNull(effectiveOnlineSourceSelection("NE", "QM", false,
            presentedLyricsSource = "APPLE"))
        assertEquals("QM", effectiveOnlineSourceSelection("NE", "QM", true,
            presentedLyricsSource = "APPLE"))
    }
}
