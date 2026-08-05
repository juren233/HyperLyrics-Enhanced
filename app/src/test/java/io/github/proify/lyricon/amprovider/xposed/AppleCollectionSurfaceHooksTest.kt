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

class AppleCollectionSurfaceHooksTest {

    @Test
    fun `final Epoxy dispatcher routes collection and artist page models separately`() {
        assertEquals(
            MetadataPageFinalBindingKind.ALBUM_HEADER,
            metadataPageFinalBindingKind(true, false, false, false, false),
        )
        assertEquals(
            MetadataPageFinalBindingKind.ALBUM_ROW,
            metadataPageFinalBindingKind(false, true, false, false, false),
        )
        assertEquals(
            MetadataPageFinalBindingKind.PLAYLIST_ROW,
            metadataPageFinalBindingKind(false, false, true, false, false),
        )
        assertEquals(
            MetadataPageFinalBindingKind.ARTIST_TOP_SONG,
            metadataPageFinalBindingKind(false, false, false, true, false),
        )
        assertEquals(
            MetadataPageFinalBindingKind.ARTIST_HEADER,
            metadataPageFinalBindingKind(false, false, false, false, true),
        )
        assertNull(metadataPageFinalBindingKind(false, false, false, false, false))
    }

    @Test
    fun `album artist mismatch changes the controller alias for the same safe artist`() {
        val songAlias = AppliedMetadataAlias(
            mediaId = "song",
            title = "One Last Kiss",
            artist = "Utada",
            album = "One Last Kiss",
            language = "en-US",
        )

        assertEquals(
            songAlias.copy(artist = "宇多田ヒカル"),
            albumPageControllerAppliedAlias(
                appliedAlias = songAlias,
                songArtistId = "18756224",
                albumArtistId = "18756224",
                albumArtist = "宇多田ヒカル",
            ),
        )
    }

    @Test
    fun `album artist alignment rejects unrelated artists and preserves matching names`() {
        val songAlias = AppliedMetadataAlias(
            mediaId = "song",
            title = "Home",
            artist = "Charlie Puth、Utada",
            album = "CHARLIE",
            language = "en-US",
        )

        assertEquals(
            songAlias,
            albumPageControllerAppliedAlias(songAlias, null, "18756224", "宇多田ヒカル"),
        )
        assertEquals(
            songAlias,
            albumPageControllerAppliedAlias(
                songAlias,
                "18756224",
                "1486113150",
                "藤井 風",
            ),
        )
        val aligned = songAlias.copy(artist = "宇多田ヒカル")
        assertEquals(
            aligned,
            albumPageControllerAppliedAlias(
                aligned,
                "18756224",
                "18756224",
                "  宇多田ヒカル  ",
            ),
        )
    }

    @Test
    fun `playlist direct row refresh bypasses only playlist full rebuilds`() {
        assertTrue(
            shouldUsePlaylistDirectRowRefresh(
                InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                true,
            )
        )
        assertFalse(
            shouldUsePlaylistDirectRowRefresh(
                InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD,
                false,
            )
        )
        assertFalse(
            shouldUsePlaylistDirectRowRefresh(
                InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                true,
            )
        )
        assertFalse(
            shouldUsePlaylistDirectRowRefresh(
                InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                false,
            )
        )
    }
}
