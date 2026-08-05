/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLibrarySurfaceHooksTest {

    @Test
    fun `compose render capture wins over the full recent items fallback`() {
        assertEquals(
            listOf("152197399", "1882935769"),
            composeVisibleMetadataResolutionIds(
                capturedMediaIds = listOf(
                    "152197399",
                    "invalid",
                    "1882935769",
                    "152197399",
                ),
                fallbackMediaIds = listOf(
                    "1529513416",
                    "6773456078",
                    "1747393653",
                ),
                limit = 12,
            ),
        )
    }

    @Test
    fun `compose fallback keeps only the first bounded catalog ids`() {
        assertEquals(
            listOf("152197399", "1529513416", "6773456078"),
            composeVisibleMetadataResolutionIds(
                capturedMediaIds = emptyList(),
                fallbackMediaIds = listOf(
                    "152197399",
                    "invalid",
                    "1529513416",
                    "6773456078",
                    "1747393653",
                ),
                limit = 3,
            ),
        )
        assertTrue(
            composeVisibleMetadataResolutionIds(
                capturedMediaIds = listOf("152197399"),
                fallbackMediaIds = emptyList(),
                limit = 0,
            ).isEmpty()
        )
    }

    @Test
    fun `library controller strategy preserves typed and generic rebuild paths`() {
        assertEquals(
            InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
            inAppLibraryControllerBuildStrategy(true, false, false),
        )
        assertEquals(
            InAppLibraryControllerBuildStrategy.ARTIST_SET_DATA,
            inAppLibraryControllerBuildStrategy(false, true, false),
        )
        assertEquals(
            InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
            inAppLibraryControllerBuildStrategy(false, false, true),
        )
        assertEquals(
            InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD,
            inAppLibraryControllerBuildStrategy(false, false, false),
        )
    }

    @Test
    fun `library entity classification keeps artists albums and songs isolated`() {
        assertEquals(
            InAppLibraryEntityKind.ARTIST,
            inAppLibraryEntityKindForClassNames(
                listOf(
                    "com.apple.android.music.mediaapi.models.Artist",
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                )
            ),
        )
        assertEquals(
            InAppLibraryEntityKind.ALBUM,
            inAppLibraryEntityKindForClassNames(
                listOf(
                    "com.apple.android.music.mediaapi.models.LibraryAlbum",
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                )
            ),
        )
        assertEquals(
            InAppLibraryEntityKind.SONG,
            inAppLibraryEntityKindForClassNames(
                listOf(
                    "com.apple.android.music.mediaapi.models.LibrarySong",
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                )
            ),
        )
        assertNull(
            inAppLibraryEntityKindForClassNames(
                listOf(
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                    "com.apple.android.music.search2.SearchResult",
                )
            )
        )
    }

    @Test
    fun `library entity kinds retain independent resolver cache types`() {
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
            localizedEntityTypeForInAppLibraryKind(InAppLibraryEntityKind.ALBUM),
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            localizedEntityTypeForInAppLibraryKind(InAppLibraryEntityKind.SONG),
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            localizedEntityTypeForInAppLibraryKind(InAppLibraryEntityKind.ARTIST),
        )
    }

    @Test
    fun `playlist controller refreshes its first alias immediately then enters cooldown`() {
        val state = InAppLibraryControllerRefreshState()
        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 0L),
            state.enqueue(
                mediaId = "first",
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_000L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
        assertEquals(listOf("first"), state.takePendingMediaIds())
        state.recordBuildAttempt(nowUptimeMillis = 1_000L)
        assertNull(
            state.finishDrain(
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_000L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )
        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 400L),
            state.enqueue(
                mediaId = "second",
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_100L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
    }

    @Test
    fun `playlist controller coalesces aliases and preserves the trailing batch`() {
        val state = InAppLibraryControllerRefreshState()
        state.enqueue(
            mediaId = "first",
            strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
            nowUptimeMillis = 1_000L,
            albumDebounceMillis = 180L,
            playlistIntervalMillis = 500L,
        )
        assertEquals(listOf("first"), state.takePendingMediaIds())
        state.recordBuildAttempt(nowUptimeMillis = 1_000L)
        assertNull(
            state.enqueue(
                mediaId = "second",
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )
        assertNull(
            state.enqueue(
                mediaId = "third",
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )
        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 490L),
            state.finishDrain(
                strategy = InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
        assertEquals(listOf("second", "third"), state.takePendingMediaIds())
    }

    @Test
    fun `album refresh uses a debounce while generic refresh remains immediate`() {
        assertEquals(
            180L,
            inAppLibraryControllerRefreshDelayMillis(
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                lastBuildUptimeMillis = 1_000L,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
        assertEquals(
            0L,
            inAppLibraryControllerRefreshDelayMillis(
                strategy = InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD,
                lastBuildUptimeMillis = 1_000L,
                nowUptimeMillis = 1_010L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
    }

    @Test
    fun `compose alias deduplication is isolated by media id within one state`() {
        val albumAlias = AppliedMetadataAlias(
            mediaId = "album",
            title = "Pre Prema",
            artist = "藤井 風",
            album = "Pre Prema",
            language = "ja-JP",
        )
        val songAlias = AppliedMetadataAlias(
            mediaId = "song",
            title = "Feelin Good",
            artist = "藤井 風",
            album = "Pre Prema",
            language = "ja-JP",
        )
        val appliedAliases = mapOf(
            albumAlias.mediaId to albumAlias,
            songAlias.mediaId to songAlias,
        )
        assertFalse(
            shouldRefreshInAppLibraryComposeAlias(
                appliedAliases,
                albumAlias.mediaId,
                albumAlias,
            )
        )
        assertFalse(
            shouldRefreshInAppLibraryComposeAlias(
                appliedAliases,
                songAlias.mediaId,
                songAlias,
            )
        )
        assertTrue(
            shouldRefreshInAppLibraryComposeAlias(
                appliedAliases,
                songAlias.mediaId,
                songAlias.copy(artist = "藤井风"),
            )
        )
    }
}
