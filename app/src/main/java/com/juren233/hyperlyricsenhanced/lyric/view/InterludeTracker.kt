/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

internal class InterludeTracker(
    lines: List<TimedLine> = emptyList(),
    private val minGapMs: Long = 7_000L
) {

    private val firstLyric = lines.firstOrNull { !it.isTitleLine() }

    fun evaluate(
        posMs: Long,
        lineAtOrBefore: TimedLine?,
        current: Interlude?
    ): Interlude? {
        current?.let { if (posMs in it.start until it.end) return it }

        val previous = lineAtOrBefore?.takeUnless { it.isTitleLine() }
        if (previous != null && posMs <= previous.end) return null

        val next = lineAtOrBefore?.nextLyric() ?: firstLyric ?: return null

        val start = previous?.end?.plus(1L) ?: 0L
        val gap = next.begin - (previous?.end ?: 0L)
        if (gap < minGapMs) return null
        if (posMs !in start until next.begin) return null

        return Interlude(
            start = start,
            end = next.begin,
            type = if (previous == null) Type.INTRO else Type.INTERLUDE,
            next = next
        )
    }

    private fun TimedLine.nextLyric(): TimedLine? {
        var candidate = next
        while (candidate?.isTitleLine() == true) candidate = candidate.next
        return candidate
    }

    private fun TimedLine.isTitleLine(): Boolean =
        metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true

    enum class Type {
        INTRO,
        INTERLUDE
    }

    data class Interlude(
        val start: Long,
        val end: Long,
        val type: Type,
        val next: TimedLine
    ) {
        val duration get() = end - start
    }
}
