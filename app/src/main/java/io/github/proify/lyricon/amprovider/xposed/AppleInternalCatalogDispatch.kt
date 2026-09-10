/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

/**
 * Single owner of catalog scheduling state: request queues, in-flight callback
 * merging, batch running slots, and priority memory. Content caches stay in
 * [AppleInternalCatalogCaches]; native lookups stay behind the Query/Reflection
 * adapters on [AppleInternalCatalogResolver].
 *
 * Lock contract is unchanged from the pre-split resolver: pending-queue work
 * synchronizes on the queue maps themselves, in-flight work synchronizes on the
 * callback tables, and priority memory synchronizes on [requestPriorityByMediaId].
 * Batch sizes, delays, and running caps live beside the batch loops in
 * AppleInternalCatalogBatching.kt and must not be retuned here.
 *
 * All state is private: callers outside this file use the named operations below and
 * immutable snapshots for diagnostics. The `*Locked` helpers that require the caller to
 * already hold a queue monitor have been folded into the owning operations so no caller
 * can enter a half-critical-section.
 */
internal class AppleInternalCatalogDispatch {

    // ---- in-flight callback lanes (duplicate request merging) ----

    private val originalSongInFlight =
        mutableMapOf<String, MutableList<(OriginalResolution) -> Unit>>()
    private val originalCandidateCallbacks =
        mutableMapOf<String, MutableList<(Alias) -> Unit>>()
    private val catalogIdentityInFlight =
        mutableMapOf<String, MutableList<(CatalogIdentity) -> Unit>>()
    private val localizedInFlight =
        mutableMapOf<String, MutableList<(Alias?) -> Unit>>()

    /** Returns false when a resolve for the same song is already in flight. */
    fun attachOriginalSongCallback(
        mediaId: String,
        callback: (OriginalResolution) -> Unit,
    ): Boolean {
        synchronized(originalSongInFlight) {
            val callbacks = originalSongInFlight[mediaId]
            if (callbacks != null) {
                callbacks.add(callback)
                return false
            }
            originalSongInFlight[mediaId] = mutableListOf(callback)
        }
        return true
    }

    fun drainOriginalSongCallbacks(mediaId: String): List<(OriginalResolution) -> Unit> =
        synchronized(originalSongInFlight) { originalSongInFlight.remove(mediaId).orEmpty() }

    fun addOriginalCandidateCallback(mediaId: String, callback: (Alias) -> Unit) {
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.getOrPut(mediaId) { mutableListOf() }.add(callback)
        }
    }

    fun drainOriginalCandidateCallbacks(mediaId: String): List<(Alias) -> Unit> =
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.remove(mediaId).orEmpty()
        }

    fun discardOriginalCandidates(mediaId: String) {
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.remove(mediaId)
        }
    }

    /** Blank aliases publish nothing and keep the lane queued for a real candidate. */
    fun publishOriginalCandidate(mediaId: String, alias: Alias) {
        if (alias.title.isBlank() && alias.artist.isBlank()) return
        drainOriginalCandidateCallbacks(mediaId).forEach { callback -> callback(alias) }
    }

    /** Returns false when an identity lookup for the same song is already in flight. */
    fun attachCatalogIdentityCallback(
        mediaId: String,
        callback: (CatalogIdentity) -> Unit,
    ): Boolean {
        synchronized(catalogIdentityInFlight) {
            val callbacks = catalogIdentityInFlight[mediaId]
            if (callbacks != null) {
                callbacks += callback
                return false
            }
            catalogIdentityInFlight[mediaId] = mutableListOf(callback)
        }
        return true
    }

    fun drainCatalogIdentityCallbacks(mediaId: String): List<(CatalogIdentity) -> Unit> =
        synchronized(catalogIdentityInFlight) {
            catalogIdentityInFlight.remove(mediaId).orEmpty()
        }

    /**
     * Merges into an existing localized lane or opens a new one.
     *
     * [onMerged] runs **inside** the lane monitor, preserving the original lock nesting where
     * priority promotion happened while the in-flight lane lock was held. Returns false when
     * the request was merged, so the caller must not query again.
     */
    fun attachLocalizedCallback(
        requestKey: String,
        callback: (Alias?) -> Unit,
        onMerged: () -> Unit,
    ): Boolean {
        synchronized(localizedInFlight) {
            val callbacks = localizedInFlight[requestKey]
            if (callbacks != null) {
                callbacks += callback
                onMerged()
                return false
            }
            localizedInFlight[requestKey] = mutableListOf(callback)
        }
        return true
    }

    fun drainLocalizedCallbacks(requestKey: String): List<(Alias?) -> Unit> =
        synchronized(localizedInFlight) { localizedInFlight.remove(requestKey).orEmpty() }

    // ---- original entity batch lane ----

    private val originalEntityPending = LinkedHashMap<String, OriginalEntityRequest>()
    private var originalEntityBatchScheduled = false
    private var originalEntityBatchesRunning = 0
    private var originalEntityBackgroundBatchesRunning = 0

    /** Merges duplicates into one queued request; true means the caller must post the batch. */
    fun submitOriginalEntityRequest(request: OriginalEntityRequest): Boolean {
        val shouldSchedule = synchronized(originalEntityPending) {
            val existing = originalEntityPending[request.requestKey]
            originalEntityPending[request.requestKey] = if (existing == null) {
                request
            } else {
                existing.copy(
                    priority = higherPriority(existing.priority, request.priority),
                    callbacks = existing.callbacks + request.callbacks,
                )
            }
            if (
                originalEntityBatchScheduled ||
                !canStartOriginalEntityBatchLocked()
            ) {
                false
            } else {
                originalEntityBatchScheduled = true
                true
            }
        }
        return shouldSchedule
    }

    /**
     * Dequeues one same-shape batch and acquires its running slot atomically.
     * An empty result means nothing is due and the caller must return early,
     * exactly like the pre-split synchronous guard inside the batch loop.
     */
    fun takeOriginalEntityBatch(): List<OriginalEntityRequest> {
        return synchronized(originalEntityPending) {
            originalEntityBatchScheduled = false
            if (
                originalEntityPending.isEmpty() ||
                !canStartOriginalEntityBatchLocked()
            ) {
                return emptyList()
            }
            val pendingValues = originalEntityPending.values.toList()
            val first = pendingValues[
                selectNextRequestIndex(pendingValues.map(OriginalEntityRequest::priority))
                    ?: return emptyList()
            ]
            val selected = mutableListOf<OriginalEntityRequest>()
            val selectedIds = linkedSetOf<String>()
            originalEntityPending.values.forEach { request ->
                if (
                    request.priority == first.priority &&
                    request.storefront == first.storefront &&
                    request.language == first.language &&
                    request.entityType == first.entityType
                ) {
                    val newIds = request.lookupIds.filterNot(selectedIds::contains)
                    if (
                        selected.isNotEmpty() &&
                        selectedIds.size + newIds.size > ORIGINAL_ENTITY_BATCH_SIZE
                    ) return@forEach
                    selected += request
                    selectedIds += request.lookupIds
                }
            }
            selected.forEach { originalEntityPending.remove(it.requestKey) }
            originalEntityBatchesRunning += 1
            if (first.priority == RequestPriority.BACKGROUND) {
                originalEntityBackgroundBatchesRunning += 1
            }
            selected
        }
    }

    /** Releases the slot taken by [takeOriginalEntityBatch]; every exit path calls this. */
    fun endOriginalEntityBatch(firstPriority: RequestPriority) {
        synchronized(originalEntityPending) {
            originalEntityBatchesRunning -= 1
            if (firstPriority == RequestPriority.BACKGROUND) {
                originalEntityBackgroundBatchesRunning -= 1
            }
        }
    }

    fun shouldScheduleOriginalEntityBatch(): Boolean {
        val shouldSchedule = synchronized(originalEntityPending) {
            if (
                originalEntityPending.isEmpty() ||
                originalEntityBatchScheduled ||
                !canStartOriginalEntityBatchLocked()
            ) {
                false
            } else {
                originalEntityBatchScheduled = true
                true
            }
        }
        return shouldSchedule
    }

    /** Capacity check for the original-entity lane; caller holds the queue monitor. */
    private fun canStartOriginalEntityBatchLocked(): Boolean {
        val nextPriority = originalEntityPending.values
            .maxByOrNull { request -> request.priority.ordinal }
            ?.priority
            ?: return false
        return canStartRequest(
            priority = nextPriority,
            totalRunning = originalEntityBatchesRunning,
            backgroundRunning = originalEntityBackgroundBatchesRunning,
            maxRunning = MAX_ORIGINAL_ENTITY_BATCHES_RUNNING,
            maxBackgroundRunning = MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING,
        )
    }

    // ---- localized batch lane ----

    private val localizedPending = LinkedHashMap<String, LocalizedRequest>()
    private var localizedBatchScheduled = false
    private var localizedBatchesRunning = 0
    private var localizedBackgroundBatchesRunning = 0

    /** Merges duplicates by request key; true means the caller must post the batch. */
    fun submitLocalizedRequest(request: LocalizedRequest): Boolean {
        val shouldSchedule = synchronized(localizedPending) {
            val existing = localizedPending[request.requestKey]
            localizedPending[request.requestKey] = if (existing == null) {
                request
            } else {
                existing.copy(
                    priority = higherPriority(existing.priority, request.priority),
                )
            }
            if (
                localizedBatchScheduled ||
                !canStartLocalizedBatchLocked()
            ) {
                false
            } else {
                localizedBatchScheduled = true
                true
            }
        }
        return shouldSchedule
    }

    /** Same contract as [takeOriginalEntityBatch] for the localized lane. */
    fun takeLocalizedBatch(): List<LocalizedRequest> {
        return synchronized(localizedPending) {
            localizedBatchScheduled = false
            if (
                localizedPending.isEmpty() ||
                !canStartLocalizedBatchLocked()
            ) {
                return emptyList()
            }
            val pendingValues = localizedPending.values.toList()
            val first = pendingValues[
                selectNextRequestIndex(pendingValues.map(LocalizedRequest::priority))
                    ?: return emptyList()
            ]
            val selected = mutableListOf<LocalizedRequest>()
            val selectedIds = linkedSetOf<String>()
            localizedPending.values.forEach { request ->
                if (
                    request.priority == first.priority &&
                    request.storefront == first.storefront &&
                    request.language == first.language &&
                    request.entityType == first.entityType
                ) {
                    val newIds = request.lookupIds.filterNot(selectedIds::contains)
                    if (selected.isNotEmpty() && selectedIds.size + newIds.size > LOCALIZED_BATCH_SIZE) {
                        return@forEach
                    }
                    selected += request
                    selectedIds += request.lookupIds
                }
            }
            selected.forEach { localizedPending.remove(it.requestKey) }
            localizedBatchesRunning += 1
            if (first.priority == RequestPriority.BACKGROUND) {
                localizedBackgroundBatchesRunning += 1
            }
            selected
        }
    }

    /** Releases the slot taken by [takeLocalizedBatch]; every exit path calls this. */
    fun endLocalizedBatch(firstPriority: RequestPriority) {
        synchronized(localizedPending) {
            localizedBatchesRunning -= 1
            if (firstPriority == RequestPriority.BACKGROUND) {
                localizedBackgroundBatchesRunning -= 1
            }
        }
    }

    fun shouldScheduleLocalizedBatch(): Boolean {
        val shouldSchedule = synchronized(localizedPending) {
            if (
                localizedPending.isEmpty() ||
                localizedBatchScheduled ||
                !canStartLocalizedBatchLocked()
            ) {
                false
            } else {
                localizedBatchScheduled = true
                true
            }
        }
        return shouldSchedule
    }

    /** Capacity check for the localized lane; caller holds the queue monitor. */
    private fun canStartLocalizedBatchLocked(): Boolean {
        val nextPriority = localizedPending.values
            .maxByOrNull { request -> request.priority.ordinal }
            ?.priority
            ?: return false
        return canStartRequest(
            priority = nextPriority,
            totalRunning = localizedBatchesRunning,
            backgroundRunning = localizedBackgroundBatchesRunning,
            maxRunning = MAX_LOCALIZED_BATCHES_RUNNING,
            maxBackgroundRunning = MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING,
        )
    }

    // ---- priority memory and request scope ----

    private val requestPriorityByMediaId =
        object : LinkedHashMap<String, RequestPriority>(256, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, RequestPriority>?,
            ): Boolean = size > REQUEST_PRIORITY_CACHE_SIZE
        }

    @Volatile
    private var requestScopeActive = false
    private var requestScopeRevision = -1L

    /** True once a request scope has been installed; promotion then follows scoped priorities. */
    fun isRequestScopeActive(): Boolean = requestScopeActive

    /**
     * Installs a new scope generation; false means the revision was already
     * applied and the caller must skip priority work and rescheduling entirely.
     */
    fun applyRequestScope(
        revision: Long,
        visible: Set<String>,
        activePage: Set<String>,
    ): Boolean {
        synchronized(requestPriorityByMediaId) {
            if (requestScopeActive && requestScopeRevision == revision) return false
            requestScopeActive = true
            requestScopeRevision = revision
            requestPriorityByMediaId.clear()
            activePage.forEach { mediaId ->
                requestPriorityByMediaId[mediaId] = RequestPriority.ACTIVE_PAGE
            }
            visible.forEach { mediaId ->
                requestPriorityByMediaId[mediaId] = RequestPriority.VISIBLE
            }
        }
        return true
    }

    fun currentScopedPriority(mediaId: String): RequestPriority =
        synchronized(requestPriorityByMediaId) {
            requestPriorityByMediaId[mediaId.trim()] ?: RequestPriority.BACKGROUND
        }

    fun rememberRequestPriority(mediaId: String, priority: RequestPriority) {
        val normalizedId = mediaId.trim()
        if (normalizedId.isEmpty()) return
        synchronized(requestPriorityByMediaId) {
            if (requestScopeActive) {
                requestPriorityByMediaId.putIfAbsent(
                    normalizedId,
                    RequestPriority.BACKGROUND,
                )
                return
            }
            requestPriorityByMediaId[normalizedId] = higherPriority(
                requestPriorityByMediaId[normalizedId] ?: RequestPriority.BACKGROUND,
                priority,
            )
        }
    }

    fun currentRequestPriority(
        mediaId: String,
        fallback: RequestPriority,
    ): RequestPriority = synchronized(requestPriorityByMediaId) {
        if (requestScopeActive) {
            requestPriorityByMediaId[mediaId.trim()] ?: RequestPriority.BACKGROUND
        } else {
            higherPriority(requestPriorityByMediaId[mediaId.trim()] ?: fallback, fallback)
        }
    }

    fun updatePendingRequestPriorities(
        scopedPriorities: Map<String, RequestPriority>,
        onlyMediaIds: Set<String>? = null,
    ): Int {
        var changed = 0
        synchronized(localizedPending) {
            localizedPending.entries.forEach { entry ->
                val request = entry.value
                if (onlyMediaIds != null && request.mediaId !in onlyMediaIds) {
                    return@forEach
                }
                val next = scopedPriorities[request.mediaId] ?: RequestPriority.BACKGROUND
                if (request.priority != next) {
                    entry.setValue(request.copy(priority = next))
                    changed += 1
                }
            }
        }
        synchronized(originalEntityPending) {
            originalEntityPending.entries.forEach { entry ->
                val request = entry.value
                if (onlyMediaIds != null && request.mediaId !in onlyMediaIds) {
                    return@forEach
                }
                val next = scopedPriorities[request.mediaId] ?: RequestPriority.BACKGROUND
                if (request.priority != next) {
                    entry.setValue(request.copy(priority = next))
                    changed += 1
                }
            }
        }
        return changed
    }

    /**
     * Promotes already-queued requests for [mediaIds] to at least [priority].
     *
     * Both queues are updated in one owner call, in the original order (localized then
     * original), and each queue's monitor is taken and released exactly as the inline
     * version did. Returns how many queued requests actually changed per lane.
     */
    fun promotePendingRequests(mediaIds: Set<String>, priority: RequestPriority): PromotionCounts {
        var localizedPromoted = 0
        synchronized(localizedPending) {
            localizedPending.entries.forEach { entry ->
                val request = entry.value
                if (request.mediaId in mediaIds && request.priority.ordinal < priority.ordinal) {
                    entry.setValue(request.copy(priority = priority))
                    localizedPromoted += 1
                }
            }
        }
        var originalPromoted = 0
        synchronized(originalEntityPending) {
            originalEntityPending.entries.forEach { entry ->
                val request = entry.value
                if (request.mediaId in mediaIds && request.priority.ordinal < priority.ordinal) {
                    entry.setValue(request.copy(priority = priority))
                    originalPromoted += 1
                }
            }
        }
        return PromotionCounts(localized = localizedPromoted, original = originalPromoted)
    }

    // ---- immutable snapshots for diagnostics and tests ----

    data class PromotionCounts(val localized: Int, val original: Int)

    /** Queue depth plus running-slot counts for both lanes, read under their own monitors. */
    data class LaneSnapshot(
        val localizedPending: Int,
        val localizedRunning: Int,
        val originalPending: Int,
        val originalRunning: Int,
    )

    data class BatchFlags(
        val localizedScheduled: Boolean,
        val originalScheduled: Boolean,
        val originalTotalRunning: Int,
        val originalBackgroundRunning: Int,
        val localizedTotalRunning: Int,
        val localizedBackgroundRunning: Int,
    )

    fun laneSnapshot(): LaneSnapshot {
        val localized = synchronized(localizedPending) {
            localizedPending.size to localizedBatchesRunning
        }
        val original = synchronized(originalEntityPending) {
            originalEntityPending.size to originalEntityBatchesRunning
        }
        return LaneSnapshot(
            localizedPending = localized.first,
            localizedRunning = localized.second,
            originalPending = original.first,
            originalRunning = original.second,
        )
    }

    fun batchFlags(): BatchFlags = BatchFlags(
        localizedScheduled = synchronized(localizedPending) { localizedBatchScheduled },
        originalScheduled = synchronized(originalEntityPending) { originalEntityBatchScheduled },
        originalTotalRunning = synchronized(originalEntityPending) { originalEntityBatchesRunning },
        originalBackgroundRunning = synchronized(originalEntityPending) {
            originalEntityBackgroundBatchesRunning
        },
        localizedTotalRunning = synchronized(localizedPending) { localizedBatchesRunning },
        localizedBackgroundRunning = synchronized(localizedPending) {
            localizedBackgroundBatchesRunning
        },
    )

    /** Copy of queued original-entity requests, for tests and diagnostics only. */
    fun pendingOriginalEntityRequests(): List<OriginalEntityRequest> =
        synchronized(originalEntityPending) { originalEntityPending.values.toList() }

    /** Copy of queued localized requests, for tests and diagnostics only. */
    fun pendingLocalizedRequests(): List<LocalizedRequest> =
        synchronized(localizedPending) { localizedPending.values.toList() }
}
