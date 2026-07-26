/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf

internal class SongPreprocessor(
    private val placeholder: TitleSlot,
    private val mergeOverlappingLyrics: Boolean = true
) {

    companion object {
        internal const val KEY_TITLE_LINE = "TitleLine"
        private const val MIN_OVERLAP_GROUP_MS = 100L
    }

    fun prepare(song: Song): List<TimedLine> {
        val filled = fillGap(song)
        val preparedLyrics = if (mergeOverlappingLyrics) {
            groupOverlappingLyrics(filled.lyrics.orEmpty())
        } else {
            filled.lyrics.orEmpty()
        }
        val lines = mutableListOf<TimedLine>()
        var prev: TimedLine? = null
        preparedLyrics.forEach { lyric ->
            val tl = TimedLine(lyric).also {
                it.previous = prev
                prev?.next = it
            }
            lines.add(tl)
            prev = tl
        }
        return lines
    }

    private fun groupOverlappingLyrics(
        lyrics: List<IRichLyricLine>
    ): List<IRichLyricLine> {
        if (lyrics.size < 2) return lyrics

        val grouped = mutableListOf<IRichLyricLine>()
        var index = 0
        while (index < lyrics.size) {
            val primary = lyrics[index]
            val secondary = lyrics.getOrNull(index + 1)
            if (secondary != null && canGroup(primary, secondary)) {
                grouped += mergeOverlappingLines(primary, secondary)
                index += 2
            } else {
                grouped += primary
                index += 1
            }
        }
        return grouped
    }

    private fun canGroup(
        primary: IRichLyricLine,
        secondary: IRichLyricLine
    ): Boolean {
        if (primary.isTitleLine() || secondary.isTitleLine()) return false
        val overlap = minOf(primary.end, secondary.end) -
            maxOf(primary.begin, secondary.begin)
        return overlap >= MIN_OVERLAP_GROUP_MS
    }

    private fun mergeOverlappingLines(
        primary: IRichLyricLine,
        secondary: IRichLyricLine
    ): IRichLyricLine {
        val groupEnd = maxOf(primary.end, secondary.end)
        val primaryBackingTranslation = primary.metadata?.getString(
            LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION
        )
        val secondaryBackingTranslation = secondary.metadata?.getString(
            LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION
        )
        val metadata = lyricMetadataOf(
            *(primary.metadata?.entries?.map { it.key to it.value } ?: emptyList())
                .toTypedArray(),
            LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP to "true",
            LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT to
                secondary.isAlignedRight.toString(),
            LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to secondary.translation,
            LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING to primary.secondary,
            LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_TRANSLATION to
                primaryBackingTranslation,
            LyricMetadataKeys.OVERLAPPING_SECONDARY_TRANSLATION to
                secondary.translation,
            LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING to secondary.secondary,
            LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_TRANSLATION to
                secondaryBackingTranslation,
        )
        return RichLyricLine(
            begin = primary.begin,
            end = groupEnd,
            duration = groupEnd - primary.begin,
            isAlignedRight = primary.isAlignedRight,
            metadata = metadata,
            text = primary.text,
            words = primary.words.withFallbackTiming(primary),
            secondary = secondary.text,
            secondaryWords = secondary.words.withFallbackTiming(secondary),
            translation = primary.translation,
            translationWords = primary.translationWords,
            roma = primary.roma,
        )
    }

    private fun List<LyricWord>?.withFallbackTiming(
        line: IRichLyricLine
    ): List<LyricWord>? {
        if (!isNullOrEmpty() || line.text.isNullOrBlank()) return this
        return listOf(
            LyricWord(
                begin = line.begin,
                end = line.end,
                duration = line.duration,
                text = line.text,
            )
        )
    }

    private fun fillGap(song: Song): Song {
        val title = songTitle(song) ?: return song
        val lyrics = song.lyrics?.toMutableList() ?: mutableListOf()
        if (lyrics.isEmpty()) {
            val d = if (song.duration > 0) song.duration else Long.MAX_VALUE
            lyrics.add(titleLine(d, d, title))
        } else {
            val first = lyrics.first()
            if (first.begin > 0) {
                var end = first.begin
                if (end > 1) end--
                lyrics.add(0, titleLine(end, end, title))
            }
        }
        song.lyrics = lyrics
        return song
    }

    private fun titleLine(end: Long, duration: Long, text: String) =
        RichLyricLine(end = end, duration = duration, text = text).apply {
            metadata = lyricMetadataOf(KEY_TITLE_LINE to "true")
        }

    private fun songTitle(song: Song): String? {
        val name = song.name
        val artist = song.artist
        return when (placeholder) {
            TitleSlot.NONE -> null
            TitleSlot.NAME_ARTIST -> when {
                !name.isNullOrBlank() && !artist.isNullOrBlank() -> "$name - $artist"
                !name.isNullOrBlank() -> name
                else -> null
            }
            TitleSlot.NAME -> name?.takeIf { it.isNotBlank() }
        }
    }
}

internal class TimedLine(val line: IRichLyricLine) : IRichLyricLine by line {
    var previous: TimedLine? = null
    var next: TimedLine? = null
}
