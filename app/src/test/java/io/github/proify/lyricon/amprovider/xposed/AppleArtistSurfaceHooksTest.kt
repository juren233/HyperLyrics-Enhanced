/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleArtistSurfaceHooksTest {

    @Test
    fun `artist profile controller is recognized behind the epoxy adapter`() {
        assertTrue(
            isArtistProfileControllerClassNames(
                listOf(
                    "com.apple.android.music.profiles.ArtistEpoxyController",
                    "com.apple.android.music.profiles.BaseProfileEpoxyController",
                )
            )
        )
        assertFalse(
            isArtistProfileControllerClassNames(
                listOf("com.airbnb.epoxy.s", "com.airbnb.epoxy.f")
            )
        )
    }

    @Test
    fun `artist header invalidates an applied alias after Apple renders its old title`() {
        val alias = artistAlias()
        assertTrue(
            shouldInvalidateArtistHeaderAppliedAlias(
                appliedAlias = alias,
                effectiveAlias = alias,
                pendingAlias = null,
                expectedTitle = "宇多田ヒカル",
                renderedTexts = listOf("Utada"),
            )
        )
    }

    @Test
    fun `artist header keeps alias state when rendered or refresh is already pending`() {
        val alias = artistAlias()
        assertFalse(
            shouldInvalidateArtistHeaderAppliedAlias(
                alias,
                alias,
                null,
                "宇多田ヒカル",
                listOf("宇多田ヒカル"),
            )
        )
        assertFalse(
            shouldInvalidateArtistHeaderAppliedAlias(
                alias,
                alias,
                alias,
                "宇多田ヒカル",
                listOf("Utada"),
            )
        )
        assertFalse(
            shouldInvalidateArtistHeaderAppliedAlias(
                alias,
                alias.copy(artist = "Utada"),
                null,
                "Utada",
                listOf("Utada"),
            )
        )
    }

    @Test
    fun `artist profile capture accepts only catalog songs from top songs`() {
        assertEquals("1505498782", artistProfileTopSongMediaId("top-songs", "1505498782"))
        assertNull(artistProfileTopSongMediaId("albums", "1505498782"))
        assertNull(artistProfileTopSongMediaId("top-songs", "not-a-catalog-id"))
        assertNull(artistProfileTopSongMediaId("top-songs", null))
    }

    @Test
    fun `artist profile infers its artist id only for an exact known solo credit`() {
        assertEquals(
            "18756224",
            artistProfileFallbackArtistId(
                profileArtistId = "18756224",
                existingArtistIds = emptyList(),
                songArtistCredit = "Utada",
                profileArtistCredits = listOf("宇多田ヒカル", "Utada"),
            ),
        )
    }

    @Test
    fun `artist profile never infers its artist id for collaborations or existing ids`() {
        assertNull(
            artistProfileFallbackArtistId(
                "18756224",
                emptyList(),
                "Charlie Puth、Utada",
                listOf("Utada"),
            )
        )
        assertNull(
            artistProfileFallbackArtistId(
                "18756224",
                listOf("111", "18756224"),
                "Utada",
                listOf("Utada"),
            )
        )
        assertNull(
            artistProfileFallbackArtistId(
                "18756224",
                emptyList(),
                "宇多田ヒカル feat. 椎名林檎",
                listOf("宇多田ヒカル"),
            )
        )
    }

    @Test
    fun `artist profile subtitle replaces only its own credit and preserves suffixes`() {
        assertEquals(
            "藤井 風 · 2023年",
            artistProfileSubtitleWithArtist("藤井风 · 2023年", "藤井风", "藤井 風"),
        )
        assertEquals(
            "2023年 · 流行乐",
            artistProfileSubtitleWithArtist("2023年 · 流行乐", "藤井风", "藤井 風"),
        )
        assertEquals(
            "Charlie Puth、宇多田ヒカル · 2022年",
            artistProfileSubtitleWithArtist(
                "Charlie Puth、Utada · 2022年",
                "Charlie Puth、Utada",
                "Charlie Puth、宇多田ヒカル",
            ),
        )
    }

    private fun artistAlias() = AppliedMetadataAlias(
        mediaId = "18756224",
        alias = AppleInternalCatalogResolver.Alias(
            title = "宇多田ヒカル",
            artist = "宇多田ヒカル",
            album = "",
            language = "ja-JP",
        ),
    )
}
