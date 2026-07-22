package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song

internal object OnlineFallbackSongMapper {
    private const val DEFAULT_LAST_LINE_DURATION_MS = 5_000L

    fun map(baseSong: Song, lines: List<LrcLine>): Song? {
        val normalizedLines = lines
            .asSequence()
            .filter { it.startTimeMs >= 0L && it.content.isNotBlank() }
            .sortedBy(LrcLine::startTimeMs)
            .distinctBy(LrcLine::startTimeMs)
            .toList()
        if (normalizedLines.isEmpty()) return null

        val richLines = normalizedLines.mapIndexed { index, line ->
            val nextStart = normalizedLines
                .getOrNull(index + 1)
                ?.startTimeMs
                ?.takeIf { it > line.startTimeMs }
            val end = nextStart
                ?: baseSong.duration.takeIf { it > line.startTimeMs }
                ?: (line.startTimeMs + DEFAULT_LAST_LINE_DURATION_MS)
            RichLyricLine(
                begin = line.startTimeMs,
                end = end,
                duration = end - line.startTimeMs,
                text = line.content,
                translation = line.translation?.trim()?.takeIf(String::isNotEmpty)
            )
        }

        return baseSong.copy(
            duration = baseSong.duration.takeIf { it > 0L } ?: richLines.last().end,
            lyrics = richLines
        )
    }
}
