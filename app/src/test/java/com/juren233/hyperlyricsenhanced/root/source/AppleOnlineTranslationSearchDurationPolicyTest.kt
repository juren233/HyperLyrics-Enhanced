/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOnlineTranslationSearchDurationPolicyTest {
    @Test
    fun `uses matching MediaSession duration instead of Apple lyric duration`() {
        val resolution = AppleOnlineTranslationSearchDurationPolicy.resolve(
            song = song(),
            media = media(
                title = "レプリカント",
                artist = "ヨルシカ",
                durationMs = 217_000L,
            ),
        )

        assertEquals(217_000L, resolution.durationMs)
        assertTrue(resolution.mediaIdentityMatched)
    }

    @Test
    fun `does not use stale MediaSession duration from another title`() {
        val resolution = AppleOnlineTranslationSearchDurationPolicy.resolve(
            song = song(),
            media = media(
                title = "ただ君に晴れ",
                artist = "ヨルシカ",
                durationMs = 198_000L,
            ),
        )

        assertEquals(205_486L, resolution.durationMs)
        assertFalse(resolution.mediaIdentityMatched)
    }

    @Test
    fun `does not accept a same-title MediaSession item from the wrong artist`() {
        val resolution = AppleOnlineTranslationSearchDurationPolicy.resolve(
            song = song(),
            media = media(
                title = "レプリカント",
                artist = "nqrse",
                durationMs = 205_000L,
            ),
        )

        assertEquals(205_486L, resolution.durationMs)
        assertFalse(resolution.mediaIdentityMatched)
    }

    @Test
    fun `accepts MediaSession identity matching Apple original metadata`() {
        val resolution = AppleOnlineTranslationSearchDurationPolicy.resolve(
            song = song(
                title = "Replicant",
                artist = "Yorushika",
                originalTitle = "レプリカント",
                originalArtist = "ヨルシカ",
            ),
            media = media(
                title = "レプリカント",
                artist = "ヨルシカ",
                durationMs = 217_000L,
            ),
        )

        assertEquals(217_000L, resolution.durationMs)
        assertTrue(resolution.mediaIdentityMatched)
    }

    private fun song(
        title: String = "レプリカント",
        artist: String = "ヨルシカ",
        originalTitle: String? = null,
        originalArtist: String? = null,
    ) = Song(
        id = "1519740249",
        name = title,
        artist = artist,
        duration = 205_486L,
        metadata = if (originalTitle != null || originalArtist != null) {
            lyricMetadataOf(
                LyricMetadataKeys.APPLE_ORIGINAL_TITLE to originalTitle,
                LyricMetadataKeys.APPLE_ORIGINAL_ARTIST to originalArtist,
            )
        } else {
            null
        },
    )

    private fun media(
        title: String,
        artist: String,
        durationMs: Long,
    ) = AppleOnlineTranslationSearchDurationPolicy.MediaSnapshot(
        title = title,
        artist = artist,
        durationMs = durationMs,
    )
}
