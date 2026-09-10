/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMissingLyricsStateOwnersTest {
    private fun identity(songId: String = "a", adamId: Long = 1L, queueId: Long = 2L) =
        AppleMissingLyricsPlaybackIdentity(contentSongId = songId, adamId = adamId, queueId = queueId)

    private fun key(
        revision: Long = 1L,
        identity: AppleMissingLyricsPlaybackIdentity = identity(),
    ) = AppleMissingLyricsHooks.NativeBuildKey(contentRevision = revision, identity = identity)

    @Test
    fun `native build registration de-duplicates per exact key`() {
        val state = AppleMissingLyricsNativeBuildState()
        val first = key(revision = 1L)
        assertTrue(state.begin(first))
        assertFalse(state.begin(first))
        assertTrue(state.isCurrent(first))

        // A new revision is a new build even for the same playback identity.
        val second = key(revision = 2L)
        assertTrue(state.begin(second))
        assertTrue(state.isCurrent(second))
        assertFalse(state.isCurrent(first))

        // A late build must not clear the newer key it does not own.
        state.clearIfCurrent(first)
        assertTrue(state.isCurrent(second))
        state.clearIfCurrent(second)
        assertFalse(state.isCurrent(second))
    }

    @Test
    fun `native build scope nests and unwinds through exceptions`() {
        val state = AppleMissingLyricsNativeBuildState()
        assertFalse(state.isScopeActive())
        val value = state.withinScope {
            assertTrue(state.isScopeActive())
            state.withinScope {
                assertTrue(state.isScopeActive())
                "inner"
            }
            assertTrue(state.isScopeActive())
            "outer"
        }
        assertEquals("outer", value)
        assertFalse(state.isScopeActive())
        runCatching {
            state.withinScope<Unit> { throw IllegalStateException("parse") }
        }
        assertFalse(state.isScopeActive())
    }

    @Test
    fun `takeover recheck keeps the earliest deadline and claims only its own`() {
        val rechecks = AppleMissingLyricsTakeoverRechecks()
        assertTrue(rechecks.registerIfEarlier("a", 100L))
        // A later deadline must not replace the pending earlier one.
        assertFalse(rechecks.registerIfEarlier("a", 200L))
        // An earlier deadline supersedes it.
        assertTrue(rechecks.registerIfEarlier("a", 50L))

        assertFalse(rechecks.claim("a", 100L))
        assertTrue(rechecks.claim("a", 50L))
        // Claiming consumed the registration.
        assertFalse(rechecks.claim("a", 50L))

        rechecks.registerIfEarlier("a", 300L)
        rechecks.remove("a")
        assertFalse(rechecks.claim("a", 300L))
    }

    @Test
    fun `playback item binding replaces the whole identity and item pair`() {
        val binding = AppleMissingLyricsPlaybackItemBinding()
        assertNull(binding.snapshot())

        val item = Any()
        binding.remember(identity(songId = "a"), item)
        val snapshot = binding.snapshot()!!
        assertSame(item, snapshot.item.get())
        assertEquals(identity(songId = "a"), snapshot.identity)

        binding.remember(identity(songId = "b"), Any())
        assertEquals("b", binding.snapshot()!!.identity.contentSongId)
        assertNotSame(item, binding.snapshot()!!.item.get())
    }

    @Test
    fun `availability diagnostics only reports a signature change`() {
        val diagnostics = AppleMissingLyricsAvailabilityDiagnostics()
        assertTrue(diagnostics.shouldLog("a|b"))
        assertFalse(diagnostics.shouldLog("a|b"))
        assertTrue(diagnostics.shouldLog("a|c"))
    }
}
