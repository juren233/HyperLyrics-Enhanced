/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsLine

/**
 * 把三方在线源命中结果映射为「无歌词歌曲补充」回传载荷。
 * 逐字时间轴优先保留（[LyricsLine.words]），无逐字信息时行级文本仍可回退显示。
 */
internal object AppleMissingLyricsSongMapper {
    private const val DEFAULT_LAST_LINE_DURATION_MS = 5_000L

    fun map(baseSong: Song, lines: List<LyricsLine>): Song? {
        return map(
            baseSong = baseSong,
            wordLines = lines,
            lrcLines = null,
            sourceInfo = null,
        )
    }

    fun map(
        baseSong: Song,
        wordLines: List<LyricsLine>?,
        lrcLines: List<LrcLine>?,
        sourceInfo: AppleMissingLyricsSourceInfo?,
        rawAppleTtml: String? = null,
        sourceLyricId: String? = null,
        sourceLyricSha256: String? = null,
    ): Song? {
        val inputLines = wordLines.orEmpty()
        val fetchedTranslationLines = lrcLines.orEmpty()
        val preserveSourceWhitespace = sourceInfo?.selectedSource == "LB"
        val previousTranslationLines = baseSong.lyrics.orEmpty()
        val normalizedLines = inputLines
            .asSequence()
            .filter { line ->
                line.start >= 0L &&
                    line.words.joinToString("") { it.text }.trim().isNotEmpty()
            }
            .sortedBy(LyricsLine::start)
            .distinctBy(LyricsLine::start)
            .toList()
        val richLines = if (normalizedLines.isNotEmpty()) {
            normalizedLines.mapIndexed { index, line ->
                val words = line.words.map { word ->
                    LyricWord(
                        begin = word.start,
                        end = word.end,
                        text = word.text,
                    )
                }
                val sourceLineText = fetchedTranslationLines
                    .firstOrNull { it.startTimeMs == line.start }
                    ?.content
                    ?.takeIf(String::isNotBlank)
                val joinedWordText = words.joinToString("") { it.text.orEmpty() }
                val text = (sourceLineText ?: joinedWordText).let { value ->
                    if (preserveSourceWhitespace) value else value.trim()
                }
                val nextStart = normalizedLines
                    .getOrNull(index + 1)
                    ?.start
                    ?.takeIf { it > line.start }
                val end = if (preserveSourceWhitespace) {
                    line.end.takeIf { it > line.start }
                } else {
                    null
                } ?: nextStart
                    ?: baseSong.duration.takeIf { it > line.start }
                    ?: (line.start + DEFAULT_LAST_LINE_DURATION_MS)
                RichLyricLine(
                    begin = line.start,
                    end = end,
                    duration = end - line.start,
                    text = text,
                    words = words,
                    translation = findTranslation(
                        startTimeMs = line.start,
                        text = text,
                        fetchedLines = fetchedTranslationLines,
                        previousLines = previousTranslationLines,
                    ),
                )
            }
        } else {
            val normalizedLrcLines = lrcLines.orEmpty()
                .asSequence()
                .filter { it.startTimeMs >= 0L && it.content.trim().isNotEmpty() }
                .sortedBy(LrcLine::startTimeMs)
                .distinctBy(LrcLine::startTimeMs)
                .toList()
            normalizedLrcLines.mapIndexed { index, line ->
                val nextStart = normalizedLrcLines
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
                    text = if (preserveSourceWhitespace) line.content else line.content.trim(),
                    words = emptyList(),
                    translation = line.translation?.trim()?.takeIf(String::isNotEmpty)
                        ?: findTranslation(
                            startTimeMs = line.startTimeMs,
                            text = line.content,
                            fetchedLines = fetchedTranslationLines,
                            previousLines = previousTranslationLines,
                        ),
                )
            }
        }
        if (richLines.isEmpty()) return null

        val lunaBeatMetadataKeys = setOf(
            LyricMetadataKeys.LUNA_BEAT_RAW_TTML,
            LyricMetadataKeys.LUNA_BEAT_HUB_ID,
            LyricMetadataKeys.LUNA_BEAT_TTML_SHA256,
        )
        val metadataPairs = buildList<Pair<String, String?>> {
            addAll(baseSong.metadata.orEmpty().entries
                .filterNot { it.key in lunaBeatMetadataKeys }
                .map { it.key to it.value })
            add(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true")
            sourceInfo?.selectedSource?.let {
                add(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to it)
            }
            sourceInfo?.statuses?.let {
                AppleMissingLyricsSourceMetadata.encodeStatuses(it)?.let { encoded ->
                    add(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES to encoded)
                }
            }
            if (sourceInfo?.selectedSource == "LB" && !rawAppleTtml.isNullOrEmpty()) {
                add(LyricMetadataKeys.LUNA_BEAT_RAW_TTML to rawAppleTtml)
                sourceLyricId?.takeIf(String::isNotBlank)?.let {
                    add(LyricMetadataKeys.LUNA_BEAT_HUB_ID to it)
                }
                sourceLyricSha256?.takeIf(String::isNotBlank)?.let {
                    add(LyricMetadataKeys.LUNA_BEAT_TTML_SHA256 to it.lowercase())
                }
            }
        }
        return baseSong.copy(
            duration = baseSong.duration.takeIf { it > 0L } ?: richLines.last().end,
            metadata = lyricMetadataOf(*metadataPairs.toTypedArray()),
            lyrics = richLines,
        )
    }

    private fun findTranslation(
        startTimeMs: Long,
        text: String?,
        fetchedLines: List<LrcLine>,
        previousLines: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
    ): String? {
        fun meaningful(value: String?): String? = value
            ?.trim()
            ?.takeIf(OnlineTranslationContentPolicy::isMeaningful)

        val normalizedText = normalizeText(text)
        val fetched = fetchedLines.firstOrNull { it.startTimeMs == startTimeMs }
            ?.let { meaningful(it.translation) }
            ?: fetchedLines
                .asSequence()
                .filter { normalizeText(it.content) == normalizedText }
                .minByOrNull { kotlin.math.abs(it.startTimeMs - startTimeMs) }
                ?.let { meaningful(it.translation) }
        if (fetched != null) return fetched

        return previousLines
            .asSequence()
            .filter { normalizeText(it.text) == normalizedText }
            .minByOrNull { kotlin.math.abs(it.begin - startTimeMs) }
            ?.let { meaningful(it.translation) }
    }

    private fun normalizeText(text: String?): String = text.orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()
}
