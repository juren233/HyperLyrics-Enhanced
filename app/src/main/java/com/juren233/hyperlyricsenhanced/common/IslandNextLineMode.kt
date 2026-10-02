/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common

/** Full-island presentation; the legacy switch still belongs to single-side mode. */
object IslandNextLineMode {
    const val UNSPECIFIED = -1
    const val OFF = 0
    const val SECOND_LINE = 1
    const val RIGHT = 2

    fun resolve(stored: Int, legacyEnabled: Boolean): Int = when (stored) {
        OFF, SECOND_LINE, RIGHT -> stored
        UNSPECIFIED -> if (legacyEnabled) SECOND_LINE else OFF
        else -> OFF
    }
}
