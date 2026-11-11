/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSourceOverrideTest {
    @Test
    fun `strip blanks translation and pronunciation including background translation`() {
        val song = song(
            line(
                1_000L,
                "First",
                translation = "官方翻译",
                roma = "guanfang",
                background = "官方伴唱",
            ),
        )

        val stripped = with(OnlineSourceOverride) { strip(song, translation = true, pronunciation = true) }

        val strippedLine = stripped.lyrics!!.single()
        assertNull(strippedLine.translation)
        assertNull(strippedLine.translationWords)
        assertNull(strippedLine.roma)
        assertNull(strippedLine.backgroundTranslationForTest())
        assertEquals("First", strippedLine.text)
    }

    @Test
    fun `strip without target content returns the same song`() {
        val song = song(line(1_000L, "First", translation = "官方翻译"))

        val untouched = with(OnlineSourceOverride) { strip(song, translation = false, pronunciation = false) }

        assertSame(song, untouched)
    }

    @Test
    fun `overlay translation takes picked lines and keeps the rest`() {
        val target = song(
            line(1_000L, "First", translation = "官方翻译", background = "官方伴唱"),
            line(2_000L, "Second", translation = "官方第二行"),
        )
        val picked = song(
            line(1_000L, "First", translation = "网易第一句"),
            line(2_000L, "Second", translation = null),
        )

        val merged = with(OnlineSourceOverride) { overlayTranslation(target, picked) }

        val lines = merged.lyrics!!
        // The picked source replaces Apple's own translation where it has a line...
        assertEquals("网易第一句", lines[0].translation)
        // ...keeps Apple's background translation when it has none of its own...
        assertEquals("官方伴唱", lines[0].backgroundTranslationForTest())
        // ...and leaves lines it could not match untouched.
        assertEquals("官方第二行", lines[1].translation)
    }

    @Test
    fun `overlay pronunciation takes picked lines and keeps the rest`() {
        val target = song(
            line(1_000L, "First", roma = "official"),
            line(2_000L, "Second", roma = "official-second"),
        )
        val picked = song(
            line(1_000L, "First", roma = "netease"),
            line(2_000L, "Second", roma = " "),
        )

        val merged = with(OnlineSourceOverride) { overlayPronunciation(target, picked) }

        val lines = merged.lyrics!!
        assertEquals("netease", lines[0].roma)
        assertEquals("official-second", lines[1].roma)
    }

    @Test
    fun `restore puts Apple's own content back on every line`() {
        val native = song(
            line(1_000L, "First", translation = "官方翻译", background = "官方伴唱", roma = "official"),
            line(2_000L, "Second"),
        )
        val published = song(
            line(1_000L, "First", translation = "在线第一行", background = "在线伴唱", roma = "online"),
            line(2_000L, "Second", translation = "在线第二行", roma = "online-second"),
        )

        val restored = with(OnlineSourceOverride) {
            restoreNativeTranslation(
                restoreNativePronunciation(published, native),
                native,
            )
        }

        val lines = restored.lyrics!!
        assertEquals("官方翻译", lines[0].translation)
        assertEquals("官方伴唱", lines[0].backgroundTranslationForTest())
        assertEquals("official", lines[0].roma)
        assertNull(lines[1].translation)
        assertNull(lines[1].roma)
    }

    @Test
    fun `changes content compares translation pronunciation and background`() {
        val base = song(line(1_000L, "First", translation = "A", roma = "a", background = "bg"))

        with(OnlineSourceOverride) {
            assertFalse(changesContent(base, song(line(1_000L, "First", translation = "A", roma = "a", background = "bg"))))
            assertTrue(changesContent(base, song(line(1_000L, "First", translation = "B", roma = "a", background = "bg"))))
            assertTrue(changesContent(base, song(line(1_000L, "First", translation = "A", roma = "b", background = "bg"))))
            assertTrue(changesContent(base, song(line(1_000L, "First", translation = "A", roma = "a", background = "other"))))
            assertTrue(changesContent(base, song(line(1_000L, "First", translation = "A", roma = "a"))))
        }
    }

    @Test
    fun `coverage counts only sung lines`() {
        val song = song(
            line(1_000L, "First", translation = "A", roma = "a"),
            line(2_000L, "Second", translation = "B"),
            line(3_000L, " ", translation = "C", roma = "c"),
        )

        with(OnlineSourceOverride) {
            val translation = translationCoverage(song)
            assertEquals(2, translation.matchedLines)
            assertEquals(2, translation.totalLines)
            val pronunciation = pronunciationCoverage(song)
            assertEquals(1, pronunciation.matchedLines)
            assertEquals(2, pronunciation.totalLines)
        }
    }

    @Test
    fun `native state reports what Apple itself provides`() {
        val song = song(
            line(1_000L, "First", translation = "A"),
            line(2_000L, "Second"),
        )

        val state = with(OnlineSourceOverride) { nativeState(song) }

        assertTrue(state.hasTranslation)
        assertFalse(state.hasPronunciation)
    }

    @Test
    fun `metadata helpers replace a key instead of duplicating it`() {
        val song = song(line(1_000L, "First"))
            .let { base ->
                with(OnlineSourceOverride) {
                    base.withMetadataValue("key", "one")
                        .withMetadataValue("key", "two")
                        .withMetadataFlag("flag", true)
                        .withMetadataFlag("flag", false)
                }
            }

        val entries = song.metadata!!.entries
        assertEquals(1, entries.count { it.key == "key" })
        assertEquals("two", song.metadata!!.getString("key"))
        assertNull(song.metadata!!.getString("flag"))
    }

    private fun song(vararg lyrics: RichLyricLine): Song = Song(
        id = "123",
        name = "Override Song",
        artist = "Artist",
        duration = 180_000L,
        lyrics = lyrics.toList(),
    )

    private fun line(
        begin: Long,
        text: String,
        translation: String? = null,
        roma: String? = null,
        background: String? = null,
    ): RichLyricLine = RichLyricLine(
        begin = begin,
        end = begin + 900L,
        text = text,
        translation = translation,
        roma = roma,
        metadata = background?.let {
            lyricMetadataOf(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to it)
        },
    )

    private fun RichLyricLine.backgroundTranslationForTest(): String? =
        metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
}
