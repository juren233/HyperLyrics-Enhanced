package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackManagerTest {

    @Test
    fun catalogMetadataUpdatePreservesCurrentLyricsWhenResolvedSongIsPlaceholder() {
        val lyrics = listOf(
            RichLyricLine(begin = 0L, end = 1_000L, duration = 1_000L, text = "満ちてゆく")
        )
        val currentSong = Song(
            id = "1882935962",
            name = "Michi Teyu Ku (Overflowing)",
            artist = "Fujii Kaze",
            duration = 315_000L,
            lyrics = lyrics
        )
        val metadata = lyricMetadataOf("apple_original_title" to "満ちてゆく")
        val resolvedSong = Song(
            id = "1882935962",
            name = "Michi Teyu Ku (Overflowing)",
            artist = "Fujii Kaze",
            duration = 315_000L,
            metadata = metadata
        )

        val merged = PlaybackManager.mergeCatalogMetadata(currentSong, resolvedSong)

        assertSame(lyrics, merged.lyrics)
        assertEquals(metadata, merged.metadata)
    }

    @Test
    fun catalogMetadataUpdateKeepsResolvedLyricsWhenTheyExist() {
        val currentLyrics = listOf(
            RichLyricLine(begin = 0L, end = 1_000L, duration = 1_000L, text = "old")
        )
        val resolvedLyrics = listOf(
            RichLyricLine(begin = 0L, end = 1_000L, duration = 1_000L, text = "new")
        )
        val currentSong = Song(id = "song", lyrics = currentLyrics)
        val resolvedSong = Song(id = "song", lyrics = resolvedLyrics)

        val merged = PlaybackManager.mergeCatalogMetadata(currentSong, resolvedSong)

        assertSame(resolvedSong, merged)
    }
}
