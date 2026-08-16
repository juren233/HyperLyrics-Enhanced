/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.lyrics

import io.github.proify.lyricon.amprovider.xposed.belongsToCurrentLyricsPage
import io.github.proify.lyricon.amprovider.xposed.selectLyricsViewModelPlaybackItem
import io.github.proify.lyricon.amprovider.xposed.shouldKeepAppleLyricsScrollSnapshot
import io.github.proify.lyricon.amprovider.xposed.shouldRouteAppleTranslationAsMissingSupplement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsPresentationPolicyTest {
    private open class LyricsPlaybackItem
    private class CurrentPlaybackItem : LyricsPlaybackItem()
    private class PreviousPlaybackItem : LyricsPlaybackItem()
    private class StoreMediaItem

    @Test
    fun `selects a compatible registered playback item instead of queue media item`() {
        val queueMediaItem = StoreMediaItem()
        val currentItem = CurrentPlaybackItem()
        val selected = selectLyricsViewModelPlaybackItem(
            expectedSongId = "1810905308",
            expectedType = LyricsPlaybackItem::class.java,
            candidates = listOf(queueMediaItem, currentItem),
            registeredSongId = { item ->
                when (item) {
                    queueMediaItem, currentItem -> "1810905308"
                    else -> null
                }
            },
            runtimeSongId = { null },
        )

        assertSame(currentItem, selected)
    }

    @Test
    fun `rejects compatible playback item belonging to previous song`() {
        val previousItem = PreviousPlaybackItem()
        val selected = selectLyricsViewModelPlaybackItem(
            expectedSongId = "1810905308",
            expectedType = LyricsPlaybackItem::class.java,
            candidates = listOf(previousItem),
            registeredSongId = { "1440818674" },
            runtimeSongId = { null },
        )

        assertNull(selected)
    }

    @Test
    fun `accepts current queue song load while visible page id is stale`() {
        assertTrue(
            belongsToCurrentLyricsPage(
                loadedSongId = "1720737136",
                visibleSongId = "1768090627",
                queueSongId = "1720737136",
            )
        )
    }

    @Test
    fun `accepts load matching visible page song`() {
        assertTrue(
            belongsToCurrentLyricsPage(
                loadedSongId = "1768090627",
                visibleSongId = "1768090627",
                queueSongId = "1720737136",
            )
        )
    }

    @Test
    fun `rejects historical load matching neither visible nor queue song`() {
        assertFalse(
            belongsToCurrentLyricsPage(
                loadedSongId = "1440818674",
                visibleSongId = "1768090627",
                queueSongId = "1720737136",
            )
        )
    }

    @Test
    fun `accepts unknown loaded song when identities are unavailable`() {
        assertTrue(
            belongsToCurrentLyricsPage(
                loadedSongId = null,
                visibleSongId = null,
                queueSongId = null,
            )
        )
        assertTrue(
            belongsToCurrentLyricsPage(
                loadedSongId = "1440818674",
                visibleSongId = null,
                queueSongId = null,
            )
        )
    }

    @Test
    fun `routes marked payload as native translation when native lyrics are confirmed`() {
        assertFalse(
            shouldRouteAppleTranslationAsMissingSupplement(
                markedAsSupplement = true,
                knownNativeLyrics = true,
                visiblePageIsSupplement = null,
            )
        )
    }

    @Test
    fun `routes marked payload as native translation when visible page is native`() {
        assertFalse(
            shouldRouteAppleTranslationAsMissingSupplement(
                markedAsSupplement = true,
                knownNativeLyrics = false,
                visiblePageIsSupplement = false,
            )
        )
    }

    @Test
    fun `routes marked payload as supplement when page is not native`() {
        assertTrue(
            shouldRouteAppleTranslationAsMissingSupplement(
                markedAsSupplement = true,
                knownNativeLyrics = false,
                visiblePageIsSupplement = null,
            )
        )
        assertTrue(
            shouldRouteAppleTranslationAsMissingSupplement(
                markedAsSupplement = true,
                knownNativeLyrics = false,
                visiblePageIsSupplement = true,
            )
        )
    }

    @Test
    fun `unmarked payload always uses native translation path`() {
        assertFalse(
            shouldRouteAppleTranslationAsMissingSupplement(
                markedAsSupplement = false,
                knownNativeLyrics = false,
                visiblePageIsSupplement = true,
            )
        )
    }

    @Test
    fun `presentation reset to top does not overwrite a deeper scroll snapshot`() {
        assertTrue(
            shouldKeepAppleLyricsScrollSnapshot(
                existingPosition = 46,
                capturedPosition = 0,
                presentationInFlight = true,
            )
        )
    }

    @Test
    fun `real idle scroll to top replaces the previous snapshot`() {
        assertFalse(
            shouldKeepAppleLyricsScrollSnapshot(
                existingPosition = 46,
                capturedPosition = 0,
                presentationInFlight = false,
            )
        )
    }
}
