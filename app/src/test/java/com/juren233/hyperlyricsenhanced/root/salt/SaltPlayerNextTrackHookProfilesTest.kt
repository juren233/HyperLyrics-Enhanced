package com.juren233.hyperlyricsenhanced.root.salt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaltPlayerNextTrackHookProfilesTest {
    @Test
    fun `native Lyricon boundary starts at Salt Player 12 2 0`() {
        assertFalse(SaltPlayerNextTrackHookProfiles.usesNativeLyricon("12.1.9"))
        assertTrue(SaltPlayerNextTrackHookProfiles.usesNativeLyricon("12.2.0"))
        assertTrue(SaltPlayerNextTrackHookProfiles.usesNativeLyricon("12.2.0-beta02"))
    }

    @Test
    fun `controller query keeps stable runtime contracts`() {
        val query = SaltPlayerNextTrackHookProfiles.controllerQuery(
            SaltPlayerNextTrackHookProfiles.resolve("12.2.1"),
        )
        assertEquals(SaltPlayerNextTrackHookProfiles.CACHE_KEY, query.cacheKey)
        assertEquals("com.salt.music.service.", query.declaringClassNamePrefix)
        assertEquals(
            listOf("com.salt.music.data.entry.Song", "long", "long", "java.lang.Long"),
            query.parameterTypeNames,
        )
        assertEquals("void", query.returnTypeName)
        assertEquals(true, query.isStatic)
    }

    @Test
    fun `state holder discovery uses getValue semantics instead of Kotlin class name`() {
        assertTrue(SaltPlayerNextTrackResolver.hasStateHolderContract(ObfuscatedStateHolder::class.java))
        assertFalse(SaltPlayerNextTrackResolver.hasStateHolderContract(NotAStateHolder::class.java))
    }

    private class ObfuscatedStateHolder {
        fun getValue(): Any = Any()
    }

    private class NotAStateHolder {
        fun value(): Any = Any()
    }
}
