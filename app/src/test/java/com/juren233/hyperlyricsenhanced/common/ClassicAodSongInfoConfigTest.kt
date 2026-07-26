package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicAodSongInfoConfigTest {

    @Test
    fun `formats title and artist in requested order`() {
        assertEquals(
            "Song - Artist",
            ClassicAodSongInfoConfig.formatSongInfo(
                title = "Song",
                artist = "Artist",
                format = RootConstants.AOD_SONG_INFO_FORMAT_TITLE_ARTIST
            )
        )
        assertEquals(
            "Artist - Song",
            ClassicAodSongInfoConfig.formatSongInfo(
                title = "Song",
                artist = "Artist",
                format = RootConstants.AOD_SONG_INFO_FORMAT_ARTIST_TITLE
            )
        )
    }

    @Test
    fun `omits empty metadata separators`() {
        assertEquals(
            "Song",
            ClassicAodSongInfoConfig.formatSongInfo(
                title = "Song",
                artist = "",
                format = RootConstants.AOD_SONG_INFO_FORMAT_TITLE_ARTIST
            )
        )
    }

    @Test
    fun `normalizes embedded position to supported range`() {
        assertEquals(
            RootConstants.AOD_SONG_INFO_POSITION_CENTER,
            ClassicAodSongInfoConfig.normalizeEmbeddedPosition(
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_POSITION
            )
        )
        assertEquals(
            RootConstants.AOD_SONG_INFO_POSITION_LEFT,
            ClassicAodSongInfoConfig.normalizeEmbeddedPosition(-1)
        )
        assertEquals(
            RootConstants.AOD_SONG_INFO_POSITION_RIGHT,
            ClassicAodSongInfoConfig.normalizeEmbeddedPosition(3)
        )
    }

    @Test
    fun `normalizes embedded song information text size to supported range`() {
        assertEquals(
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE,
            ClassicAodSongInfoConfig.sanitizeEmbeddedTextSize(
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE
            )
        )
        assertEquals(
            RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            ClassicAodSongInfoConfig.sanitizeEmbeddedTextSize(1)
        )
        assertEquals(
            RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            ClassicAodSongInfoConfig.sanitizeEmbeddedTextSize(100)
        )
    }
}
