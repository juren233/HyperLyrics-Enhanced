/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMetadataSurfacePolicyTest {

    @Test
    fun `prefers album entities for delayed artist page album bindings`() {
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            preferredVisibleEntityType(VisibleTextField.TITLE),
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
            preferredVisibleEntityType(VisibleTextField.ARTIST),
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.ALBUM,
            preferredVisibleEntityType(VisibleTextField.ALBUM),
        )
    }

    @Test
    fun `does not treat station models as songs`() {
        assertNull(
            AppleMetadataResolutionEngine.localizedEntityTypeForContentItemClassNames(
                listOf("SongStationItem", "BaseContentItem")
            )
        )
        assertNull(
            AppleMetadataResolutionEngine.localizedEntityTypeForContentItemClassNames(
                listOf("RadioStation", "BaseContentItem")
            )
        )
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            AppleMetadataResolutionEngine.localizedEntityTypeForContentItemClassNames(
                listOf("Song", "BaseContentItem")
            ),
        )
    }

    @Test
    fun `treats Apple queue history collection items as songs`() {
        val historyTarget = AppleMusicHookProfiles.exactTargets(
            AppleMusicVersion("6.5.1", 1583L),
            AppleMusicHookPoint.IN_APP_HISTORY_UPDATE,
        ).single()
        val historyEntryClassName = historyTarget.runtimeMemberName(
            AppleMusicRuntimeMember.QUEUE_HISTORY_ENTRY_CLASS_NAME,
        )
        assertTrue(isInAppHistoryQueueEntryClassName(historyEntryClassName, historyEntryClassName))
        assertFalse(isInAppHistoryQueueEntryClassName("Z8.c", historyEntryClassName))
        assertEquals(
            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
            AppleMetadataResolutionEngine.localizedEntityTypeForQueueItem(
                historyEntry = true,
                classNames = listOf("CollectionItemView"),
            ),
        )
        assertNull(
            AppleMetadataResolutionEngine.localizedEntityTypeForQueueItem(
                historyEntry = false,
                classNames = listOf("CollectionItemView"),
            )
        )
    }

    @Test
    fun `maps history title and artist to CollectionItemView accessors`() {
        assertEquals(
            InAppPlaybackItemAccess(
                readMember = AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_GETTER,
                readViaMethod = true,
                setter = AppleMusicRuntimeMember.CONTENT_ITEM_SET_TITLE_METHOD,
            ),
            inAppPlaybackItemAccess(
                InAppPlaybackItemContract.HISTORY,
                InAppPlaybackItemField.TITLE,
            ),
        )
        assertEquals(
            InAppPlaybackItemAccess(
                readMember = AppleMusicRuntimeMember.CONTENT_ITEM_SUBTITLE_GETTER,
                readViaMethod = true,
                setter = AppleMusicRuntimeMember.CONTENT_ITEM_SET_SUBTITLE_METHOD,
            ),
            inAppPlaybackItemAccess(
                InAppPlaybackItemContract.HISTORY,
                InAppPlaybackItemField.ARTIST,
            ),
        )
        assertNull(
            inAppPlaybackItemAccess(
                InAppPlaybackItemContract.HISTORY,
                InAppPlaybackItemField.ALBUM,
            )
        )
    }

    @Test
    fun `rejects a delayed alias after a history item is rebound to another song`() {
        assertTrue(
            shouldApplyInAppPlaybackItemAlias(
                expectedMediaId = "1158763998",
                currentMediaId = "1158763998",
            )
        )
        assertFalse(
            shouldApplyInAppPlaybackItemAlias(
                expectedMediaId = "1158763998",
                currentMediaId = "1813917858",
            )
        )
        assertFalse(
            shouldApplyInAppPlaybackItemAlias(
                expectedMediaId = "1158763998",
                currentMediaId = null,
            )
        )
    }

    @Test
    fun `expired coordinator scope still refreshes an exact visible consumer`() {
        assertTrue(
            shouldRefreshInAppSurface(
                surfaceRelevant = false,
                hasVisibleExactConsumer = true,
            )
        )
        assertFalse(
            shouldRefreshInAppSurface(
                surfaceRelevant = false,
                hasVisibleExactConsumer = false,
            )
        )
    }

    @Test
    fun `visible request lease can refresh compose after an owner switch`() {
        assertTrue(
            shouldRefreshInAppSurface(
                surfaceRelevant = false,
                hasVisibleExactConsumer = false,
                hasActiveVisibleLease = true,
            )
        )
    }

    @Test
    fun `exact recycler refresh rejects recycled or hidden rows`() {
        assertTrue(
            shouldRefreshExactBoundTarget(
                surfaceRelevant = false,
                mediaIdMatches = true,
                rootVisible = true,
            )
        )
        assertFalse(
            shouldRefreshExactBoundTarget(
                surfaceRelevant = false,
                mediaIdMatches = false,
                rootVisible = true,
            )
        )
        assertFalse(
            shouldRefreshExactBoundTarget(
                surfaceRelevant = false,
                mediaIdMatches = true,
                rootVisible = false,
            )
        )
    }

    @Test
    fun `active surface can refresh its exact binding before visibility settles`() {
        assertTrue(
            shouldRefreshExactBoundTarget(
                surfaceRelevant = true,
                mediaIdMatches = true,
                rootVisible = false,
            )
        )
    }

    @Test
    fun `album controller coalesces one batch and still accepts a later visible batch`() {
        val state = InAppLibraryControllerRefreshState()

        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 180L),
            state.enqueue(
                mediaId = "first",
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                nowUptimeMillis = 1_000L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
        assertNull(
            state.enqueue(
                mediaId = "second",
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                nowUptimeMillis = 1_020L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )
        assertEquals(listOf("first", "second"), state.takePendingMediaIds())
        state.recordBuildAttempt(nowUptimeMillis = 1_180L)
        assertNull(
            state.finishDrain(
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                nowUptimeMillis = 1_180L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            )
        )

        assertEquals(
            InAppLibraryControllerRefreshDispatch(delayMillis = 180L),
            state.enqueue(
                mediaId = "later-visible-row",
                strategy = InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
                nowUptimeMillis = 1_300L,
                albumDebounceMillis = 180L,
                playlistIntervalMillis = 500L,
            ),
        )
    }

    @Test
    fun `fresh matching artwork cache fills only an empty replacement delegate`() {
        assertEquals(
            listOf("https://example.test/cover.jpg"),
            selectInAppArtworkContinuityUrls(
                currentUrls = emptyList(),
                cachedUrls = listOf("https://example.test/cover.jpg"),
                cachedAtUptimeMillis = 1_000L,
                nowUptimeMillis = 1_100L,
                ttlMillis = 1_000L,
            ),
        )
        assertNull(
            selectInAppArtworkContinuityUrls(
                currentUrls = listOf("https://example.test/current.jpg"),
                cachedUrls = listOf("https://example.test/cover.jpg"),
                cachedAtUptimeMillis = 1_000L,
                nowUptimeMillis = 1_100L,
                ttlMillis = 1_000L,
            )
        )
    }

    @Test
    fun `stale or clock-invalid artwork cache never replaces an empty delegate`() {
        assertNull(
            selectInAppArtworkContinuityUrls(
                currentUrls = emptyList(),
                cachedUrls = listOf("https://example.test/old.jpg"),
                cachedAtUptimeMillis = 1_000L,
                nowUptimeMillis = 2_001L,
                ttlMillis = 1_000L,
            )
        )
        assertNull(
            selectInAppArtworkContinuityUrls(
                currentUrls = emptyList(),
                cachedUrls = listOf("https://example.test/future.jpg"),
                cachedAtUptimeMillis = 2_000L,
                nowUptimeMillis = 1_000L,
                ttlMillis = 1_000L,
            )
        )
    }
}
