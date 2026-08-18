/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMissingLyricsStoreTest {

    @Test
    fun `source menu availability query only activates a newly accepted supplement`() {
        assertTrue(
            shouldRunSupplementActivationSideEffects(
                trigger = "source_menu_query",
                newlyAccepted = true,
            )
        )
        assertFalse(
            shouldRunSupplementActivationSideEffects(
                trigger = "source_menu_query",
                newlyAccepted = false,
            )
        )
        assertTrue(
            shouldRunSupplementActivationSideEffects(
                trigger = "supplement_received",
                newlyAccepted = false,
            )
        )
        assertTrue(
            shouldRunSupplementActivationSideEffects(
                trigger = "page_resume",
                newlyAccepted = false,
            )
        )
    }

    @Test
    fun `native Apple lyrics suppress the missing-lyrics source menu`() {
        assertTrue(
            shouldShowMissingLyricsSourceMenu(
                hasSupplementContent = true,
                hasKnownNativeLyrics = false,
            )
        )
        assertFalse(
            shouldShowMissingLyricsSourceMenu(
                hasSupplementContent = true,
                hasKnownNativeLyrics = true,
            )
        )
        assertFalse(
            shouldShowMissingLyricsSourceMenu(
                hasSupplementContent = false,
                hasKnownNativeLyrics = false,
            )
        )
    }

    @Test
    fun `prepared supplement exposes button while native request is still loading`() {
        var now = 1_000L
        val gate = AppleNativeLyricsTakeoverGate(clock = { now })

        gate.observe("song")
        gate.onNativeRequestStarted("song")
        now += 8_000L

        assertTrue(
            shouldExposeSupplementAvailability(
                enabled = true,
                hasSupplementContent = true,
                identityAvailable = true,
                hasKnownNativeLyrics = false,
            )
        )
        assertFalse(gate.decision("song").allowed)
        assertEquals("native_request_in_flight", gate.decision("song").reason)
    }

    @Test
    fun `confirmed native lyrics suppress prepared supplement availability`() {
        assertFalse(
            shouldExposeSupplementAvailability(
                enabled = true,
                hasSupplementContent = true,
                identityAvailable = true,
                hasKnownNativeLyrics = true,
            )
        )
    }

    @Test
    fun `both Apple availability signals are required before declaring native lyrics absent`() {
        val tracker = AppleNativeLyricsAvailabilityTracker()

        assertNull(
            tracker.record(
                songId = "song",
                signal = AppleNativeLyricsAvailabilitySignal.HAS_LYRICS,
                available = false,
            )
        )
        assertEquals(
            true,
            tracker.record(
                songId = "song",
                signal = AppleNativeLyricsAvailabilitySignal.TIME_SYNCED,
                available = false,
            )
        )
        assertNull(
            tracker.record(
                songId = "song",
                signal = AppleNativeLyricsAvailabilitySignal.TIME_SYNCED,
                available = false,
            )
        )
        assertEquals(
            false,
            tracker.record(
                songId = "song",
                signal = AppleNativeLyricsAvailabilitySignal.HAS_LYRICS,
                available = true,
            )
        )
    }

    @Test
    fun `native build scope covers synchronous nested parser callbacks`() {
        val scope = AppleMissingLyricsNativeBuildScope()

        assertFalse(scope.isActive())
        val result = scope.within {
            assertTrue(scope.isActive())
            scope.within {
                assertTrue(scope.isActive())
            }
            assertTrue(scope.isActive())
            42
        }

        assertEquals(42, result)
        assertFalse(scope.isActive())
        runCatching {
            scope.within<Unit> {
                assertTrue(scope.isActive())
                error("expected")
            }
        }
        assertFalse(scope.isActive())
    }

    @Test
    fun `candidate observation alone never starts no-request takeover grace`() {
        var now = 1_000L
        val gate = AppleNativeLyricsTakeoverGate(
            clock = { now },
            noRequestGraceMs = 5_000L,
            nativeRequestTimeoutMs = 20_000L,
        )

        gate.observe("song")
        assertEquals("native_request_not_started", gate.decision("song").reason)
        assertFalse(gate.decision("song").allowed)

        now += 60_000L
        assertFalse(gate.decision("song").allowed)
        assertEquals("native_request_not_started", gate.decision("song").reason)
    }

    @Test
    fun `open lyrics page starts bounded grace when Apple never requests native lyrics`() {
        var now = 1_000L
        val gate = AppleNativeLyricsTakeoverGate(
            clock = { now },
            noRequestGraceMs = 5_000L,
            nativeRequestTimeoutMs = 20_000L,
        )

        gate.observe("song")
        now += 60_000L
        gate.onSupplementPresentationRequested("song")
        assertEquals("native_request_grace", gate.decision("song").reason)
        assertFalse(gate.decision("song").allowed)

        now += 4_999L
        assertFalse(gate.decision("song").allowed)
        now += 1L
        assertTrue(gate.decision("song").allowed)
        assertEquals("native_request_not_observed", gate.decision("song").reason)
    }

    @Test
    fun `slow Apple request blocks supplement until its real result arrives`() {
        var now = 10_000L
        val gate = AppleNativeLyricsTakeoverGate(
            clock = { now },
            noRequestGraceMs = 5_000L,
            nativeRequestTimeoutMs = 20_000L,
        )

        gate.observe("song")
        gate.onNativeRequestStarted("song")
        now += 8_000L

        assertFalse(gate.decision("song").allowed)
        assertEquals("native_request_in_flight", gate.decision("song").reason)
        assertTrue(gate.onNativeResult("song", hasLyrics = true))
        assertFalse(gate.decision("song").allowed)
        assertEquals("native_lyrics_present", gate.decision("song").reason)
    }

    @Test
    fun `confirmed empty Apple result immediately allows supplement`() {
        var now = 20_000L
        val gate = AppleNativeLyricsTakeoverGate(clock = { now })

        gate.observe("song")
        assertFalse(gate.onNativeResult("song", hasLyrics = false))
        assertFalse(gate.decision("song").allowed)

        gate.onNativeRequestStarted("song")
        now += 500L
        assertTrue(gate.onNativeResult("song", hasLyrics = false))
        assertTrue(gate.decision("song").allowed)
        assertEquals("native_empty_result", gate.decision("song").reason)
    }

    @Test
    fun `availability absence does not bypass the native result`() {
        var now = 20_000L
        val gate = AppleNativeLyricsTakeoverGate(
            clock = { now },
            nativeRequestTimeoutMs = 20_000L,
        )

        gate.onNativeRequestStarted("song")
        now += 100L
        assertFalse(gate.decision("song").allowed)
        assertEquals("native_request_in_flight", gate.decision("song").reason)

        assertTrue(gate.onNativeResult("song", hasLyrics = false))
        assertTrue(gate.decision("song").allowed)
        assertEquals("native_empty_result", gate.decision("song").reason)
    }

    @Test
    fun `hung Apple request has a bounded fallback timeout`() {
        var now = 30_000L
        val gate = AppleNativeLyricsTakeoverGate(
            clock = { now },
            nativeRequestTimeoutMs = 20_000L,
        )

        gate.onNativeRequestStarted("song")
        now += 19_999L
        assertFalse(gate.decision("song").allowed)
        now += 1L
        assertTrue(gate.decision("song").allowed)
        assertEquals("native_request_timeout", gate.decision("song").reason)
    }

    private class FakeNativePointer {
        var deallocated = false

        @Suppress("unused")
        fun deallocate() {
            deallocated = true
        }
    }

    private data class AddressPointer(val address: Long)

    private fun word(begin: Long, end: Long, text: String) = LyricWord(
        begin = begin,
        end = end,
        text = text,
    )

    private fun line(begin: Long, end: Long, text: String, words: List<LyricWord>? = null) =
        RichLyricLine(begin = begin, end = end, text = text, words = words)

    @Test
    fun `update stores normalized lines with word timelines`() {
        val store = AppleMissingLyricsStore()
        val song = Song(
            id = "123",
            lyrics = listOf(
                line(0L, 1_000L, "第一句", listOf(word(0L, 400L, "第一"), word(400L, 900L, "句"))),
                line(1_000L, 2_000L, "第二句"),
                line(-1L, 500L, "非法行"),
                line(2_000L, 2_000L, "零时长行"),
                line(3_000L, 4_000L, "   "),
            ),
        )

        assertTrue(store.update(song))
        val lines = store.lines("123")
        assertEquals(2, lines.size)
        assertEquals("第一句", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals("第一", lines[0].words[0].text)
        assertEquals(0L, lines[0].words[0].begin)
        assertEquals(emptyList<AppleMissingLyricsWord>(), lines[1].words)
    }

    @Test
    fun `update preserves english word boundary whitespace`() {
        val store = AppleMissingLyricsStore()
        val song = Song(
            id = "english-123",
            lyrics = listOf(
                line(
                    0L,
                    2_000L,
                    "Hotel California",
                    listOf(
                        word(0L, 800L, "Hotel "),
                        word(800L, 1_800L, "California"),
                    ),
                ),
            ),
        )

        assertTrue(store.update(song))
        assertEquals("Hotel ", store.lines("english-123").single().words[0].text)
        assertEquals("California", store.lines("english-123").single().words[1].text)
    }

    @Test
    fun `update keeps translation and source details for Apple native overlay`() {
        val store = AppleMissingLyricsStore()
        val statuses = listOf(
            AppleMissingLyricsSourceStatus("NE", true, true, true, 1),
            AppleMissingLyricsSourceStatus("QM", true, false, false, 0),
        )
        val song = Song(
            id = "translation-123",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES to
                    AppleMissingLyricsSourceMetadata.encodeStatuses(statuses),
            ),
            lyrics = listOf(
                RichLyricLine(
                    begin = 0L,
                    end = 2_000L,
                    text = "Original line",
                    translation = "翻译行",
                ),
            ),
        )

        assertTrue(store.update(song))
        assertTrue(store.hasTranslation("translation-123"))
        assertEquals(
            "翻译行",
            store.translation("translation-123", 0L, 2_000L, "Original line"),
        )
        assertEquals("NE", store.sourceInfo("translation-123")?.selectedSource)
        assertEquals(statuses, store.sourceInfo("translation-123")?.statuses)
    }

    @Test
    fun `update migrates old cache whose words lost boundary whitespace`() {
        val store = AppleMissingLyricsStore()
        val song = Song(
            id = "english-cache-123",
            lyrics = listOf(
                line(
                    0L,
                    2_000L,
                    "Hotel California",
                    listOf(
                        word(0L, 800L, "Hotel"),
                        word(800L, 1_800L, "California"),
                    ),
                ),
            ),
        )

        assertTrue(store.update(song))
        assertEquals("Hotel ", store.lines("english-cache-123").single().words[0].text)
        assertEquals("California", store.lines("english-cache-123").single().words[1].text)
    }

    @Test
    fun `update removes whitespace from cjk lines and words`() {
        val store = AppleMissingLyricsStore()
        val song = Song(
            id = "cjk-123",
            lyrics = listOf(
                line(
                    0L,
                    2_000L,
                    "你 好 世界",
                    listOf(
                        word(0L, 500L, "你"),
                        word(500L, 1_000L, " "),
                        word(1_000L, 1_500L, "好 "),
                        word(1_500L, 1_900L, "世界"),
                    ),
                ),
            ),
        )

        assertTrue(store.update(song))
        val stored = store.lines("cjk-123").single()
        assertEquals("你好世界", stored.text)
        assertEquals(listOf("你", "好", "世界"), stored.words.map { it.text })
        assertTrue(stored.words.none { it.text.startsWith(" ") })
    }

    @Test
    fun `duplicate update is ignored`() {
        val store = AppleMissingLyricsStore()
        val song = Song(id = "123", lyrics = listOf(line(0L, 1_000L, "歌词")))
        assertTrue(store.update(song))
        assertFalse(store.update(song))
    }

    @Test
    fun `update rejects blank id and empty lyrics`() {
        val store = AppleMissingLyricsStore()
        assertFalse(store.update(Song(id = " ", lyrics = listOf(line(0L, 1_000L, "歌词")))))
        assertFalse(store.update(Song(id = "123", lyrics = emptyList())))
        assertFalse(store.update(Song(id = "123", lyrics = listOf(line(0L, 1_000L, " ")))))
    }

    @Test
    fun `clear removes content for matching song only`() {
        val store = AppleMissingLyricsStore()
        store.update(Song(id = "123", lyrics = listOf(line(0L, 1_000L, "歌词"))))
        assertFalse(store.clear("456"))
        assertTrue(store.hasContent("123"))
        assertTrue(store.clear("123"))
        assertFalse(store.hasContent("123"))
        assertFalse(store.clear("123"))
    }

    @Test
    fun `lines returns empty for unknown song`() {
        val store = AppleMissingLyricsStore()
        store.update(Song(id = "123", lyrics = listOf(line(0L, 1_000L, "歌词"))))
        assertEquals(emptyList<AppleMissingLyricsLine>(), store.lines("999"))
        assertEquals(emptyList<AppleMissingLyricsLine>(), store.lines(null))
    }

    @Test
    fun `native pointer is bound to content and playback identity`() {
        val store = AppleMissingLyricsStore()
        val identity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "catalog-123",
            adamId = 123L,
            queueId = 456L,
        )
        val pointer = FakeNativePointer()

        store.update(
            Song(id = "catalog-123", lyrics = listOf(line(0L, 1_000L, "歌词")))
        )
        store.updatePlaybackIdentity(identity)

        assertTrue(store.updateNativeSongInfoPointer(pointer, identity))
        assertSame(pointer, store.nativeSongInfoPointer("catalog-123"))
        assertFalse(pointer.deallocated)
    }

    @Test
    fun `content switch retains exposed pointer and prevents reuse`() {
        val store = AppleMissingLyricsStore()
        val identity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "catalog-123",
            adamId = 123L,
            queueId = 456L,
        )
        val pointer = FakeNativePointer()
        store.update(
            Song(id = "catalog-123", lyrics = listOf(line(0L, 1_000L, "第一首")))
        )
        store.updatePlaybackIdentity(identity)
        assertTrue(store.updateNativeSongInfoPointer(pointer, identity))

        store.update(
            Song(id = "catalog-999", lyrics = listOf(line(0L, 1_000L, "第二首")))
        )

        // 已交给 Apple 的旧模型仍可能被切歌后的异步回调读取，不能提前释放。
        assertFalse(pointer.deallocated)
        assertNull(store.nativeSongInfoPointer())
        assertNull(store.playbackIdentity("catalog-999"))
    }

    @Test
    fun `identity switch retains exposed pointer until process ends`() {
        val store = AppleMissingLyricsStore()
        val firstIdentity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "catalog-123",
            adamId = 123L,
            queueId = 456L,
        )
        val secondIdentity = firstIdentity.copy(
            contentSongId = "catalog-999",
            adamId = 999L,
            queueId = 999L,
        )
        val pointer = FakeNativePointer()

        store.update(Song(id = "catalog-123", lyrics = listOf(line(0L, 1_000L, "第一首"))))
        store.updatePlaybackIdentity(firstIdentity)
        assertTrue(store.updateNativeSongInfoPointer(pointer, firstIdentity))

        store.updatePlaybackIdentity(secondIdentity)

        assertFalse(pointer.deallocated)
        assertNull(store.nativeSongInfoPointer("catalog-999"))
        assertSame(pointer, store.knownNativeSongInfoPointers().single())
    }

    @Test
    fun `known pointer snapshot includes current and retained supplement models`() {
        val store = AppleMissingLyricsStore()
        val firstIdentity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "catalog-123",
            adamId = 123L,
            queueId = 456L,
        )
        val secondIdentity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "catalog-999",
            adamId = 999L,
            queueId = 999L,
        )
        val retainedPointer = FakeNativePointer()
        val currentPointer = FakeNativePointer()

        store.update(Song(id = "catalog-123", lyrics = listOf(line(0L, 1_000L, "第一首"))))
        store.updatePlaybackIdentity(firstIdentity)
        assertTrue(store.updateNativeSongInfoPointer(retainedPointer, firstIdentity))

        store.update(Song(id = "catalog-999", lyrics = listOf(line(0L, 1_000L, "第二首"))))
        store.updatePlaybackIdentity(secondIdentity)
        assertTrue(store.updateNativeSongInfoPointer(currentPointer, secondIdentity))

        val pointers = store.knownNativeSongInfoPointers()
        assertEquals(2, pointers.size)
        assertTrue(pointers.any { it === retainedPointer })
        assertTrue(pointers.any { it === currentPointer })
    }

    @Test
    fun `supplement pointer matching covers current retained and unrelated pointers`() {
        val currentPointer = AddressPointer(101L)
        val retainedPointer = AddressPointer(202L)
        val retainedWrapper = AddressPointer(202L)
        val unrelatedPointer = AddressPointer(303L)
        val pointers = listOf(currentPointer, retainedPointer)
        val addressOf: (Any) -> Long? = { (it as? AddressPointer)?.address }

        assertTrue(isKnownSupplementPointer(currentPointer, pointers, addressOf))
        assertTrue(isKnownSupplementPointer(retainedWrapper, pointers, addressOf))
        assertFalse(isKnownSupplementPointer(unrelatedPointer, pointers, addressOf))
        assertFalse(isKnownSupplementPointer(AddressPointer(0L), pointers, addressOf))
    }

    @Test
    fun `stale identity pointer is rejected and released`() {
        val store = AppleMissingLyricsStore()
        val currentIdentity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "catalog-123",
            adamId = 123L,
            queueId = 456L,
        )
        val staleIdentity = currentIdentity.copy(adamId = 999L)
        val pointer = FakeNativePointer()
        store.update(
            Song(id = "catalog-123", lyrics = listOf(line(0L, 1_000L, "歌词")))
        )
        store.updatePlaybackIdentity(currentIdentity)

        assertFalse(store.updateNativeSongInfoPointer(pointer, staleIdentity))
        assertTrue(pointer.deallocated)
        assertNull(store.nativeSongInfoPointer())
    }

    @Test
    fun `translation content change keeps exposed native model`() {
        val store = AppleMissingLyricsStore()
        val identity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "translation-only",
            adamId = 1L,
            queueId = 2L,
        )
        val pointer = FakeNativePointer()
        store.update(
            Song(
                id = "translation-only",
                lyrics = listOf(
                    RichLyricLine(
                        begin = 0L,
                        end = 1_000L,
                        text = "Original",
                        translation = "旧翻译",
                    ),
                ),
            )
        )
        store.updatePlaybackIdentity(identity)
        assertTrue(store.updateNativeSongInfoPointer(pointer, identity))
        val revision = store.revision()

        assertFalse(
            store.update(
                Song(
                    id = "translation-only",
                    lyrics = listOf(
                        RichLyricLine(
                            begin = 0L,
                            end = 1_000L,
                            text = "Original",
                            translation = "新翻译",
                        ),
                    ),
                )
            )
        )

        assertEquals(revision, store.revision())
        assertEquals("新翻译", store.translation("translation-only", 0L, 1_000L, "Original"))
        assertSame(pointer, store.nativeSongInfoPointer("translation-only"))
        // 翻译 getter 会动态读取 Store；已暴露给 Apple 的指针继续有效。
        assertFalse(pointer.deallocated)
    }

    @Test
    fun `single full-line pseudo word is normalized away`() {
        val store = AppleMissingLyricsStore()
        store.update(
            Song(
                id = "line-only-pseudo-word",
                lyrics = listOf(
                    RichLyricLine(
                        begin = 0L,
                        end = 1_000L,
                        text = "整句歌词",
                        words = listOf(word(0L, 1_000L, "整句歌词")),
                    )
                ),
            )
        )

        assertTrue(store.lines("line-only-pseudo-word").single().words.isEmpty())
    }

    @Test
    fun `source-only update after translation does not rebuild native model`() {
        val store = AppleMissingLyricsStore()
        val identity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "source-only",
            adamId = 1L,
            queueId = 2L,
        )
        val first = Song(
            id = "source-only",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
            ),
            lyrics = listOf(
                RichLyricLine(
                    begin = 0L,
                    end = 1_000L,
                    text = "Original",
                    translation = "翻译",
                ),
            ),
        )
        store.update(first)
        store.updatePlaybackIdentity(identity)
        val pointer = FakeNativePointer()
        assertTrue(store.updateNativeSongInfoPointer(pointer, identity))
        val revision = store.revision()

        val sourceOnly = first.copy(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "QM",
            )
        )
        assertFalse(store.update(sourceOnly))
        assertEquals(revision, store.revision())
        assertSame(pointer, store.nativeSongInfoPointer("source-only"))
    }

    @Test
    fun `lyrics source switch without translation reuses current line translations`() {
        val store = AppleMissingLyricsStore()
        val identity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = "source-switch",
            adamId = 1L,
            queueId = 2L,
        )
        store.update(
            Song(
                id = "source-switch",
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
                ),
                lyrics = listOf(
                    RichLyricLine(
                        begin = 0L,
                        end = 1_000L,
                        text = "Original",
                        translation = "翻译",
                        words = listOf(word(0L, 1_000L, "Original")),
                    ),
                ),
            )
        )
        store.updatePlaybackIdentity(identity)
        val pointer = FakeNativePointer()
        assertTrue(store.updateNativeSongInfoPointer(pointer, identity))
        val revision = store.revision()

        val switchedSource = Song(
            id = "source-switch",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "KUGOU",
            ),
            lyrics = listOf(
                RichLyricLine(
                    begin = 0L,
                    end = 1_000L,
                    text = "Original",
                    words = listOf(word(0L, 500L, "Ori"), word(500L, 1_000L, "ginal")),
                ),
            ),
        )
        assertTrue(store.update(switchedSource))
        assertTrue(store.revision() > revision)
        assertEquals("翻译", store.translation("source-switch", 0L, 1_000L, "Original"))
        assertNull(store.nativeSongInfoPointer("source-switch"))
        assertFalse(pointer.deallocated)
    }

    @Test
    fun `source switch does not reuse translation for different line text`() {
        val store = AppleMissingLyricsStore()
        store.update(
            Song(
                id = "different-text",
                lyrics = listOf(
                    RichLyricLine(
                        begin = 0L,
                        end = 1_000L,
                        text = "Original",
                        translation = "翻译",
                    ),
                ),
            )
        )

        store.update(
            Song(
                id = "different-text",
                lyrics = listOf(
                    RichLyricLine(
                        begin = 0L,
                        end = 1_000L,
                        text = "Different",
                    ),
                ),
            )
        )

        assertNull(store.translation("different-text", 0L, 1_000L, "Different"))
    }

    @Test
    fun `source switch reuses translation when timing boundaries shift`() {
        val store = AppleMissingLyricsStore()
        store.update(
            Song(
                id = "shifted-timing",
                lyrics = listOf(
                    RichLyricLine(
                        begin = 0L,
                        end = 1_000L,
                        text = "Original",
                        translation = "翻译",
                    ),
                ),
            )
        )

        store.update(
            Song(
                id = "shifted-timing",
                lyrics = listOf(
                    RichLyricLine(
                        begin = 240L,
                        end = 1_240L,
                        text = "Original",
                    ),
                ),
            )
        )

        assertEquals("翻译", store.translation("shifted-timing", 240L, 1_240L, "Original"))
    }
}
