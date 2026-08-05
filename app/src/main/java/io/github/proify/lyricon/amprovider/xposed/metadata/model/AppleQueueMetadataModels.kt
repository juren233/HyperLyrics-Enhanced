/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.media.MediaMetadata
import android.media.session.MediaSession
import java.lang.ref.WeakReference
import java.lang.reflect.Method

internal data class FrameworkMediaSessionRefresh(
    val mediaId: String,
    val session: WeakReference<MediaSession>,
    val metadata: MediaMetadata,
)

internal data class FrameworkMediaQueueRefresh(
    val session: WeakReference<MediaSession>,
    val queue: List<MediaSession.QueueItem>,
    val mediaIds: Set<String>,
)

internal data class InAppMetadataRef(
    val metadata: WeakReference<Any>,
    val originalTitle: Any?,
    val originalArtist: Any?,
)

internal data class InAppPlaybackItemRef(
    val playbackItem: WeakReference<Any>,
    val originalTitle: Any?,
    val originalArtist: Any?,
    val originalCollectionName: String?,
    val contract: InAppPlaybackItemContract,
)

internal enum class InAppPlaybackItemContract {
    STANDARD,
    HISTORY,
}

internal enum class InAppPlaybackItemField {
    TITLE,
    ARTIST,
    ALBUM,
}

internal data class InAppPlaybackItemAccess(
    val readMember: AppleMusicRuntimeMember,
    val readViaMethod: Boolean,
    val setter: AppleMusicRuntimeMember,
)

internal data class InAppNowPlayingRefresh(
    val mediaId: String,
    val listener: WeakReference<Any>,
    val method: Method,
    val metadata: WeakReference<Any>,
)

internal data class InAppMetadataDispatcherRefresh(
    val mediaId: String,
    val dispatcher: WeakReference<Any>,
    val method: Method,
    val metadata: WeakReference<Any>,
)

internal data class InAppQueueRefresh(
    val mediaIds: Set<String>,
)

internal data class InAppQueueEntryLookup(
    val entry: Any?,
    val source: String,
)
