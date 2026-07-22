package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineTranslationMatcherTest {

    @Test
    fun `matches normalized lyrics while preserving apple timing and layout`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000L,
                    end = 3_000L,
                    text = "Hello, world!",
                    secondary = "Backing vocal",
                    isAlignedRight = true
                )
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(LrcLine(1_300L, "hello world", "你好，世界"))
        )

        val line = result.song.lyrics?.single()
        assertEquals(1, result.matchedCount)
        assertEquals(1_000L, line?.begin)
        assertEquals(3_000L, line?.end)
        assertEquals(true, line?.isAlignedRight)
        assertEquals("Backing vocal", line?.secondary)
        assertEquals("你好，世界", line?.translation)
    }

    @Test
    fun `matches repeated lyrics in chronological order`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(begin = 1_000L, end = 2_000L, text = "Again"),
                RichLyricLine(begin = 8_000L, end = 9_000L, text = "Again")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(1_100L, "Again", "再一次"),
                LrcLine(8_100L, "Again", "又一次")
            )
        )

        assertEquals(listOf("再一次", "又一次"), result.song.lyrics?.map { it.translation })
    }

    @Test
    fun `matches apple main lyric against online line containing backing vocals`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(
                    begin = 35_000L,
                    end = 40_000L,
                    text = "Ooo, don't you know",
                    secondary = "Don't you know?"
                )
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(
                    35_070L,
                    "Ooo, don't you know (Don't you know?)",
                    "噢 难道你不知道吗 (难道你不知道吗)"
                )
            )
        )

        assertEquals(1, result.matchedCount)
        val matchedLine = result.song.lyrics?.single()
        assertEquals("Ooo, don't you know", matchedLine?.text)
        assertEquals("Don't you know?", matchedLine?.secondary)
        assertEquals("噢 难道你不知道吗", matchedLine?.translation)
        assertEquals(
            "难道你不知道吗",
            matchedLine?.metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
    }

    @Test
    fun `keeps native backing translation when online source only supplies main translation`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000L,
                    end = 2_000L,
                    text = "Main lyric",
                    secondary = "Backing vocal",
                    metadata = com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf(
                        LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to "Apple 伴唱译文"
                    )
                )
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(LrcLine(1_000L, "Main lyric", "在线主译文"))
        )

        val line = result.song.lyrics?.single()
        assertEquals("在线主译文", line?.translation)
        assertEquals(
            "Apple 伴唱译文",
            line?.metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
    }

    @Test
    fun `extracts backing translation when apple main text also contains the vocal`() {
        val song = Song(
            name = "I Like It",
            lyrics = listOf(
                RichLyricLine(
                    begin = 217_369L,
                    end = 220_909L,
                    text = "I said I like it like that (¡Rrr!)",
                    secondary = "¡Rrr!"
                )
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(
                    217_369L,
                    "I said I like it like that (¡Rrr!)",
                    "我说我就喜欢这样（吼！）"
                )
            )
        )

        val line = result.song.lyrics?.single()
        assertEquals("我说我就喜欢这样", line?.translation)
        assertEquals(
            "吼！",
            line?.metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
    }

    @Test
    fun `keeps parenthetical translation when apple line has no backing vocal`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(begin = 1_000L, end = 2_000L, text = "Main lyric")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(LrcLine(1_000L, "Main lyric", "主句（补充说明）"))
        )

        assertEquals("主句（补充说明）", result.song.lyrics?.single()?.translation)
        assertNull(
            result.song.lyrics?.single()?.metadata
                ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
    }

    @Test
    fun `drops online parenthetical content absent from apple lyric`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(begin = 35_000L, end = 40_000L, text = "Ooo, don't you know")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(
                    35_070L,
                    "Ooo, don't you know (Don't you know?)",
                    "噢 难道你不知道吗 (难道你不知道吗)"
                )
            )
        )

        val matchedLine = result.song.lyrics?.single()
        assertEquals("Ooo, don't you know", matchedLine?.text)
        assertNull(matchedLine?.secondary)
        assertEquals("噢 难道你不知道吗", matchedLine?.translation)
        assertNull(
            matchedLine?.metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
    }

    @Test
    fun `merges translations when apple combines multiple online lines`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(
                    begin = 10_980L,
                    end = 16_000L,
                    text = "No matter how good this is, it could never satisfy"
                )
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(10_980L, "No matter how good this is", "不管这一切多美好"),
                LrcLine(12_820L, "It could never satisfy", "永远都无法彻底满足")
            )
        )

        assertEquals(1, result.matchedCount)
        assertEquals(
            "不管这一切多美好 永远都无法彻底满足",
            result.song.lyrics?.single()?.translation
        )
    }

    @Test
    fun `splits translation when apple separates one online line`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(begin = 26_310L, end = 28_800L, text = "It ain't a mystery"),
                RichLyricLine(begin = 28_800L, end = 31_380L, text = "That every time you leave")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(
                    26_310L,
                    "It ain't a mystery that every time you leave",
                    "这并非什么秘密 每当你转身离去"
                )
            )
        )

        assertEquals(2, result.matchedCount)
        assertEquals(
            listOf("这并非什么秘密", "每当你转身离去"),
            result.song.lyrics?.map { it.translation }
        )
    }

    @Test
    fun `does not split an unstructured translation at arbitrary characters`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(begin = 1_000L, end = 2_000L, text = "Short"),
                RichLyricLine(begin = 2_000L, end = 4_000L, text = "A much longer source line")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(
                    1_000L,
                    "Short a much longer source line",
                    "这是一句没有任何可靠分隔符的中文译文"
                )
            )
        )

        assertEquals(1, result.matchedCount)
        assertEquals(
            listOf(null, "这是一句没有任何可靠分隔符的中文译文"),
            result.song.lyrics?.map { it.translation }
        )
    }

    @Test
    fun `does not zip a multi line group whose internal lines do not match`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(begin = 1_000L, end = 2_000L, text = "Alpha"),
                RichLyricLine(begin = 2_000L, end = 3_000L, text = "Beta")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(1_000L, "Alpha", "正确第一句"),
                LrcLine(2_000L, "Beta jam", "不该给第二句")
            )
        )

        assertEquals(listOf("正确第一句", null), result.song.lyrics?.map { it.translation })
    }

    @Test
    fun `does not split a Chinese translation at spaces inside an English name`() {
        val song = Song(
            name = "I Like It",
            lyrics = listOf(
                RichLyricLine(begin = 1_000L, end = 2_000L, text = "Yeah they call me Cardi B"),
                RichLyricLine(begin = 2_000L, end = 3_000L, text = "I run this shit like cardio")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(
                    1_000L,
                    "Yeah they call me Cardi B I run this shit like cardio",
                    "家喻户晓的Cardi B 成功就像有氧运动一样简单"
                )
            )
        )

        assertEquals(listOf(null, null), result.song.lyrics?.map { it.translation })
    }

    @Test
    fun `fills only unmatched translations from the supplemental source`() {
        val source = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(text = "First", translation = "首选译文"),
                RichLyricLine(text = "Second"),
                RichLyricLine(
                    text = "Third",
                    secondary = "Backing",
                    metadata = com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf(
                        LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to "首选伴唱译文"
                    )
                )
            )
        )
        val supplementalSong = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(text = "First", translation = "备选译文"),
                RichLyricLine(text = "Second", translation = "补全译文"),
                RichLyricLine(
                    text = "Third",
                    secondary = "Backing",
                    metadata = com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf(
                        LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to "备选伴唱译文"
                    )
                )
            )
        )

        val result = OnlineTranslationMatcher.fillMissing(
            OnlineTranslationMatcher.Result(source, matchedCount = 1, averageMatchScore = 0.95),
            OnlineTranslationMatcher.Result(supplementalSong, matchedCount = 2, averageMatchScore = 0.9)
        )

        assertEquals(
            listOf("首选译文", "补全译文", null),
            result.song.lyrics?.map { it.translation }
        )
        assertEquals(
            "首选伴唱译文",
            result.song.lyrics?.get(2)?.metadata
                ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
        assertEquals(2, result.matchedCount)
    }

    @Test
    fun `maps online parenthetical vocal to a separate apple line`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(begin = 137_110L, end = 139_000L, text = "Ooh, don't you know"),
                RichLyricLine(begin = 139_000L, end = 141_390L, text = "Don't you know")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(
                    137_110L,
                    "Ooo, don't you know (Don't you know?)",
                    "噢 难道你不知道吗 (不知道吗)"
                )
            )
        )

        assertEquals(2, result.matchedCount)
        assertEquals(
            listOf("噢 难道你不知道吗", "不知道吗"),
            result.song.lyrics?.map { it.translation }
        )
    }

    @Test
    fun `does not let a distant repeated chorus steal the current match`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(begin = 1_000L, end = 2_000L, text = "Stay with me"),
                RichLyricLine(begin = 5_000L, end = 6_000L, text = "Next line")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(
                LrcLine(1_100L, "Stay with me (with me)", "留在我身边"),
                LrcLine(5_100L, "Next line", "下一句"),
                LrcLine(61_000L, "Stay with me", "后段副歌")
            )
        )

        assertEquals(2, result.matchedCount)
        assertEquals(
            listOf("留在我身边", "下一句"),
            result.song.lyrics?.map { it.translation }
        )
    }

    @Test
    fun `rejects unrelated online lyric and preserves existing translation`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000L,
                    end = 2_000L,
                    text = "Original line",
                    translation = "Existing"
                ),
                RichLyricLine(begin = 3_000L, end = 4_000L, text = "Different text")
            )
        )

        val result = OnlineTranslationMatcher.apply(
            song,
            listOf(LrcLine(3_000L, "Nothing alike", "Wrong"))
        )

        assertEquals(0, result.matchedCount)
        assertEquals("Existing", result.song.lyrics?.get(0)?.translation)
        assertNull(result.song.lyrics?.get(1)?.translation)
    }
}
