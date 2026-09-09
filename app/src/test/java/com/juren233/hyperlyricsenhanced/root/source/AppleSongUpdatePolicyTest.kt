package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSongUpdatePolicyTest {

    private val nativeSong = Song(
        id = "1882935962",
        name = "Michi Teyu Ku (Overflowing)",
        artist = "Fujii Kaze",
        duration = 315_000L,
        lyrics = listOf(
            RichLyricLine(
                begin = 0L,
                end = 1_000L,
                duration = 1_000L,
                text = "満ちてゆく"
            )
        )
    )

    @Test
    fun `same track placeholder cannot replace native lyrics`() {
        val placeholder = nativeSong.copy(lyrics = emptyList())

        assertTrue(
            AppleSongUpdatePolicy.shouldPreserveCurrentLyrics(
                currentSong = nativeSong,
                candidate = placeholder,
                sameTrack = true
            )
        )
    }

    @Test
    fun `same metadata media session placeholder is ignored despite duration mismatch`() {
        val mediaSessionSong = Song(
            name = nativeSong.name,
            artist = nativeSong.artist,
            duration = nativeSong.duration + 10_000L,
            lyrics = emptyList()
        )

        assertTrue(
            AppleSongUpdatePolicy.shouldIgnoreMediaSessionCandidate(
                currentSong = nativeSong,
                candidate = mediaSessionSong,
                currentHasNativeLyrics = true
            )
        )
    }

    @Test
    fun `different media session track remains eligible during a switch`() {
        val nextSong = Song(
            name = "Hana",
            artist = "Fujii Kaze",
            duration = 240_000L,
            lyrics = emptyList()
        )

        assertFalse(
            AppleSongUpdatePolicy.shouldIgnoreMediaSessionCandidate(
                currentSong = nativeSong,
                candidate = nextSong,
                currentHasNativeLyrics = true
            )
        )
    }

    @Test
    fun `media session alone cannot start online fallback`() {
        val mediaSessionSong = nativeSong.copy(lyrics = emptyList())

        assertFalse(
            AppleSongUpdatePolicy.canStartFallbackFromMediaSession(
                currentSong = null,
                mediaSessionSong = mediaSessionSong,
                currentHasNativeLyrics = false
            )
        )
    }

    @Test
    fun `provider confirmed empty lyrics can start online fallback`() {
        val emptyAppleSong = nativeSong.copy(lyrics = emptyList())

        assertTrue(
            AppleSongUpdatePolicy.canStartFallbackFromMediaSession(
                currentSong = emptyAppleSong,
                mediaSessionSong = emptyAppleSong,
                currentHasNativeLyrics = false
            )
        )
    }

    @Test
    fun `confirmed native apple lyrics cannot start online fallback`() {
        assertFalse(
            AppleSongUpdatePolicy.canStartFallbackFromMediaSession(
                currentSong = nativeSong,
                mediaSessionSong = nativeSong.copy(lyrics = emptyList()),
                currentHasNativeLyrics = true
            )
        )
    }

    @Test fun `late display metadata preserves lyrics duration and source metadata`() {
        val incoming = nativeSong.copy(name = "満ちてゆく", artist = "藤井風", lyrics = emptyList(), duration = 0L)
        val updated = AppleSongUpdatePolicy.refreshDisplayMetadata(nativeSong, incoming)!!
        assertEquals(incoming.name, updated.name)
        assertEquals(incoming.artist, updated.artist)
        assertSame(nativeSong.lyrics, updated.lyrics)
        assertSame(nativeSong.metadata, updated.metadata)
        assertEquals(nativeSong.duration, updated.duration)
    }

    @Test fun `metadata refresh rejects old track missing identity and duplicates`() {
        assertNull(AppleSongUpdatePolicy.refreshDisplayMetadata(nativeSong, nativeSong.copy(id = "other", name = "new")))
        assertNull(AppleSongUpdatePolicy.refreshDisplayMetadata(nativeSong.copy(id = null), nativeSong.copy(id = null, name = "new")))
        assertNull(AppleSongUpdatePolicy.refreshDisplayMetadata(nativeSong, nativeSong))
        assertNull(AppleSongUpdatePolicy.refreshDisplayMetadata(nativeSong, null))
    }

    @Test fun `blank metadata does not erase existing title or artist`() {
        assertNull(AppleSongUpdatePolicy.refreshDisplayMetadata(nativeSong, nativeSong.copy(name = " ", artist = null)))
        val updated = AppleSongUpdatePolicy.refreshDisplayMetadata(nativeSong, nativeSong.copy(name = "new", artist = ""))!!
        assertEquals("new", updated.name)
        assertEquals(nativeSong.artist, updated.artist)
    }

    @Test fun `publication refresh updates cache and consumer without replacing enriched payload`() {
        val publication = LyriconPublication()
        publication.publishApple(LyricPublicationEvent(nativeSong, LyricPublicationOrigin.MANUAL, true)) { _, _ -> }
        var notifications = 0
        val changed = nativeSong.copy(name = "満ちてゆく", artist = "藤井風", lyrics = emptyList())
        assertTrue(publication.refreshAppleDisplayMetadata(changed) { received, matched ->
            notifications++
            assertSame(received, publication.currentPublishedAppleSong)
            assertEquals("満ちてゆく", received?.name)
            assertSame(nativeSong.lyrics, received?.lyrics)
            assertTrue(matched)
        })
        assertFalse(publication.refreshAppleDisplayMetadata(changed) { _, _ -> notifications++ })
        assertFalse(publication.refreshAppleDisplayMetadata(changed.copy(id = "next")) { _, _ -> notifications++ })
        assertEquals(1, notifications)
        assertTrue(publication.currentPublishedAppleOnlineTranslationMatched)
        publication.reset()
        assertFalse(publication.refreshAppleDisplayMetadata(changed) { _, _ -> notifications++ })
        assertEquals(1, notifications)
    }
}
