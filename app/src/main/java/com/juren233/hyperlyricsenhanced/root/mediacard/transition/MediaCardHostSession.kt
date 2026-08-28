/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.transition

import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationModel
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCardFullAodTransitionMode
import com.juren233.hyperlyricsenhanced.root.mediacard.host.NativeHeightLease

/**
 * Per-player content/session container. During a native transition the frozen model
 * is immutable; later store updates are retained as pending content until commit.
 */
internal class MediaCardHostSession(
    val identity: MediaCardControllerIdentity,
) {
    val coordinator = MediaCardTransitionCoordinator(identity)

    var stablePresentation: LyricPresentationModel? = null
        private set
    var pendingPresentation: LyricPresentationModel? = null
        private set
    var frozenPresentation: LyricPresentationModel? = null
        private set

    fun attach(presentation: LyricPresentationModel?): MediaCardTransitionResult {
        presentation?.let { stablePresentation = it }
        return coordinator.attach(presentation?.snapshotSequence ?: stablePresentation?.snapshotSequence ?: 0L)
    }

    fun attachHeightLease(lease: NativeHeightLease?) {
        coordinator.attachHeightLease(lease)
    }

    fun acceptPresentation(presentation: LyricPresentationModel): Boolean {
        val active = coordinator.activeToken()
        if (active != null) {
            if (presentation.snapshotSequence < active.snapshotSequence) return false
            val pending = pendingPresentation
            if (pending == null || presentation.snapshotSequence >= pending.snapshotSequence) {
                pendingPresentation = presentation
            }
            return true
        }
        if (presentation.snapshotSequence < (stablePresentation?.snapshotSequence ?: -1L)) {
            return false
        }
        stablePresentation = presentation
        pendingPresentation = null
        return true
    }

    fun begin(
        listener: Any,
        targetFullAod: Boolean,
        mode: MediaCardFullAodTransitionMode,
    ): MediaCardTransitionResult {
        // A reversal must continue to display the already committed model. Do not
        // clear pending content: it belongs to a newer song/translation generation
        // and is committed only after the new transaction reaches its target.
        if (frozenPresentation == null) {
            frozenPresentation = stablePresentation ?: pendingPresentation
        }
        return coordinator.begin(
            listener = listener,
            targetFullAod = targetFullAod,
            mode = mode,
            snapshotSequence = frozenPresentation?.snapshotSequence ?: 0L,
        )
    }

    fun complete(token: MediaCardTransitionToken): MediaCardTransitionResult {
        val result = coordinator.complete(token)
        if (result.accepted) {
            val candidates = listOfNotNull(stablePresentation, frozenPresentation, pendingPresentation)
            stablePresentation = candidates.maxByOrNull { it.snapshotSequence }
            pendingPresentation = null
            frozenPresentation = null
        }
        return result
    }

    fun cancel(token: MediaCardTransitionToken): MediaCardTransitionResult {
        val result = coordinator.cancel(token)
        if (result.accepted) {
            // Keep a newer pending snapshot for the next stable refresh. A native
            // cancel must restore the current visual side without losing late data.
            if ((pendingPresentation?.snapshotSequence ?: -1L) <=
                (stablePresentation?.snapshotSequence ?: -1L)
            ) {
                pendingPresentation = null
            }
            frozenPresentation = null
        }
        return result
    }

    fun detach(): MediaCardTransitionResult {
        val result = coordinator.detach()
        stablePresentation = null
        pendingPresentation = null
        frozenPresentation = null
        return result
    }

    /** Invalidates host callbacks while retaining content across a view rebind. */
    fun rebind(stableFullAod: Boolean): MediaCardTransitionResult = coordinator.rebind(
        snapshotSequence = stablePresentation?.snapshotSequence ?: frozenPresentation?.snapshotSequence ?: 0L,
        stableFullAod = stableFullAod,
    )

    fun recover(stableFullAod: Boolean): MediaCardTransitionResult = rebind(stableFullAod)
}
