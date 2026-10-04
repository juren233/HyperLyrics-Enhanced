package com.juren233.hyperlyricsenhanced.root.utils

/** Bounded, change-only summaries. Position ticks and repeat callbacks do not become INFO floods. */
internal class DiagnosticDecisionGate(
    private val minIntervalMs: Long = 1_000L,
    private val maxKeys: Int = 128,
) {
    private data class Entry(val signature: String, val atMs: Long)
    private val entries = linkedMapOf<String, Entry>()

    init {
        require(minIntervalMs >= 0)
        require(maxKeys > 0)
    }

    @Synchronized
    fun shouldLog(key: String, signature: String, nowMs: Long): Boolean {
        val previous = entries[key]
        if (previous != null && (previous.signature == signature ||
                nowMs - previous.atMs < minIntervalMs)) return false
        if (previous == null && entries.size >= maxKeys) entries.remove(entries.keys.first())
        entries[key] = Entry(signature, nowMs)
        return true
    }

    @Synchronized
    fun clear(key: String? = null) {
        if (key == null) entries.clear()
        else entries.keys.removeAll { it == key || it.startsWith("$key/") }
    }
}
