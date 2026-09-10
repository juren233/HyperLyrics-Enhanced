/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.*
import org.junit.Test

class AppleLyricsPresentationUpdateTest {
    private fun song(id: String = "a", source: String = "QM") = Song(
        id = id,
        lyrics = listOf(RichLyricLine(begin = 0, end = 1000, text = "Hello", translation = "你好")),
        metadata = lyricMetadataOf(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to source),
    )

    @Test fun `receipt captures identity and revision at cache acceptance`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = store.receive(song())
        assertTrue(first.updated)
        assertTrue(first.displayContentChanged)
        assertEquals("a", first.update.songId)
        assertEquals(store.revision(), first.update.revision)
        store.receive(song("b"))
        assertFalse(store.isCurrentRevision(first.update.songId, requireNotNull(first.update.revision)))
        assertEquals(1L, first.update.revision)
    }

    @Test fun `source metadata change advances receipt without requesting whole page rebind`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = store.receive(song())
        val second = store.receive(song(source = "NE"))
        assertTrue(second.updated)
        assertFalse(second.displayContentChanged)
        assertEquals(requireNotNull(first.update.revision) + 1, second.update.revision)
        assertEquals("NE", store.translationSource("a"))
    }

    @Test fun `unchanged and invalid receipts do not mutate cache`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = store.receive(song())
        assertFalse(store.receive(song()).updated)
        val invalid = store.receive(Song(id = "b"))
        assertFalse(invalid.updated)
        assertFalse(invalid.displayContentChanged)
        assertEquals(first.update.revision, invalid.update.revision)
        assertTrue(store.hasTranslation("a"))
    }

    @Test fun `clear invalidates already queued receipt`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = store.receive(song())
        assertTrue(store.clear("a"))
        assertFalse(store.isCurrentRevision(first.update.songId, requireNotNull(first.update.revision)))
        assertFalse(store.hasTranslation("a"))
    }

    @Test fun `missing lyrics receipt distinguishes first content and translation only update`() {
        val store = AppleMissingLyricsStore()
        val input = song()
        val first = store.receive(input)
        assertFalse(first.hadContent)
        assertEquals(AppleMissingLyricsUpdateKind.LYRICS, first.result.kind)
        val next = store.receive(input.copy(lyrics = input.lyrics!!.map { it.copy(translation = "您好") }))
        assertTrue(next.hadContent)
        assertEquals(AppleMissingLyricsUpdateKind.TRANSLATION_ONLY, next.result.kind)
        assertEquals(first.nativeModelRevision, next.nativeModelRevision)
        assertEquals(first.nativeModelRevision, next.revisionBefore)
    }

    @Test fun `missing lyrics receipt retains song identity after next track arrives`() {
        val store = AppleMissingLyricsStore()
        val first = store.receive(song())
        val next = store.receive(song("b"))
        assertEquals("a", first.songId)
        assertEquals("b", next.songId)
        assertFalse(next.hadContent)
        assertEquals(first.nativeModelRevision + 1, next.nativeModelRevision)
        assertFalse(store.hasContent("a"))
    }

    @Test fun `deferred slot keeps latest identity and revision as one value`() {
        val pending = AppleLyricsDeferredPresentation()
        assertTrue(pending.offer(AppleLyricsPresentationUpdate("a", 1)))
        assertFalse(pending.offer(AppleLyricsPresentationUpdate("b", 2)))
        assertEquals(AppleLyricsPresentationUpdate("b", 2), pending.take())
        assertNull(pending.take())
        assertTrue(pending.offer(AppleLyricsPresentationUpdate("c", 3)))
    }

    @Test fun `menu still showing can reschedule same update including unversioned refresh`() {
        val pending = AppleLyricsDeferredPresentation()
        val update = AppleLyricsPresentationUpdate("a", null)
        assertTrue(pending.offer(update))
        val drained = requireNotNull(pending.take())
        assertTrue(pending.offer(drained))
        assertEquals(update, pending.take())
    }
}
