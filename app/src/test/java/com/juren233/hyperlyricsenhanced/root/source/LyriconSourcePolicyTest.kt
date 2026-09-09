/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconSourcePolicyTest {

    private val retainedSong = Song(
        id = "1053568848",
        name = "Against the Wind",
        artist = "G.E.M.",
        duration = 219_000L,
        lyrics = listOf(
            RichLyricLine(begin = 0L, end = 1_000L, duration = 1_000L, text = "luna beat body")
        )
    )

    private val incomingSong = retainedSong.copy(name = "一路逆风", artist = "邓紫棋")

    @Test
    fun `attempt is alive only while running ready or matched`() {
        assertFalse(
            isAppleOnlineTranslationAttemptAlive(
                attemptMatches = false,
                requestRunning = true,
                resultReady = true,
                matchedActive = true,
            )
        )
        assertTrue(
            isAppleOnlineTranslationAttemptAlive(
                attemptMatches = true,
                requestRunning = true,
                resultReady = false,
                matchedActive = false,
            )
        )
        assertTrue(
            isAppleOnlineTranslationAttemptAlive(
                attemptMatches = true,
                requestRunning = false,
                resultReady = true,
                matchedActive = false,
            )
        )
        assertTrue(
            isAppleOnlineTranslationAttemptAlive(
                attemptMatches = true,
                requestRunning = false,
                resultReady = false,
                matchedActive = true,
            )
        )
        assertFalse(
            isAppleOnlineTranslationAttemptAlive(
                attemptMatches = true,
                requestRunning = false,
                resultReady = false,
                matchedActive = false,
            )
        )
    }

    @Test
    fun `retained body inherits latest non blank display metadata`() {
        val synced = mergeRetainedSongDisplayMetadata(
            mergedSong = retainedSong,
            previousSong = retainedSong,
            incomingSong = incomingSong,
        )

        assertEquals("一路逆风", synced?.name)
        assertEquals("邓紫棋", synced?.artist)
        assertSame(retainedSong.id, synced?.id)
        assertSame(retainedSong.lyrics, synced?.lyrics)
        assertSame(retainedSong.metadata, synced?.metadata)
    }

    @Test
    fun `unchanged display metadata keeps the merged instance`() {
        assertSame(
            retainedSong,
            mergeRetainedSongDisplayMetadata(
                mergedSong = retainedSong,
                previousSong = retainedSong,
                incomingSong = retainedSong,
            )
        )
    }

    @Test
    fun `blank incoming metadata never overwrites retained song`() {
        val blankDisplay = incomingSong.copy(name = "  ", artist = " ")
        assertSame(
            retainedSong,
            mergeRetainedSongDisplayMetadata(
                mergedSong = retainedSong,
                previousSong = retainedSong,
                incomingSong = blankDisplay,
            )
        )
    }

    @Test
    fun `blank name still syncs non blank artist per field`() {
        val synced = mergeRetainedSongDisplayMetadata(
            mergedSong = retainedSong,
            previousSong = retainedSong,
            incomingSong = incomingSong.copy(name = "  "),
        )

        assertEquals("Against the Wind", synced?.name)
        assertEquals("邓紫棋", synced?.artist)
    }

    @Test
    fun `incoming based merge result is returned untouched`() {
        val mergedFromIncoming = incomingSong.copy(name = "merged")
        assertSame(
            mergedFromIncoming,
            mergeRetainedSongDisplayMetadata(
                mergedSong = mergedFromIncoming,
                previousSong = retainedSong,
                incomingSong = incomingSong,
            )
        )
    }

    @Test
    fun `null incoming keeps the merged song`() {
        assertSame(
            retainedSong,
            mergeRetainedSongDisplayMetadata(
                mergedSong = retainedSong,
                previousSong = retainedSong,
                incomingSong = null,
            )
        )
    }
}
