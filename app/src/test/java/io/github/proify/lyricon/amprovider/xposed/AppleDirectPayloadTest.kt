package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import io.github.proify.extensions.deflate
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleDirectPayloadTest {
    @Test
    fun `round trips lyrics through the direct bridge payload`() {
        val source = Song(
            id = "song-id",
            name = "I Fall Apart",
            artist = "Post Malone",
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000,
                    end = 4_000,
                    text = "Main lyric",
                    secondary = "Backing vocal",
                    translation = "翻译",
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.GROUP_VOCALS to "true",
                        LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to "伴唱翻译"
                    )
                )
            )
        )

        val compressed = json.encodeToString(source)
            .toByteArray(Charsets.UTF_8)
            .deflate()
        val restored = json.decodeFromString<Song>(
            compressed.inflate().toString(Charsets.UTF_8)
        )

        assertEquals(source, restored)
        assertEquals("Backing vocal", restored.lyrics.orEmpty().single().secondary)

        val localSong = json.decodeFromString<com.juren233.hyperlyricsenhanced.lyric.model.Song>(
            json.encodeToString(restored)
        )
        assertEquals(
            true,
            localSong.lyrics.orEmpty().single().metadata
                ?.getBoolean(LyricMetadataKeys.GROUP_VOCALS)
        )
        assertEquals(
            "伴唱翻译",
            localSong.lyrics.orEmpty().single().metadata
                ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
    }
}
