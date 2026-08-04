/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.view.View
import android.widget.TextView
import java.lang.ref.WeakReference

internal data class InAppLibraryControllerRefreshDispatch(
    val delayMillis: Long,
)

internal class InAppLibraryControllerRefreshState {
    private val pendingMediaIds = linkedSetOf<String>()
    var scheduled: Boolean = false
        private set
    private var lastBuildUptimeMillis: Long? = null

    fun enqueue(
        mediaId: String,
        strategy: InAppLibraryControllerBuildStrategy,
        nowUptimeMillis: Long,
        albumDebounceMillis: Long,
        playlistIntervalMillis: Long,
    ): InAppLibraryControllerRefreshDispatch? {
        pendingMediaIds.add(mediaId)
        if (scheduled) return null
        scheduled = true
        return InAppLibraryControllerRefreshDispatch(
            delayMillis = inAppLibraryControllerRefreshDelayMillis(
                strategy = strategy,
                lastBuildUptimeMillis = lastBuildUptimeMillis,
                nowUptimeMillis = nowUptimeMillis,
                albumDebounceMillis = albumDebounceMillis,
                playlistIntervalMillis = playlistIntervalMillis,
            )
        )
    }

    fun takePendingMediaIds(): List<String> = pendingMediaIds.toList().also {
        pendingMediaIds.clear()
    }

    fun recordBuildAttempt(nowUptimeMillis: Long) {
        lastBuildUptimeMillis = nowUptimeMillis
    }

    fun finishDrain(
        strategy: InAppLibraryControllerBuildStrategy,
        nowUptimeMillis: Long,
        albumDebounceMillis: Long,
        playlistIntervalMillis: Long,
    ): InAppLibraryControllerRefreshDispatch? {
        if (pendingMediaIds.isEmpty()) {
            scheduled = false
            return null
        }
        return InAppLibraryControllerRefreshDispatch(
            delayMillis = inAppLibraryControllerRefreshDelayMillis(
                strategy = strategy,
                lastBuildUptimeMillis = lastBuildUptimeMillis,
                nowUptimeMillis = nowUptimeMillis,
                albumDebounceMillis = albumDebounceMillis,
                playlistIntervalMillis = playlistIntervalMillis,
            )
        )
    }
}

internal data class CollectionPageBoundResolutionState(
    val requestedMediaIds: MutableSet<String> = linkedSetOf(),
)

internal data class InAppPlaylistRowRef(
    val root: WeakReference<View>,
    val title: WeakReference<TextView>?,
    val subtitle: WeakReference<TextView>?,
    val entity: WeakReference<Any>,
    val originalSubtitle: String?,
    val originalArtist: String?,
)

internal data class AlbumPageBuildData(
    val album: Any,
    val selectedItemIds: Any?,
    val mediaId: String?,
    val trackMediaIds: Set<String> = emptySet(),
)

internal data class ArtistPageBuildData(
    val artist: Any,
    val isAddMusicMode: Boolean,
    val selectedItemIds: Any?,
)

internal data class AlbumHeaderBuildCapture(
    val mediaId: String,
)

internal data class InAppLibraryEntityRef(
    val entity: WeakReference<Any>,
    val kind: InAppLibraryEntityKind,
    val originalName: String?,
    val originalArtist: String?,
    val originalAlbum: String?,
)

internal data class InAppMediaApiAttributeBinding(
    val mediaId: String,
    val kind: InAppLibraryEntityKind,
)

internal enum class MetadataPageFinalBindingKind {
    ALBUM_HEADER,
    ALBUM_ROW,
    PLAYLIST_ROW,
    ARTIST_TOP_SONG,
    ARTIST_HEADER,
}

internal enum class InAppLibraryControllerBuildStrategy {
    ALBUM_SET_DATA,
    ARTIST_SET_DATA,
    PLAYLIST_FORCE_MODEL_BUILD,
    GENERIC_REQUEST_MODEL_BUILD,
}

internal enum class InAppLibraryEntityKind {
    ALBUM,
    SONG,
    ARTIST,
}

internal data class InAppLibraryComposeCapture(
    val fragment: Any,
    val liveData: Any,
    val mediaIds: MutableSet<String> = linkedSetOf(),
)

internal data class RecyclerBindCapture(
    val adapter: WeakReference<Any>?,
    val position: Int,
    val root: WeakReference<View>?,
    val captureMetadata: Boolean,
    val mediaIds: MutableSet<String> = linkedSetOf(),
)

internal data class ArtistTopSongModelSnapshot(
    val mediaId: String,
    val originalTitle: String?,
    val originalSubtitle: String?,
    val originalArtist: String?,
)

internal data class InAppRecyclerItemRef(
    val adapter: WeakReference<Any>,
    val root: WeakReference<View>,
    val position: Int,
)

internal data class InAppContainerItemRef(
    val containerItem: WeakReference<Any>,
    val kind: InAppContainerKind,
    val originalTitle: String?,
)

internal data class InAppContainerNavigationRef(
    val containerItem: WeakReference<Any>,
    val kind: InAppContainerKind,
    val mediaId: String,
)

internal enum class InAppContainerKind {
    ARTIST,
    ALBUM,
}
