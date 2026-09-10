/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Owns the state of one Apple lyrics scroll/presentation transition.
 *
 * The owner deliberately stores only immutable snapshots, weak host references and listener
 * identity; reflection, adapter inspection and host callbacks stay in the extension functions.
 * Callers cannot replace a snapshot, a host token or a listener without a named transition.
 * Hosts are held as opaque weak tokens because the owner only needs liveness and identity.
 */
internal class AppleLyricsScrollPresentationState {
    internal data class ScrollSnapshot(
        val firstPosition: Int,
        val firstOffset: Int,
        val activeAdapterPosition: Int?,
        val activeAdapterOffset: Int?,
        val playbackPositionMs: Long?,
        val sourceTimingDebug: String? = null,
        val adapterTimingDebug: String? = null,
    )

    internal data class PendingRestore(
        val host: WeakReference<Any>,
        val listener: ViewTreeObserver.OnPreDrawListener,
    )

    internal data class ResolvedRestoreTarget(
        val layoutManager: Any,
        val itemCount: Int,
        val anchor: AppleLyricsRestoreAnchor,
        val activePositions: Set<Int>,
        val playbackMappedPosition: Int?,
        val sourceTimingDebug: String? = null,
        val adapterTimingDebug: String? = null,
    )

    private var snapshot: ScrollSnapshot? = null
    private var snapshotSongId: String? = null
    private var preservedTopSnapshotSongId: String? = null
    private var pendingRestore: PendingRestore? = null
    private var presentationInFlight = false
    private val trackedHosts = Collections.newSetFromMap(
        WeakHashMap<Any, Boolean>()
    )

    @Synchronized
    fun markTrackedIfNew(host: Any): Boolean = trackedHosts.add(host)

    @Synchronized
    fun isPresentationInFlight(): Boolean = presentationInFlight

    @Synchronized
    fun beginPresentation() {
        presentationInFlight = true
    }

    @Synchronized
    fun finishPresentation() {
        presentationInFlight = false
    }

    @Synchronized
    fun isRestorePending(): Boolean = pendingRestore?.host?.get() != null

    @Synchronized
    fun snapshotFor(songId: String): ScrollSnapshot? =
        snapshot?.takeIf { snapshotSongId == songId }

    @Synchronized
    fun shouldPreserveTopSnapshot(songId: String, capturedPosition: Int): Boolean =
        preservedTopSnapshotSongId == songId && capturedPosition == 0 &&
            snapshotFor(songId)?.firstPosition?.let { it > 0 } == true

    @Synchronized
    fun clearPreservedTopSnapshotIfScrolled(songId: String, capturedPosition: Int) {
        if (preservedTopSnapshotSongId == songId && capturedPosition > 0) {
            preservedTopSnapshotSongId = null
        }
    }

    @Synchronized
    fun acceptSnapshot(songId: String, value: ScrollSnapshot) {
        snapshot = value
        snapshotSongId = songId
    }

    @Synchronized
    fun clearSnapshot() {
        snapshot = null
        snapshotSongId = null
    }

    @Synchronized
    fun markRestoreFailed(songId: String) {
        preservedTopSnapshotSongId = songId
    }

    @Synchronized
    fun setPendingRestore(host: Any, listener: ViewTreeObserver.OnPreDrawListener) {
        pendingRestore = PendingRestore(WeakReference(host), listener)
    }

    @Synchronized
    fun takePendingRestore(): PendingRestore? = pendingRestore.also { pendingRestore = null }

    /** Clears the pending entry only when it belongs to [listener]; a newer restore is left intact. */
    @Synchronized
    fun clearPendingRestoreIf(listener: ViewTreeObserver.OnPreDrawListener): Boolean {
        if (pendingRestore?.listener !== listener) return false
        pendingRestore = null
        return true
    }
}

/**
 * Owns active-line rescheduling and the last applied row.
 *
 * [stop] deliberately only invalidates the last applied row: a callback already queued keeps
 * the update loop alive for one more turn, which is the behaviour this component was
 * extracted from. Posting is injected so the scheduling contract stays verifiable.
 */
internal class AppleLyricsActiveLineUpdateController(
    private val post: (Runnable, Long) -> Unit,
    private val intervalMs: Long,
    private val update: () -> Unit,
) {
    private var scheduled = false
    private var lastAppliedIndex = -1
    private val runnable = Runnable {
        synchronized(this) {
            scheduled = false
        }
        update()
    }

    @Synchronized
    fun schedule() {
        if (scheduled) return
        scheduled = true
        post(runnable, intervalMs)
    }

    @Synchronized
    fun lastAppliedIndex(): Int = lastAppliedIndex

    @Synchronized
    fun markApplied(index: Int) {
        lastAppliedIndex = index
    }

    @Synchronized
    fun stop() {
        lastAppliedIndex = -1
    }
}
