/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaCapabilityKind
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaHostAdapter
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLyricSnapshotContractTest {
    @After
    fun tearDown() {
        LyriconDataBridge.clearState()
    }

    @Test
    fun `bridge publishes one coherent snapshot for song and position updates`() {
        val before = MediaLyricSnapshotStore.global.current().sequence
        LyriconDataBridge.updateLyricPackage("com.example.player")
        LyriconDataBridge.updateSong(
            Song(
                id = "song-1",
                name = "Song",
                artist = "Artist",
                lyrics = listOf(
                    RichLyricLine(begin = 0, end = 1_000, text = "First"),
                    RichLyricLine(begin = 1_000, end = 2_000, text = "Second"),
                ),
            )
        )
        LyriconDataBridge.updatePlaybackState(true)
        LyriconDataBridge.updatePosition(1_200)

        val snapshot = MediaLyricSnapshotStore.global.current()
        assertTrue(snapshot.sequence > before)
        assertEquals("song-1", snapshot.song?.id)
        assertEquals("com.example.player", snapshot.packageName)
        assertEquals(1_200L, snapshot.positionMs)
        assertEquals(true, snapshot.isPlaying)
        assertEquals("Second", snapshot.current?.text)
        assertTrue(snapshot.sequence >= 1L)
    }

    @Test
    fun `clear and song switch cannot retain the previous lyric generation`() {
        val store = MediaLyricSnapshotStore()
        val first = store.publish(
            MediaLyricSnapshotDraft(
                song = MediaLyricSongIdentity("one", "One", "A", 1000),
                packageName = "player",
                positionMs = 500,
                isPlaying = true,
                isTextMode = false,
                songHasDuet = false,
                current = line("Old"),
                next = null,
                nextNext = null,
            )
        )
        val cleared = store.clear()
        val second = store.publish(
            MediaLyricSnapshotDraft(
                song = MediaLyricSongIdentity("two", "Two", "B", 1000),
                packageName = "player",
                positionMs = 0,
                isPlaying = false,
                isTextMode = false,
                songHasDuet = false,
                current = line("New"),
                next = null,
                nextNext = null,
            )
        )

        assertTrue(first.sequence < cleared.sequence)
        assertTrue(cleared.sequence < second.sequence)
        assertEquals(null, cleared.song)
        assertEquals("New", second.current?.text)
        assertFalse(store.accept(first))
        assertEquals(second, store.current())
    }

    @Test
    fun `late translation is a new snapshot while song identity remains stable`() {
        val store = MediaLyricSnapshotStore()
        val initial = store.publish(draft(line("Original", translation = "")))
        val translated = store.publish(draft(line("Original", translation = "译文")))

        assertTrue(translated.sequence > initial.sequence)
        assertEquals(initial.songKey, translated.songKey)
        assertEquals("Original", translated.current?.text)
        assertEquals("译文", translated.current?.translation)
    }

    @Test
    fun `presentation assembly is deterministic and keeps semantic slot order`() {
        val snapshot = MediaLyricSnapshot(
            sequence = 7,
            song = MediaLyricSongIdentity("song", "Title", "Artist", 10_000),
            packageName = "player",
            positionMs = 1_000,
            isPlaying = true,
            isTextMode = false,
            songHasDuet = true,
            current = line(
                text = "Main",
                translation = "Translation",
                secondary = "Backing",
                alignedRight = true,
            ),
            next = line("Next"),
            nextNext = line("Later"),
        )
        val config = LyricPresentationConfig(
            translationDisplayMode = LyricTranslationDisplayMode.TRANSLATION,
            showNextLyric = true,
            duetLyrics = true,
        )

        val first = LyricPresentationAssembler.assemble(snapshot, config)
        val second = LyricPresentationAssembler.assemble(snapshot, config)

        assertEquals(first, second)
        assertEquals(3, first.groups.size)
        assertEquals(
            listOf(
                LyricPresentationSlot.MAIN,
                LyricPresentationSlot.TRANSLATION,
                LyricPresentationSlot.BACKING,
            ),
            first.groups.first().lines.map { it.slot },
        )
        assertEquals(LyricPresentationAlignment.RIGHT, first.groups.first().lines.first().alignment)
    }

    @Test
    fun `overlapping line presentation reads original binary metadata keys only`() {
        val snapshot = MediaLyricSnapshot(
            sequence = 1,
            song = MediaLyricSongIdentity("song", "Title", "Artist", 1_000),
            packageName = "player",
            positionMs = 0,
            isPlaying = true,
            isTextMode = false,
            songHasDuet = true,
            current = line(
                text = "Primary",
                secondary = "Secondary",
                metadata = mapOf(
                    LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP to "true",
                    LyricMetadataKeys.OVERLAPPING_SECONDARY_TRANSLATION to "次译",
                ),
            ),
            next = null,
            nextNext = null,
        )
        val model = LyricPresentationAssembler.assemble(
            snapshot,
            LyricPresentationConfig(
                translationDisplayMode = LyricTranslationDisplayMode.TRANSLATION,
                duetLyrics = true,
            ),
        )
        assertEquals(
            listOf("Primary", "Secondary", "次译"),
            model.groups.single().lines.map { it.text },
        )
    }

    @Test
    fun `profiles retain exact OS3 and OS4 descriptors and reject aliases`() {
        val listener = "Lcom/android/systemui/statusbar/notification/fullaod/" +
            "NotifiFullAodController\$FullAodTransitionListener;"
        assertEquals(
            listener + "->onCancel(Ljava/lang/Object;)V",
            requireNotNull(SystemUiMediaProfile.OS3.method("transition.onCancel")).descriptor,
        )
        assertEquals(
            listener + "->onUpdate(Ljava/lang/Object;Ljava/util/Collection;)V",
            requireNotNull(SystemUiMediaProfile.OS4.method("transition.onUpdate")).descriptor,
        )
        assertEquals(
            "Lcom/android/systemui/statusbar/notification/mediacontrol/" +
                "MiuiMediaHeaderView;->setActualHeight(IZ)V",
            requireNotNull(SystemUiMediaProfile.OS4.method("header.setActualHeight")).descriptor,
        )
        assertFalse(
            SystemUiMediaProfile.OS4.methods.values.any {
                it.name == "setActualHeight" && it.signature == "(I)V"
            },
        )
        assertEquals(SystemUiMediaProfile.OS3, SystemUiMediaProfile.forBuild("3.0.301.0.WOCCNXM"))
        assertEquals(SystemUiMediaProfile.OS4, SystemUiMediaProfile.forBuild("OS4.0.0.6.XOCCNXM"))
        assertEquals(null, SystemUiMediaProfile.forBuild("unknown-system"))
    }

    @Test
    fun `capability is fail closed and isolated per class loader`() {
        val adapter = SystemUiMediaHostAdapter(SystemUiMediaProfile.OS4)
        val firstLoader = MissingClassLoader()
        val secondLoader = MissingClassLoader()

        val first = adapter.capability(firstLoader)
        val second = adapter.capability(secondLoader)
        val unknown = SystemUiMediaHostAdapter.forBuild("unknown-system", firstLoader)

        assertFalse(first.supports(SystemUiMediaCapabilityKind.MEDIA_CONTROLLER_LIFECYCLE))
        assertFalse(first.supports(SystemUiMediaCapabilityKind.FULL_AOD_CALLBACK))
        assertNotNull(first.reason(SystemUiMediaCapabilityKind.FULL_AOD_CALLBACK))
        assertNotEquals(first.classLoaderIdentity, second.classLoaderIdentity)
        assertEquals(null, unknown)
        assertEquals(null, adapter.binding(null))
    }

    private fun draft(line: MediaLyricLineSnapshot): MediaLyricSnapshotDraft =
        MediaLyricSnapshotDraft(
            song = MediaLyricSongIdentity("song", "Title", "Artist", 1_000),
            packageName = "player",
            positionMs = 0,
            isPlaying = true,
            isTextMode = false,
            songHasDuet = false,
            current = line,
            next = null,
            nextNext = null,
        )

    private fun line(
        text: String,
        translation: String = "",
        secondary: String = "",
        alignedRight: Boolean = false,
        metadata: Map<String, String?> = emptyMap(),
    ): MediaLyricLineSnapshot = MediaLyricLineSnapshot(
        beginMs = 0,
        endMs = 1_000,
        durationMs = 1_000,
        text = text,
        secondary = secondary,
        translation = translation,
        roma = "",
        alignedRight = alignedRight,
        metadata = metadata,
    )

    private class MissingClassLoader : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            throw ClassNotFoundException(name)
    }
}
