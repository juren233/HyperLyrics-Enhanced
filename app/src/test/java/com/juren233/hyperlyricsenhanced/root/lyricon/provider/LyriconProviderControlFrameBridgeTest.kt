/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.lyricon.provider

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconProviderControlFrameBridgeTest {
    @Test
    fun `next-track frames use the independent reconnect channel`() {
        val frame = OfficialProviderControlProtocol.encodeNextTrack(
            currentId = "current",
            currentTitle = "Current",
            currentArtist = "Artist",
            nextId = "next",
            nextTitle = "Next",
            nextArtist = "Artist",
        )

        assertTrue(LyriconProviderControlFrameBridge.shouldUseIndependentChannel(frame))
        assertTrue(
            LyriconProviderControlFrameBridge.shouldUseIndependentChannel(
                OfficialProviderControlProtocol.NEXT_TRACK_PREFIX + "malformed",
            ),
        )
        assertFalse(LyriconProviderControlFrameBridge.shouldUseIndependentChannel("plain lyric"))
        assertFalse(LyriconProviderControlFrameBridge.shouldUseIndependentChannel(null))
    }

    @Test
    fun `runtime identifiers match the provider binary`() {
        val type = Class.forName(
            LyriconProviderControlFrameBridge.CACHED_REMOTE_PLAYER_CLASS_NAME,
            false,
            javaClass.classLoader,
        )

        assertEquals(
            java.lang.Boolean.TYPE,
            type.getDeclaredMethod(
                LyriconProviderControlFrameBridge.SEND_TEXT_METHOD_NAME,
                String::class.java,
            ).returnType,
        )
        assertEquals(
            java.lang.Void.TYPE,
            type.getDeclaredMethod(
                LyriconProviderControlFrameBridge.SYNC_METHOD_NAME,
            ).returnType,
        )
        assertEquals(
            "io.github.proify.lyricon.provider.RemotePlayer",
            type.getDeclaredMethod(
                LyriconProviderControlFrameBridge.PLAYER_GETTER_METHOD_NAME,
            ).returnType.name,
        )
    }
}
