package io.github.proify.lyricon.amprovider.xposed

internal class AppleVisibleMetadataResolutionLeases(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private val lock = Any()
    private val expiresAtByMediaId = LinkedHashMap<String, Long>()

    fun mark(mediaIds: Collection<String>) = synchronized(lock) {
        val expiresAt = clock() + ttlMs
        normalizeIds(mediaIds).forEach { mediaId ->
            expiresAtByMediaId[mediaId] = expiresAt
        }
        pruneExpiredLocked()
    }

    fun contains(mediaId: String): Boolean = synchronized(lock) {
        pruneExpiredLocked()
        val normalized = normalizeId(mediaId) ?: return@synchronized false
        expiresAtByMediaId[normalized]?.let { it >= clock() } == true
    }

    private fun pruneExpiredLocked() {
        val now = clock()
        expiresAtByMediaId.entries.removeAll { (_, expiresAt) -> expiresAt < now }
    }

    private fun normalizeIds(mediaIds: Collection<String>): List<String> =
        mediaIds.mapNotNull(::normalizeId).distinct()

    private fun normalizeId(mediaId: String?): String? =
        mediaId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    companion object {
        private const val DEFAULT_TTL_MS = 30_000L
    }
}
