/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import kotlin.math.abs

internal object SourceClockUpdatePolicy {
    private const val QQ_HD_PACKAGE = "com.tencent.qqmusicpad"
    private const val SYSTEM_SEEK_DIVERGENCE_MS = 5_000L

    /** A newer, widely different QQ HD MediaSession anchor is a seek the source missed. */
    fun preferSystemAfterSeek(
        packageName: String?,
        sourcePosition: Long?,
        sourceSampleAtMs: Long,
        systemPosition: Long?,
        systemStateUpdatedAtMs: Long,
    ): Boolean = packageName == QQ_HD_PACKAGE &&
        sourcePosition != null && systemPosition != null &&
        sourceSampleAtMs > 0L && systemStateUpdatedAtMs > sourceSampleAtMs &&
        abs(systemPosition - sourcePosition) >= SYSTEM_SEEK_DIVERGENCE_MS

    enum class Action {
        IGNORE_DUPLICATE,
        IGNORE_BACKTRACK,
        ANCHOR,
        SEEK,
    }

    fun decide(
        explicitSeek: Boolean,
        previousAnchorPosition: Long?,
        projectedPosition: Long?,
        incomingPosition: Long,
    ): Action {
        if (explicitSeek) return Action.SEEK
        if (previousAnchorPosition == null) return Action.ANCHOR
        if (incomingPosition == previousAnchorPosition) return Action.IGNORE_DUPLICATE
        if (projectedPosition == null) return Action.ANCHOR

        val deltaFromProjection = incomingPosition - projectedPosition
        if (deltaFromProjection < 0 && -deltaFromProjection <= SMALL_BACKTRACK_TOLERANCE_MS) {
            return Action.IGNORE_BACKTRACK
        }
        return if (abs(deltaFromProjection) >= DISCONTINUITY_THRESHOLD_MS) {
            Action.SEEK
        } else {
            Action.ANCHOR
        }
    }

    private const val SMALL_BACKTRACK_TOLERANCE_MS = 1_500L
    private const val DISCONTINUITY_THRESHOLD_MS = 1_500L
}
