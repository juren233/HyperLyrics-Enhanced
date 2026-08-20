package com.juren233.hyperlyricsenhanced.root.island

internal data class IslandHostCleanupDecision(
    val shouldClear: Boolean,
    val shouldRelayout: Boolean,
)

internal object IslandHostCleanupPolicy {
    fun decide(
        wasRegisteredMediaIsland: Boolean,
        hasVisibleInjectedContent: Boolean,
        suppressRelayout: Boolean,
    ): IslandHostCleanupDecision {
        val shouldClear = wasRegisteredMediaIsland || hasVisibleInjectedContent
        return IslandHostCleanupDecision(
            shouldClear = shouldClear,
            shouldRelayout = shouldClear && !suppressRelayout,
        )
    }
}
