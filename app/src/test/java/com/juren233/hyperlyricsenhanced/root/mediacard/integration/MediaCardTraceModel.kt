/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.integration

@JvmInline
value class TraceSessionId(val value: Long)

@JvmInline
value class TraceControllerId(val value: Long)

@JvmInline
value class TraceListenerId(val value: Long)

data class TraceTransitionToken(
    val sessionId: TraceSessionId,
    val epoch: Long,
    val listenerId: TraceListenerId,
    val targetFullAod: Boolean,
)

enum class TraceEventType {
    HOST_ATTACHED,
    MEDIA_BOUND,
    SNAPSHOT_UPDATED,
    FULL_AOD_STATE_CHANGED,
    NATIVE_BEGIN,
    NATIVE_UPDATE,
    NATIVE_COMPLETE,
    NATIVE_CANCEL,
    SCREEN_INTERACTIVE_CHANGED,
    FRAME_PRESENTED,
    HOST_DETACHED,
}

enum class TraceCallbackDisposition {
    NOT_APPLICABLE,
    ACCEPTED,
    STALE_REJECTED,
}

data class MediaLyricTraceSnapshot(
    val sequence: Long,
    val songKey: String?,
    val currentLineId: String?,
    val nextLineId: String?,
    val positionMs: Long,
    val playing: Boolean,
)

data class TraceEvent(
    val eventSequence: Long,
    val timestampNanos: Long,
    val frameId: Long?,
    val sessionId: TraceSessionId,
    val controllerId: TraceControllerId,
    val listenerId: TraceListenerId?,
    val token: TraceTransitionToken?,
    val snapshotSequence: Long?,
    val type: TraceEventType,
    val fraction: Float? = null,
    val callbackDisposition: TraceCallbackDisposition = TraceCallbackDisposition.NOT_APPLICABLE,
    val mutatesUi: Boolean = false,
    val visibleRootCount: Int? = null,
    val activeResourceCount: Int? = null,
    val detail: String? = null,
    val classLoaderId: String? = null,
    val playerId: TraceControllerId? = null,
)

data class TraceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "right must be >= left" }
        require(bottom >= top) { "bottom must be >= top" }
    }

    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun contains(other: TraceRect): Boolean =
        other.left >= left &&
            other.top >= top &&
            other.right <= right &&
            other.bottom <= bottom

    fun intersects(other: TraceRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

data class PresentedFrame(
    val frameId: Long,
    val timestampNanos: Long,
    val sessionId: TraceSessionId,
    val controllerId: TraceControllerId,
    val token: TraceTransitionToken?,
    val fraction: Float,
    val playerBounds: TraceRect,
    val lyricBounds: TraceRect?,
    val progressBounds: TraceRect?,
    val actionBounds: List<TraceRect>,
    val safeBottomInset: Int,
    val visibleRootCount: Int,
    val activeResourceCount: Int,
    val classLoaderId: String? = null,
    val playerId: TraceControllerId? = null,
)
