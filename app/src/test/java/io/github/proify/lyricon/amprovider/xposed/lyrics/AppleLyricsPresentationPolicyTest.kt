/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.lyrics

import io.github.proify.lyricon.amprovider.xposed.belongsToCurrentLyricsPage
import io.github.proify.lyricon.amprovider.xposed.AppleLyricsRestoreAnchor
import io.github.proify.lyricon.amprovider.xposed.selectLyricsViewModelPlaybackItem
import io.github.proify.lyricon.amprovider.xposed.selectAppleLyricsPlaybackAdapterPosition
import io.github.proify.lyricon.amprovider.xposed.shouldKeepAppleLyricsScrollSnapshot
import io.github.proify.lyricon.amprovider.xposed.shouldRouteAppleTranslationAsMissingSupplement
import io.github.proify.lyricon.amprovider.xposed.selectAppleLyricsRestoreAnchor
import io.github.proify.lyricon.amprovider.xposed.visibleAdapterRange
import org.junit.Assert.assertEquals
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

    @Test
    fun `restore anchor uses current adapter active row and preserves its screen offset`() {
        assertEquals(
            AppleLyricsRestoreAnchor(position = 17, offset = 182, activePosition = 17),
            selectAppleLyricsRestoreAnchor(
                savedPosition = 21,
                savedOffset = -354,
                savedActivePosition = 22,
                savedActiveOffset = 182,
                currentActivePositions = setOf(17),
                itemCount = 61,
            ),
        )
    }

    @Test
    fun `restore anchor preserves active row distance when its child offset is unavailable`() {
        assertEquals(
            AppleLyricsRestoreAnchor(position = 16, offset = -354, activePosition = 17),
            selectAppleLyricsRestoreAnchor(
                savedPosition = 21,
                savedOffset = -354,
                savedActivePosition = 22,
                savedActiveOffset = null,
                currentActivePositions = setOf(17),
                itemCount = 61,
            ),
        )
    }

    @Test
    fun `restore anchor uses playback mapped row before new adapter active state exists`() {
        assertEquals(
            AppleLyricsRestoreAnchor(position = 31, offset = 182, activePosition = 31),
            selectAppleLyricsRestoreAnchor(
                savedPosition = 23,
                savedOffset = -732,
                savedActivePosition = 22,
                savedActiveOffset = 182,
                currentActivePositions = emptySet(),
                itemCount = 74,
                playbackMappedPosition = 31,
            ),
        )
    }

    @Test
    fun `restore anchor prefers current adapter active row over playback mapping`() {
        assertEquals(
            AppleLyricsRestoreAnchor(position = 25, offset = 182, activePosition = 25),
            selectAppleLyricsRestoreAnchor(
                savedPosition = 23,
                savedOffset = -3347,
                savedActivePosition = 31,
                savedActiveOffset = 182,
                currentActivePositions = setOf(25),
                itemCount = 61,
                playbackMappedPosition = 26,
            ),
        )
    }

    @Test
    fun `restore anchor clamps saved first row when active adapter row is unavailable`() {
        assertEquals(
            AppleLyricsRestoreAnchor(position = 3, offset = -354, activePosition = null),
            selectAppleLyricsRestoreAnchor(
                savedPosition = 46,
                savedOffset = -354,
                savedActivePosition = null,
                savedActiveOffset = null,
                currentActivePositions = emptySet(),
                itemCount = 4,
            ),
        )
    }

    @Test
    fun `restore anchor returns null while adapter has no rows`() {
        assertNull(
            selectAppleLyricsRestoreAnchor(
                savedPosition = 46,
                savedOffset = -354,
                savedActivePosition = 47,
                savedActiveOffset = 182,
                currentActivePositions = setOf(2),
                itemCount = 0,
            ),
        )
    }

    @Test
    fun `restore anchor clamps playback mapping to adapter boundary`() {
        assertEquals(
            AppleLyricsRestoreAnchor(position = 3, offset = 182, activePosition = 3),
            selectAppleLyricsRestoreAnchor(
                savedPosition = 1,
                savedOffset = -354,
                savedActivePosition = 2,
                savedActiveOffset = 182,
                currentActivePositions = emptySet(),
                itemCount = 4,
                playbackMappedPosition = 73,
            ),
        )
    }

    @Test
    fun `playback mapping selects the latest begun logical line`() {
        assertEquals(
            1,
            selectAppleLyricsPlaybackAdapterPosition(
                lineBeginsMs = listOf(0L, 410L, 2869L, 5328L),
                playbackPositionMs = 2500L,
                itemCount = 5,
            ),
        )
        assertNull(
            selectAppleLyricsPlaybackAdapterPosition(
                lineBeginsMs = listOf(353L, 6520L),
                playbackPositionMs = 100L,
                itemCount = 3,
            ),
        )
    }

    @Test
    fun `visible adapter range ignores invalid child positions`() {
        assertEquals(4..8, visibleAdapterRange(listOf(-1, 8, 4, 6)))
        assertNull(visibleAdapterRange(listOf(-1, -2)))
    }

    @Test
    fun `belongsToCurrentLyricsPage allows same song and queue transition`() {
        assertTrue(
            belongsToCurrentLyricsPage(
                loadedSongId = "1720737136",
                visibleSongId = "1720737136",
                queueSongId = "1720737136",
            )
        )
        assertTrue(
            belongsToCurrentLyricsPage(
                loadedSongId = "1720737136",
                visibleSongId = "1768090627",
                queueSongId = "1720737136",
            )
        )
    }
}
