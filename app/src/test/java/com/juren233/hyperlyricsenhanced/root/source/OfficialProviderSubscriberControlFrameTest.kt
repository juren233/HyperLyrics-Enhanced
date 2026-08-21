/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderSubscriberControlFrameTest {
    @Test
    fun `plain lyric text is passed through`() {
        val decision = OfficialProviderSubscriberControlFrame.inspect("ordinary lyric")

        assertFalse(decision.consumed)
        assertNull(decision.frame)
    }

    @Test
    fun `valid next track frame is consumed and decoded`() {
        val encoded = OfficialProviderControlProtocol.encodeNextTrack(
            currentId = "2130340447",
            currentTitle = "INTERGALACTIA",
            currentArtist = "IA/KIRA",
            nextId = "next-id",
            nextTitle = "Next Song",
            nextArtist = "Next Artist",
        )

        val decision = OfficialProviderSubscriberControlFrame.inspect(encoded)

        assertTrue(decision.consumed)
        assertNotNull(decision.frame)
        assertEquals("2130340447", decision.frame?.currentId)
        assertEquals("Next Song", decision.frame?.nextTitle)
    }

    @Test
    fun `standalone central stripped separator remains a control frame`() {
        val encoded = OfficialProviderControlProtocol.encodeNextTrackClear(
            currentId = "2130340447",
            currentTitle = "INTERGALACTIA",
            currentArtist = "IA/KIRA",
        ).drop(1)

        val decision = OfficialProviderSubscriberControlFrame.inspect(encoded)

        assertTrue(decision.consumed)
        assertEquals(true, decision.frame?.clear)
    }

    @Test
    fun `malformed reserved frame is filtered instead of displayed`() {
        val malformed = OfficialProviderControlProtocol.NEXT_TRACK_PREFIX + "broken"

        val decision = OfficialProviderSubscriberControlFrame.inspect(malformed)

        assertTrue(decision.consumed)
        assertNull(decision.frame)
    }
}
