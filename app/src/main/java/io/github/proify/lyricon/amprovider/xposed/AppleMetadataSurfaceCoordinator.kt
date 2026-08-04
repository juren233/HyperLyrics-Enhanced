package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference

internal class AppleMetadataSurfaceCoordinator(
    private val clock: () -> Long,
    private val visibleTtlMs: Long = DEFAULT_VISIBLE_TTL_MS,
    private val maxPageMediaIds: Int = DEFAULT_MAX_PAGE_MEDIA_IDS,
    private val maxVisibleMediaIds: Int = DEFAULT_MAX_VISIBLE_MEDIA_IDS,
) {
    private val lock = Any()
    private var activeOwner = WeakReference<Any>(null)
    private var generation = 0L
    private var scopeRevision = 0L
    private val pageMediaIds = LinkedHashSet<String>()
    private val visibleMediaIds = LinkedHashMap<String, Long>()
    private var playbackMediaId: String? = null

    fun onSurfaceResumed(owner: Any): SurfaceSnapshot = synchronized(lock) {
        if (activeOwner.get() !== owner) {
            generation += 1L
            scopeRevision += 1L
            activeOwner = WeakReference(owner)
            pageMediaIds.clear()
            visibleMediaIds.clear()
        }
        snapshotLocked()
    }

    fun onSurfacePaused(owner: Any): SurfaceSnapshot = synchronized(lock) {
        if (activeOwner.get() === owner) {
            generation += 1L
            scopeRevision += 1L
            activeOwner = WeakReference(null)
            pageMediaIds.clear()
            visibleMediaIds.clear()
        }
        snapshotLocked()
    }

    fun markCurrentPage(mediaIds: Collection<String>): SurfaceSnapshot = synchronized(lock) {
        pruneExpiredVisibleLocked()
        if (activeOwner.get() == null) return@synchronized snapshotLocked()
        val normalized = normalizeIds(mediaIds)
        var changed = false
        normalized.forEach { mediaId ->
            val existed = pageMediaIds.remove(mediaId)
            pageMediaIds.add(mediaId)
            if (!existed) changed = true
        }
        while (pageMediaIds.size > maxPageMediaIds) {
            val iterator = pageMediaIds.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
            changed = true
        }
        if (changed) scopeRevision += 1L
        snapshotLocked()
    }

    fun markVisible(mediaIds: Collection<String>): SurfaceSnapshot = synchronized(lock) {
        val now = clock()
        pruneExpiredVisibleLocked(now)
        var changed = false
        normalizeIds(mediaIds).forEach { mediaId ->
            val previous = visibleMediaIds.remove(mediaId)
            visibleMediaIds[mediaId] = now
            if (previous == null) changed = true
        }
        while (visibleMediaIds.size > maxVisibleMediaIds) {
            val iterator = visibleMediaIds.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
            changed = true
        }
        if (changed) scopeRevision += 1L
        snapshotLocked()
    }

    fun setPlaybackMediaId(mediaId: String?): SurfaceSnapshot = synchronized(lock) {
        pruneExpiredVisibleLocked()
        val normalized = normalizeId(mediaId)
        if (playbackMediaId != normalized) {
            playbackMediaId = normalized
            scopeRevision += 1L
        }
        snapshotLocked()
    }

    fun requestContext(mediaId: String): RequestContext = synchronized(lock) {
        pruneExpiredVisibleLocked()
        val normalized = normalizeId(mediaId)
        RequestContext(
            generation = generation,
            priority = priorityForLocked(normalized),
        )
    }

    fun allowsRefresh(requestGeneration: Long, mediaId: String): Boolean = synchronized(lock) {
        pruneExpiredVisibleLocked()
        val normalized = normalizeId(mediaId) ?: return@synchronized false
        normalized == playbackMediaId ||
            (requestGeneration == generation &&
                priorityForLocked(normalized) !=
                AppleInternalCatalogResolver.RequestPriority.BACKGROUND)
    }

    fun snapshot(): SurfaceSnapshot = synchronized(lock) {
        pruneExpiredVisibleLocked()
        snapshotLocked()
    }

    private fun priorityForLocked(
        mediaId: String?,
    ): AppleInternalCatalogResolver.RequestPriority = when {
        mediaId == null ->
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND
        mediaId == playbackMediaId || mediaId in visibleMediaIds ->
            AppleInternalCatalogResolver.RequestPriority.VISIBLE
        mediaId in pageMediaIds ->
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE
        else ->
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND
    }

    private fun snapshotLocked(): SurfaceSnapshot {
        val visible = LinkedHashSet(visibleMediaIds.keys)
        playbackMediaId?.let(visible::add)
        return SurfaceSnapshot(
            generation = generation,
            scopeRevision = scopeRevision,
            hasActiveSurface = activeOwner.get() != null,
            activePageMediaIds = pageMediaIds.toSet(),
            visibleMediaIds = visible,
            playbackMediaId = playbackMediaId,
        )
    }

    private fun pruneExpiredVisibleLocked(now: Long = clock()) {
        var changed = false
        val iterator = visibleMediaIds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > visibleTtlMs) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) scopeRevision += 1L
    }

    private fun normalizeIds(mediaIds: Collection<String>): List<String> =
        mediaIds.asSequence()
            .mapNotNull(::normalizeId)
            .distinct()
            .toList()

    private fun normalizeId(mediaId: String?): String? =
        mediaId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    data class RequestContext(
        val generation: Long,
        val priority: AppleInternalCatalogResolver.RequestPriority,
    )

    data class SurfaceSnapshot(
        val generation: Long,
        val scopeRevision: Long,
        val hasActiveSurface: Boolean,
        val activePageMediaIds: Set<String>,
        val visibleMediaIds: Set<String>,
        val playbackMediaId: String?,
    )

    companion object {
        private const val DEFAULT_VISIBLE_TTL_MS = 2_000L
        private const val DEFAULT_MAX_PAGE_MEDIA_IDS = 256
        private const val DEFAULT_MAX_VISIBLE_MEDIA_IDS = 96
    }
}
