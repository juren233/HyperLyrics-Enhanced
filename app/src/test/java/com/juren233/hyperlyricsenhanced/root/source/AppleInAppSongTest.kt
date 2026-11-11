/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleInAppSongTest {
    private val line = RichLyricLine(begin = 0L, end = 1_000L, duration = 1_000L, text = "空の青さを知る人よ")

    private fun song(
        id: String = "1455151646",
        name: String? = "Her Blue Sky",
        lyrics: List<RichLyricLine>? = listOf(line),
    ) = Song(id = id, name = name, artist = "Aimyon", duration = 300_000L, lyrics = lyrics)

    private val hasNative: (Song?) -> Boolean = { it != null && !it.lyrics.isNullOrEmpty() && !isSupplement(it) }
    private val supplementFlag: (Song?) -> Boolean = { isSupplement(it) }

    private fun isSupplement(song: Song?): Boolean =
        song?.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT) == "true"

    private fun supplement(source: String = "LB") = song(name = "Her Blue Sky (supplement)").copy(
        metadata = lyricMetadataOf(
            LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
            LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to source,
        ),
    )

    private fun confirmedNative() = song().copy(
        metadata = lyricMetadataOf(LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED to "true"),
    )

    private fun AppleInAppSong.acceptSong(incoming: Song?, native: Boolean = true) =
        accept(incoming, { native && hasNative(it) }, supplementFlag)

    private fun selection() = AppleManualSourceRequestState.Selection(
        translationSource = Source.KUGOU,
        pronunciationNativeOnly = true,
        sourceStatusSongId = "1455151646",
    )

    @Test
    fun `rejected direct song is stored and found only by its own id`() {
        val slot = AppleInAppSong()
        val accepted = slot.acceptSong(song())

        assertEquals(AppleInAppSong.Update.NEW_TRACK, accepted.update)
        assertEquals("1455151646", slot.songFor("1455151646")?.id)
        assertNull(slot.songFor("other"))
        assertNull(slot.songFor(null))
        assertNull(slot.songFor(" "))
        assertTrue(slot.hasNativeLyrics())
    }

    @Test
    fun `an empty direct song clears the slot`() {
        val slot = AppleInAppSong()
        slot.acceptSong(song())

        assertEquals(AppleInAppSong.Update.CLEARED, slot.acceptSong(null).update)
        assertNull(slot.song())
    }

    @Test
    fun `same track update keeps the manual selection and the published result`() {
        val slot = AppleInAppSong()
        slot.acceptSong(song())
        slot.target.manual.restoreSelection(selection())
        val published = song(name = "published")
        slot.recordPublished(published)

        val accepted = slot.acceptSong(song(name = "Her Blue Sky (updated)"))

        assertEquals(AppleInAppSong.Update.SAME_TRACK, accepted.update)
        assertEquals("Her Blue Sky (updated)", slot.song()?.name)
        assertEquals(selection(), slot.target.manual.selection())
        assertSame(published, slot.publishedSong())
    }

    @Test
    fun `same track empty lyrics never downgrade the stored lyrics`() {
        val slot = AppleInAppSong()
        slot.acceptSong(song())

        slot.acceptSong(song(name = "Her Blue Sky (late title)", lyrics = emptyList()))

        assertEquals(1, slot.song()?.lyrics.orEmpty().size)
        assertEquals("Her Blue Sky (late title)", slot.song()?.name)
        assertTrue(slot.hasNativeLyrics())
    }

    @Test
    fun `a different track clears manual selection published result and the running search`() {
        val slot = AppleInAppSong()
        slot.acceptSong(song())
        slot.target.manual.restoreSelection(selection())
        slot.recordPublished(song())
        val generation = slot.target.request.begin("attempt", allowRestart = true)

        val accepted = slot.acceptSong(song(id = "next", name = "Next"))

        assertEquals(AppleInAppSong.Update.NEW_TRACK, accepted.update)
        assertTrue(slot.target.manual.selection().isEmpty)
        assertNull(slot.publishedSong())
        assertNotEquals(generation, slot.target.request.snapshot().generation)
        assertNull(slot.target.request.snapshot().attempt)
    }

    @Test
    fun `original metadata change on the same track is reported`() {
        val slot = AppleInAppSong()
        slot.acceptSong(song())

        val unchanged = slot.acceptSong(song())
        assertFalse(unchanged.originalMetadataChanged)

        val changed = slot.acceptSong(
            song().copy(
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.APPLE_ORIGINAL_TITLE to "空の青さを知る人よ",
                ),
            ),
        )
        assertTrue(changed.originalMetadataChanged)
    }

    @Test
    fun `retired apple song carries its manual selection into the slot`() {
        val slot = AppleInAppSong()
        slot.adopt(song(), song(), true, null, selection())

        assertEquals("1455151646", slot.song()?.id)
        assertEquals(selection(), slot.target.manual.selection())
        assertNull(slot.publishedSong())
    }

    @Test
    fun `claiming the same song hands back the selection and empties the slot`() {
        val slot = AppleInAppSong()
        slot.adopt(song(), song(), true, null, selection())

        val claimed = slot.claimFor(song(name = "Her Blue Sky (central)"), supplementFlag)?.selection

        assertEquals(selection(), claimed)
        assertNull(slot.song())
        assertTrue(slot.target.manual.selection().isEmpty)
    }

    @Test
    fun `claiming a different song voids the slot and returns nothing`() {
        val slot = AppleInAppSong()
        slot.adopt(song(), song(), true, null, selection())

        assertNull(slot.claimFor(song(id = "next", name = "Next"), supplementFlag))
        assertNull(slot.song())
        assertTrue(slot.target.manual.selection().isEmpty)
    }

    @Test
    fun `claiming the same song without a selection returns nothing and still empties the slot`() {
        val slot = AppleInAppSong()
        slot.acceptSong(song())

        assertNull(slot.claimFor(song(), supplementFlag))
        assertNull(slot.song())
    }

    @Test
    fun `claiming from an empty slot or with an empty song is harmless`() {
        val slot = AppleInAppSong()
        assertNull(slot.claimFor(song(), supplementFlag))

        slot.adopt(song(), song(), true, null, selection())
        assertNull(slot.claimFor(null, supplementFlag))
        assertNull(slot.song())
    }

    @Test
    fun `in-app search slot is independent of the active player's search slot`() {
        val slot = AppleInAppSong()
        val active = OnlineTranslationRequest<LyriconSource.PendingOnlineTranslationCommit>()
        val activeGeneration = requireNotNull(active.begin("active-attempt", allowRestart = true))
        val inAppGeneration = requireNotNull(
            slot.target.request.begin("in-app-attempt", allowRestart = true)
        )

        // 取消应用内检索不得动活动播放者的检索，反之亦然。
        slot.target.request.cancel(clearAttempt = true)
        assertEquals(activeGeneration, active.snapshot().generation)
        assertEquals("active-attempt", active.snapshot().attempt)
        assertNotEquals(inAppGeneration, slot.target.request.snapshot().generation)

        val inAppAfterCancel = slot.target.request.snapshot().generation
        active.cancel(clearAttempt = true)
        assertEquals(inAppAfterCancel, slot.target.request.snapshot().generation)
    }

    @Test
    fun `disabling source control drops the selection but keeps the song`() {
        val slot = AppleInAppSong()
        slot.adopt(song(), song(), true, null, selection())
        slot.recordPublished(song())

        slot.clearManualSelection()

        assertEquals("1455151646", slot.song()?.id)
        assertTrue(slot.target.manual.selection().isEmpty)
        assertNull(slot.publishedSong())
    }

    @Test
    fun `target marks the slot as in-app only`() {
        assertTrue(AppleInAppSong().target.inAppOnly)
    }
    @Test
    fun `a chosen lyrics source is not rolled back by a same track native callback`() {
        val slot = AppleInAppSong()
        slot.acceptSong(confirmedNative())
        val chosen = supplement("LB")
        slot.acceptSupplement(chosen, ConfirmedLyricsSourceSelection("1455151646", "LB"))

        slot.acceptSong(confirmedNative())

        assertTrue(isSupplement(slot.song()))
        assertEquals("LB", slot.song()?.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE))
        assertEquals("LB", slot.lyricsSelection()?.source)
        // 原生基准仍记着，供切回「Apple 原生」使用。
        assertEquals(1, slot.nativeSong()?.lyrics.orEmpty().size)
        assertFalse(isSupplement(slot.nativeSong()))
    }

    @Test
    fun `restoring native lyrics switches the slot back to the native baseline`() {
        val slot = AppleInAppSong()
        slot.acceptSong(confirmedNative())
        slot.acceptSupplement(supplement("LB"), ConfirmedLyricsSourceSelection("1455151646", "LB"))

        slot.restoreNative(ConfirmedLyricsSourceSelection("1455151646", LyriconSource.APPLE_NATIVE_LYRICS_SOURCE))

        assertFalse(isSupplement(slot.song()))
        assertTrue(slot.hasNativeLyrics())
        assertEquals(LyriconSource.APPLE_NATIVE_LYRICS_SOURCE, slot.lyricsSelection()?.source)
    }

    @Test
    fun `retire hands over the confirmed lyrics source and the supplement`() {
        val slot = AppleInAppSong()
        val chosen = supplement("LB")
        slot.adopt(chosen, confirmedNative(), false, ConfirmedLyricsSourceSelection("1455151646", "LB"), selection())

        assertSame(chosen, slot.song())
        assertEquals("LB", slot.lyricsSelection()?.source)
        assertEquals(1, slot.nativeSong()?.lyrics.orEmpty().size)

        val claim = slot.claimFor(confirmedNative(), supplementFlag)
        assertSame(chosen, claim?.supplementSong)
        assertEquals("LB", claim?.lyricsSelection?.source)
        assertEquals(selection(), claim?.selection)
        assertNull(slot.song())
        assertNull(slot.lyricsSelection())
    }

    @Test
    fun `claiming with native lyrics selected hands back no supplement`() {
        val slot = AppleInAppSong()
        slot.adopt(
            confirmedNative(), confirmedNative(), true,
            ConfirmedLyricsSourceSelection("1455151646", LyriconSource.APPLE_NATIVE_LYRICS_SOURCE),
            AppleManualSourceRequestState.Selection(),
        )

        val claim = slot.claimFor(confirmedNative(), supplementFlag)

        assertNull(claim?.supplementSong)
        assertEquals(LyriconSource.APPLE_NATIVE_LYRICS_SOURCE, claim?.lyricsSelection?.source)
    }

    @Test
    fun `a different song voids the lyrics source choice`() {
        val slot = AppleInAppSong()
        slot.adopt(supplement("LB"), confirmedNative(), false, ConfirmedLyricsSourceSelection("1455151646", "LB"), selection())

        assertNull(slot.claimFor(song(id = "next", name = "Next"), supplementFlag))

        slot.adopt(supplement("LB"), confirmedNative(), false, ConfirmedLyricsSourceSelection("1455151646", "LB"), selection())
        slot.acceptSong(song(id = "next", name = "Next"))
        assertNull(slot.lyricsSelection())
        assertFalse(isSupplement(slot.song()))
    }

    @Test
    fun `pending translation and pronunciation requests travel with the selection`() {
        val pending = OnlineSourceSwitchRequest(7L, "1455151646", "translation", Source.NE, 0L)
        val pendingPronunciation = OnlineSourceSwitchRequest(8L, "1455151646", "pronunciation", Source.QM, 0L)
        val withPending = selection().copy(pendingTranslation = pending, pendingPronunciation = pendingPronunciation)
        val slot = AppleInAppSong()
        slot.adopt(song(), song(), true, null, withPending)

        assertTrue(slot.target.manual.hasPendingOnlineRequest("1455151646"))
        val claim = slot.claimFor(song(), supplementFlag)
        assertEquals(pending, claim?.selection?.pendingTranslation)
        assertEquals(pendingPronunciation, claim?.selection?.pendingPronunciation)
    }

    @Test
    fun `lyrics search slot is independent of the active player's lyrics search slot`() {
        val slot = AppleInAppSong()
        val active = AppleFallbackRequest()
        val activeGeneration = active.invalidate { }
        val inAppGeneration = slot.target.fallback.invalidate { }

        slot.target.fallback.invalidate { }
        assertEquals(activeGeneration, active.generation())
        assertTrue(active.isCurrent(activeGeneration))
        assertFalse(slot.target.fallback.isCurrent(inAppGeneration))

        val inAppNow = slot.target.fallback.generation()
        active.invalidate { }
        assertEquals(inAppNow, slot.target.fallback.generation())
    }

    @Test
    fun `new track and clear invalidate the lyrics search`() {
        val slot = AppleInAppSong()
        slot.acceptSong(song())
        val generation = slot.target.fallback.invalidate { }

        slot.acceptSong(song(id = "next", name = "Next"))

        assertFalse(slot.target.fallback.isCurrent(generation))
    }

    @Test
    fun `disabling source control returns the slot to the native baseline`() {
        val slot = AppleInAppSong()
        slot.adopt(supplement("LB"), confirmedNative(), false, ConfirmedLyricsSourceSelection("1455151646", "LB"), selection())

        slot.clearManualSelection()

        assertFalse(isSupplement(slot.song()))
        assertNull(slot.lyricsSelection())
        assertTrue(slot.target.manual.selection().isEmpty)
    }
}
