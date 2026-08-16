package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeOnlineTranslationStoreTest {
    @Test
    fun `exposes independent online content sources only for available content`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(
            Song(
                id = "100",
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to "QM",
                    LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE to "NE",
                ),
                lyrics = listOf(
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "君の名は",
                        translation = "你的名字",
                        roma = "Kimi no na wa",
                    )
                ),
            )
        )

        assertEquals("QM", store.translationSource("100"))
        assertEquals("NE", store.pronunciationSource("100"))
        assertNull(store.translationSource("200"))
    }
    @Test
    fun `returns exact translation for current song`() {
        val store = AppleNativeOnlineTranslationStore()
        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Hello world",
                        translation = "你好，世界",
                    ),
                )
            )
        )

        assertTrue(store.hasTranslation("100"))
        assertEquals(
            "你好，世界",
            store.translation("100", 1_000, 2_000, "  Hello   world "),
        )
        assertNull(store.translation("200", 1_000, 2_000, "Hello world"))
    }

    @Test
    fun `does not advance revision for identical online content`() {
        val store = AppleNativeOnlineTranslationStore()
        val song = song(
            id = "100",
            RichLyricLine(
                begin = 1_000,
                end = 2_000,
                text = "Hello world",
                translation = "你好，世界",
            ),
        )

        assertTrue(store.update(song))
        val revision = store.revision()

        assertFalse(store.update(song.copy()))
        assertEquals(revision, store.revision())
        assertTrue(store.isCurrentRevision("100", revision))
        assertFalse(store.isCurrentRevision("200", revision))
    }

    @Test
    fun `advances revision when online content changes or clears`() {
        val store = AppleNativeOnlineTranslationStore()
        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Hello world",
                        translation = "你好，世界",
                    ),
                )
            )
        )
        val firstRevision = store.revision()

        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Hello world",
                        translation = "你好世界",
                    ),
                )
            )
        )
        val secondRevision = store.revision()
        assertTrue(secondRevision > firstRevision)
        assertTrue(store.isCurrentRevision("100", secondRevision))
        assertFalse(store.isCurrentRevision("100", firstRevision))

        assertTrue(store.clear("100"))
        assertTrue(store.revision() > secondRevision)
        assertFalse(store.isCurrentRevision("100", secondRevision))
    }

    @Test
    fun `uses timing fallback only when timing is unambiguous`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(
            song(
                id = "100",
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "Original",
                    translation = "唯一翻译",
                ),
            )
        )

        assertEquals(
            "唯一翻译",
            store.translation("100", 1_000, 2_000, "Different wrapper text"),
        )

        store.update(
            song(
                id = "100",
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "First",
                    translation = "第一句",
                ),
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "Second",
                    translation = "第二句",
                ),
            )
        )

        assertNull(store.translation("100", 1_000, 2_000, "Unknown"))
        assertEquals("第二句", store.translation("100", 1_000, 2_000, "Second"))
    }

    @Test
    fun `does not replace overlay when payload has no translation`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(
            song(
                id = "100",
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "Original",
                    translation = "翻译",
                ),
            )
        )

        assertFalse(
            store.update(
                song(
                    id = "200",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "No translation",
                    ),
                )
            )
        )
        assertTrue(store.hasTranslation("100"))
        assertFalse(store.clear("200"))
        assertTrue(store.clear("100"))
        assertFalse(store.hasTranslation("100"))
    }

    @Test
    fun `does not create overlay from missing translation placeholders`() {
        val store = AppleNativeOnlineTranslationStore()

        listOf(" // ", "// //", "///", "//\n //").forEachIndexed { index, placeholder ->
            assertFalse(
                store.update(
                    song(
                        id = "100-$index",
                        RichLyricLine(
                            begin = 1_000,
                            end = 2_000,
                            text = "Nah",
                            translation = placeholder,
                        ),
                    )
                )
            )
        }
        assertFalse(store.hasTranslation("100-0"))
        assertNull(store.translation("100-0", 1_000, 2_000, "Nah"))
    }

    @Test
    fun `keeps real translation containing slash characters`() {
        val store = AppleNativeOnlineTranslationStore()

        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Nah",
                        translation = "AC/DC // Live",
                    ),
                )
            )
        )
        assertEquals("AC/DC // Live", store.translation("100", 1_000, 2_000, "Nah"))
    }

    @Test
    fun `keeps valid translations while ignoring placeholder lines`() {
        val store = AppleNativeOnlineTranslationStore()

        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Hello",
                        translation = "你好",
                    ),
                    RichLyricLine(
                        begin = 2_000,
                        end = 3_000,
                        text = "Nah",
                        translation = "//",
                    ),
                )
            )
        )

        assertTrue(store.hasTranslation("100"))
        assertEquals("你好", store.translation("100", 1_000, 2_000, "Hello"))
        assertNull(store.translation("100", 2_000, 3_000, "Nah"))
    }

    @Test
    fun `tracks pronunciation independently from translation`() {
        val store = AppleNativeOnlineTranslationStore()

        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "君の名は",
                        roma = "Kimi no na wa",
                    ),
                )
            )
        )

        assertFalse(store.hasTranslation("100"))
        assertTrue(store.hasPronunciation("100"))
        assertNull(store.translation("100", 1_000, 2_000, "君の名は"))
        assertEquals(
            "Kimi no na wa",
            store.pronunciation("100", 1_000, 2_000, "君の名は"),
        )
    }

    @Test
    fun `rejects non Roman and copied pronunciation payloads`() {
        val store = AppleNativeOnlineTranslationStore()

        assertFalse(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Getting washed",
                        roma = "ゲッティング ウォッシュト",
                    ),
                    RichLyricLine(
                        begin = 2_000,
                        end = 3_000,
                        text = "Home",
                        roma = "HOME",
                    ),
                )
            )
        )
        assertFalse(store.hasPronunciation("100"))
        assertNull(store.pronunciation("100", 1_000, 2_000, "Getting washed"))
        assertNull(store.pronunciation("100", 2_000, 3_000, "Home"))
    }

    @Test
    fun `rejects Latin pronunciation assigned to an English lyric line`() {
        val store = AppleNativeOnlineTranslationStore()

        assertFalse(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Where did you go?",
                        roma = "yi zei yei yv guong zong",
                    ),
                )
            )
        )
        assertFalse(store.hasPronunciation("100"))
        assertNull(
            store.pronunciation(
                "100",
                1_000,
                2_000,
                "Where did you go?",
            )
        )
    }

    @Test
    fun `keeps Apple line lookup isolated for mixed online content`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(
            song(
                id = "100",
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "First",
                    translation = "第一句",
                ),
                RichLyricLine(
                    begin = 2_000,
                    end = 3_000,
                    text = "君の名は",
                    roma = "Kimi no na wa",
                ),
            )
        )

        assertTrue(store.hasTranslation("100"))
        assertTrue(store.hasPronunciation("100"))
        assertNull(store.pronunciation("100", 1_000, 2_000, "First"))
        assertNull(store.translation("100", 2_000, 3_000, "君の名は"))
    }

    @Test
    fun `reports display content unchanged when only translation source changes`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = song(
            id = "100",
            RichLyricLine(
                begin = 1_000,
                end = 2_000,
                text = "Hello world",
                translation = "你好，世界",
            ),
        ).copy(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to "KUGOU",
            )
        )

        assertTrue(store.wouldChangeDisplayContent(first))
        assertTrue(store.update(first))

        val sourceOnlyChange = first.copy(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to "NE",
            )
        )
        assertFalse(store.wouldChangeDisplayContent(sourceOnlyChange))
        assertTrue(store.update(sourceOnlyChange))
    }

    @Test
    fun `reports display content changed when translation text changes`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = song(
            id = "100",
            RichLyricLine(
                begin = 1_000,
                end = 2_000,
                text = "Hello world",
                translation = "你好，世界",
            ),
        )
        assertTrue(store.update(first))

        val changed = song(
            id = "100",
            RichLyricLine(
                begin = 1_000,
                end = 2_000,
                text = "Hello world",
                translation = "你好世界",
            ),
        )
        assertTrue(store.wouldChangeDisplayContent(changed))
    }

    private fun song(id: String, vararg lines: RichLyricLine) = Song(
        id = id,
        lyrics = lines.toList(),
    )
}
