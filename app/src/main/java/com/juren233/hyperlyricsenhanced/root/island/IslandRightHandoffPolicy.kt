/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

/** Scope and timing of a visible right-side preview becoming the current lyric. */
internal object IslandRightHandoffPolicy {
    fun preservesLeftMetadata(
        oldFull: Boolean,
        targetFull: Boolean,
        rightHandoff: Boolean,
        previousPreservedLeft: Boolean? = null,
    ): Boolean = !oldFull && !targetFull && previousPreservedLeft != false &&
        (rightHandoff || previousPreservedLeft == true)

    fun durationMillis(requested: Long, rightHandoff: Boolean): Long =
        if (rightHandoff) maxOf(requested, 360L) else requested
}
