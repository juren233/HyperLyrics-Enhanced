package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
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
}
