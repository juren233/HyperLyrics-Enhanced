/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderControlProtocolTest {
    @Test
    fun `round trips next track text without delimiter collisions`() {
        val encoded = OfficialProviderControlProtocol.encodeNextTrack(
            currentId = "123",
            currentTitle = "Current | Song",
            currentArtist = "歌手 A",
            nextId = "456",
            nextTitle = "Next | Song",
            nextArtist = "歌手 B",
            nextAlbum = "Album | Name",
            nextDurationMs = 234_567L,
        )

        val decoded = OfficialProviderControlProtocol.decodeNextTrack(encoded)!!
        assertFalse(decoded.clear)
        assertEquals("Current | Song", decoded.currentTitle)
        assertEquals("歌手 A", decoded.currentArtist)
        assertEquals("456", decoded.nextId)
        assertEquals("Next | Song", decoded.nextTitle)
        assertEquals("Album | Name", decoded.nextAlbum)
        assertEquals(234_567L, decoded.nextDurationMs)
    }

    @Test
    fun `clear frame remains reserved and decodes as clear`() {
        val encoded = OfficialProviderControlProtocol.encodeNextTrackClear("123", "Song", "Artist")

        assertTrue(OfficialProviderControlProtocol.isReservedFrame(encoded))
        assertTrue(OfficialProviderControlProtocol.decodeNextTrack(encoded)!!.clear)
    }

    @Test
    fun `ordinary text and malformed reserved frames are rejected`() {
        assertFalse(OfficialProviderControlProtocol.isReservedFrame("plain lyric"))
        assertNull(OfficialProviderControlProtocol.decodeNextTrack("plain lyric"))
        assertNull(
            OfficialProviderControlProtocol.decodeNextTrack(
                OfficialProviderControlProtocol.NEXT_TRACK_PREFIX + "U|broken",
            )
        )
    }
}
