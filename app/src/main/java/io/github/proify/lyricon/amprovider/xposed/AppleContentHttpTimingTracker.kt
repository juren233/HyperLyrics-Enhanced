package io.github.proify.lyricon.amprovider.xposed

import java.util.IdentityHashMap

internal class AppleContentHttpTimingTracker(
    private val clock: () -> Long,
    private val summaryIntervalMs: Long = DEFAULT_SUMMARY_INTERVAL_MS,
    private val slowRequestMs: Long = DEFAULT_SLOW_REQUEST_MS,
) {
    private val lock = Any()
    private val requests = IdentityHashMap<Any, StartedRequest>()
    private var windowStartedAtMs = clock()
    private val windowAccumulators = mutableMapOf(
        Source.NATIVE to WindowAccumulator(),
        Source.MODULE to WindowAccumulator(),
    )

    fun start(
        requestKey: Any,
        descriptor: RequestDescriptor,
    ): StartSnapshot = synchronized(lock) {
        requests[requestKey] = StartedRequest(
            startedAtMs = clock(),
            descriptor = descriptor,
        )
        StartSnapshot(
            descriptor = descriptor,
            sourceInFlight = inFlightCountLocked(descriptor.source),
            totalInFlight = requests.size,
        )
    }

    fun finish(
        requestKey: Any,
        statusCode: Int?,
    ): Completion? = synchronized(lock) {
        val started = requests.remove(requestKey) ?: return@synchronized null
        val now = clock()
        val elapsedMs = (now - started.startedAtMs).coerceAtLeast(0L)
        windowAccumulators.getValue(started.descriptor.source).record(
            elapsedMs = elapsedMs,
            category = started.descriptor.category,
            slowRequestMs = slowRequestMs,
        )
        val sourceInFlight = inFlightCountLocked(started.descriptor.source)
        val totalInFlight = requests.size
        val summary = if (now - windowStartedAtMs >= summaryIntervalMs) {
            buildSummaryLocked(now)
        } else {
            null
        }
        Completion(
            descriptor = started.descriptor,
            elapsedMs = elapsedMs,
            statusCode = statusCode,
            sourceInFlight = sourceInFlight,
            totalInFlight = totalInFlight,
            isSlow = elapsedMs >= slowRequestMs,
            summary = summary,
        )
    }

    private fun buildSummaryLocked(now: Long): Summary {
        val elapsedMs = (now - windowStartedAtMs).coerceAtLeast(0L)
        val native = windowAccumulators.getValue(Source.NATIVE).snapshot(
            inFlight = inFlightCountLocked(Source.NATIVE),
        )
        val module = windowAccumulators.getValue(Source.MODULE).snapshot(
            inFlight = inFlightCountLocked(Source.MODULE),
        )
        windowAccumulators.values.forEach(WindowAccumulator::clear)
        windowStartedAtMs = now
        return Summary(
            windowMs = elapsedMs,
            native = native,
            module = module,
            totalInFlight = requests.size,
        )
    }

    private fun inFlightCountLocked(source: Source): Int =
        requests.values.count { request -> request.descriptor.source == source }

    enum class Source {
        NATIVE,
        MODULE,
    }

    data class RequestDescriptor(
        val source: Source,
        val category: String,
        val storefront: String?,
        val pendingModuleRequests: Int,
    )

    data class StartSnapshot(
        val descriptor: RequestDescriptor,
        val sourceInFlight: Int,
        val totalInFlight: Int,
    )

    data class Completion(
        val descriptor: RequestDescriptor,
        val elapsedMs: Long,
        val statusCode: Int?,
        val sourceInFlight: Int,
        val totalInFlight: Int,
        val isSlow: Boolean,
        val summary: Summary?,
    )

    data class Summary(
        val windowMs: Long,
        val native: SourceStats,
        val module: SourceStats,
        val totalInFlight: Int,
    )

    data class SourceStats(
        val completed: Int,
        val averageElapsedMs: Long,
        val maxElapsedMs: Long,
        val slowRequests: Int,
        val inFlight: Int,
        val categories: Map<String, Int>,
    )

    private data class StartedRequest(
        val startedAtMs: Long,
        val descriptor: RequestDescriptor,
    )

    private class WindowAccumulator {
        private var completed = 0
        private var totalElapsedMs = 0L
        private var maxElapsedMs = 0L
        private var slowRequests = 0
        private val categories = linkedMapOf<String, Int>()

        fun record(
            elapsedMs: Long,
            category: String,
            slowRequestMs: Long,
        ) {
            completed += 1
            totalElapsedMs += elapsedMs
            maxElapsedMs = maxOf(maxElapsedMs, elapsedMs)
            if (elapsedMs >= slowRequestMs) slowRequests += 1
            categories[category] = (categories[category] ?: 0) + 1
        }

        fun snapshot(inFlight: Int): SourceStats = SourceStats(
            completed = completed,
            averageElapsedMs = if (completed == 0) 0L else totalElapsedMs / completed,
            maxElapsedMs = maxElapsedMs,
            slowRequests = slowRequests,
            inFlight = inFlight,
            categories = categories.toMap(),
        )

        fun clear() {
            completed = 0
            totalElapsedMs = 0L
            maxElapsedMs = 0L
            slowRequests = 0
            categories.clear()
        }
    }

    companion object {
        private const val DEFAULT_SUMMARY_INTERVAL_MS = 1_000L
        private const val DEFAULT_SLOW_REQUEST_MS = 1_500L
    }
}
