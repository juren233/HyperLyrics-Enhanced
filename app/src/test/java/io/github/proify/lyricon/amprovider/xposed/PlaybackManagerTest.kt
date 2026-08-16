package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackManagerTest {

    @Test
    fun visibleAppleLyricsPublishBeforePlaybackMetadataCatchesUp() {
        assertEquals(
            true,
            PlaybackManager.shouldPublishBuiltLyrics(
                songId = "new",
                currentSongId = "old",
                visibleSongId = "new",
                playbackSongId = "new",
                source = "apple",
            )
        )
        assertEquals(
            false,
            PlaybackManager.shouldPublishBuiltLyrics(
                songId = "preloaded",
                currentSongId = "old",
                visibleSongId = "visible",
                playbackSongId = "old",
                source = "apple",
            )
        )
        assertEquals(
            false,
            PlaybackManager.shouldPublishBuiltLyrics(
                songId = "new",
                currentSongId = "old",
                visibleSongId = "new",
                playbackSongId = "new",
                source = "module",
            )
        )
    }

    @Test
    fun staleVisibleAppleLyricsCannotReplaceCurrentQueueSong() {
        assertEquals(
            false,
            PlaybackManager.shouldPublishBuiltLyrics(
                songId = "old",
                currentSongId = "new",
                visibleSongId = "old",
                playbackSongId = "new",
                source = "apple",
            )
        )
        assertEquals(
            false,
            PlaybackManager.shouldPublishBuiltLyrics(
                songId = "old",
                currentSongId = "old",
                visibleSongId = "old",
                playbackSongId = "new",
                source = "apple",
            )
        )
    }

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

    @Test
    fun catalogMetadataUpdateCannotStripCurrentMissingLyricsSupplement() {
        val currentLyrics = listOf(
            RichLyricLine(
                begin = 0L,
                end = 1_000L,
                duration = 1_000L,
                text = "current supplement",
                translation = "当前翻译",
            )
        )
        val currentSong = Song(
            id = "song",
            name = "Old title",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "module",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES to
                    "NE|true|true|false|73",
                LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to "QM",
            ),
            lyrics = currentLyrics,
        )
        val resolvedSong = Song(
            id = "song",
            name = "Resolved title",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_CATALOG_GENRE to "Alternative",
                LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "module",
            ),
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1_000L, text = "unmarked cache")
            ),
        )

        val merged = PlaybackManager.mergeCatalogMetadata(currentSong, resolvedSong)

        assertEquals("Resolved title", merged.name)
        assertSame(currentLyrics, merged.lyrics)
        assertEquals(
            "Alternative",
            merged.metadata?.getString(LyricMetadataKeys.APPLE_CATALOG_GENRE),
        )
        assertEquals(
            "true",
            merged.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT),
        )
        assertEquals(
            "NE",
            merged.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE),
        )
        assertEquals(
            "NE|true|true|false|73",
            merged.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES),
        )
        assertEquals(
            "QM",
            merged.metadata?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
        )
    }

    @Test
    fun moduleLyricsFillMissingLyricsOnlyWhenCurrentSongPreviouslyHadNoLyrics() {
        val builtSong = Song(
            id = "song",
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1_000L, duration = 1_000L, text = "line")
            ),
        )

        assertTrue(
            PlaybackManager.shouldUseBuiltLyricsAsMissingSupplement(
                source = "module",
                song = builtSong,
                currentSongId = "song",
                previousSong = Song(id = "song"),
            )
        )
        assertFalse(
            PlaybackManager.shouldUseBuiltLyricsAsMissingSupplement(
                source = "module",
                song = builtSong,
                currentSongId = "song",
                previousSong = builtSong,
            )
        )
        assertFalse(
            PlaybackManager.shouldUseBuiltLyricsAsMissingSupplement(
                source = "apple",
                song = builtSong,
                currentSongId = "song",
                previousSong = Song(id = "song"),
            )
        )
        assertFalse(
            PlaybackManager.shouldUseBuiltLyricsAsMissingSupplement(
                source = "module",
                song = builtSong,
                currentSongId = "other",
                previousSong = Song(id = "other"),
            )
        )
    }

    @Test
    fun confirmedAppleNativeLyricsRemoveSupplementIdentityBeforePublication() {
        val confirmed = PlaybackManager.markAsConfirmedAppleNativeLyrics(
            Song(
                id = "1395620514",
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "module",
                    LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                    LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
                    LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES to
                        "NE|true|true|true|53",
                ),
                lyrics = listOf(
                    RichLyricLine(begin = 0L, end = 1_000L, text = "native line")
                ),
            )
        )

        assertEquals(
            "apple",
            confirmed.metadata?.getString(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE),
        )
        assertEquals(
            "true",
            confirmed.metadata?.getString(LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED),
        )
        assertFalse(
            confirmed.metadata
                ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
                .toBoolean()
        )
        assertEquals(
            null,
            confirmed.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE),
        )
    }

    @Test
    fun cachedModuleLyricsRestoreMissingLyricsStoreOnlyForCurrentSong() {
        val cachedModuleSong = Song(
            id = "song",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "module"
            ),
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1_000L, text = "line")
            ),
        )

        assertTrue(
            PlaybackManager.shouldUseCachedLyricsAsMissingSupplement(
                cachedModuleSong,
                currentSongId = "song",
            )
        )
        assertFalse(
            PlaybackManager.shouldUseCachedLyricsAsMissingSupplement(
                cachedModuleSong,
                currentSongId = "other",
            )
        )
        assertFalse(
            PlaybackManager.shouldUseCachedLyricsAsMissingSupplement(
                cachedModuleSong.copy(
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple"
                    )
                ),
                currentSongId = "song",
            )
        )
        assertFalse(
            PlaybackManager.shouldUseCachedLyricsAsMissingSupplement(
                cachedModuleSong.copy(lyrics = emptyList()),
                currentSongId = "song",
            )
        )
        assertTrue(
            PlaybackManager.shouldUseCachedLyricsAsMissingSupplement(
                cachedModuleSong.copy(
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple"
                    )
                ),
                currentSongId = "song",
                hasPersistedMissingLyrics = true,
            )
        )
    }

    @Test
    fun legacyCachedLyricsBecomeColdStartCandidateOnlyForCurrentSong() {
        val legacyCachedSong = Song(
            id = "song",
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1_000L, text = "legacy line")
            ),
        )

        assertTrue(
            PlaybackManager.shouldUseCachedLyricsAsMissingSupplement(
                song = legacyCachedSong,
                currentSongId = "song",
            )
        )
        assertTrue(
            PlaybackManager.shouldUseLegacyCachedLyricsAsMissingSupplement(
                song = legacyCachedSong,
                currentSongId = "song",
            )
        )
        assertFalse(
            PlaybackManager.shouldUseCachedLyricsAsMissingSupplement(
                song = legacyCachedSong,
                currentSongId = "other",
            )
        )
        assertFalse(
            PlaybackManager.shouldUseCachedLyricsAsMissingSupplement(
                song = legacyCachedSong.copy(
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple"
                    )
                ),
                currentSongId = "song",
            )
        )
    }

    @Test
    fun currentCacheCanBeProvisionalCandidateWhenAppleRuntimeSaysNoLyrics() {
        val cachedSong = Song(
            id = "song",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple"
            ),
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1_000L, text = "cached line")
            ),
        )

        assertTrue(
            PlaybackManager.shouldPromoteCurrentCacheAfterNativeUnavailable(
                song = cachedSong,
                currentSongId = "song",
                nativeLyricsAvailable = false,
                nativeLyricsKnown = false,
                storeHasContent = false,
            )
        )
        assertFalse(
            PlaybackManager.shouldPromoteCurrentCacheAfterNativeUnavailable(
                song = cachedSong,
                currentSongId = "song",
                nativeLyricsAvailable = true,
                nativeLyricsKnown = false,
                storeHasContent = false,
            )
        )
        assertFalse(
            PlaybackManager.shouldPromoteCurrentCacheAfterNativeUnavailable(
                song = cachedSong,
                currentSongId = "song",
                nativeLyricsAvailable = false,
                nativeLyricsKnown = true,
                storeHasContent = false,
            )
        )
        assertFalse(
            PlaybackManager.shouldPromoteCurrentCacheAfterNativeUnavailable(
                song = cachedSong,
                currentSongId = "song",
                nativeLyricsAvailable = false,
                nativeLyricsKnown = false,
                storeHasContent = true,
            )
        )
        assertFalse(
            PlaybackManager.shouldPromoteCurrentCacheAfterNativeUnavailable(
                song = cachedSong,
                currentSongId = "other",
                nativeLyricsAvailable = false,
                nativeLyricsKnown = false,
                storeHasContent = false,
            )
        )
        assertFalse(
            PlaybackManager.shouldPromoteCurrentCacheAfterNativeUnavailable(
                song = cachedSong.copy(lyrics = emptyList()),
                currentSongId = "song",
                nativeLyricsAvailable = false,
                nativeLyricsKnown = false,
                storeHasContent = false,
            )
        )
    }

    @Test
    fun missingLyricsSupplementMarkerIsForwardedToSystemUi() {
        val decorated = PlaybackManager.markAsMissingLyricsSupplement(
            Song(
                id = "song",
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "module"
                ),
                lyrics = listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "line")),
            ),
        )

        assertEquals(
            "true",
            decorated.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT),
        )
        assertEquals(
            "module",
            decorated.metadata?.getString(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE),
        )
    }

    @Test
    fun legacyLyricsCacheCanBePromotedOnlyWhenSourceAndSupplementMarkersAreMissing() {
        val legacySong = Song(
            id = "song",
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1_000L, text = "line")
            ),
        )

        assertTrue(
            PlaybackManager.shouldPromoteLegacyCachedLyricsAsMissingSupplement(legacySong)
        )
        assertFalse(
            PlaybackManager.shouldPromoteLegacyCachedLyricsAsMissingSupplement(
                legacySong.copy(
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple"
                    )
                )
            )
        )
        assertFalse(
            PlaybackManager.shouldPromoteLegacyCachedLyricsAsMissingSupplement(
                legacySong.copy(
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true"
                    )
                )
            )
        )
    }
}
