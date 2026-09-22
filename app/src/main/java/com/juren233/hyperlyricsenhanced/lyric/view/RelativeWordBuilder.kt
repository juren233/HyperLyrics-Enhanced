/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import com.juren233.hyperlyricsenhanced.common.lyric.SecondaryLyricTextUnits
import com.juren233.hyperlyricsenhanced.lyric.model.LyricMetadata
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.ILyricTiming

internal class RelativeWordBuilder {
    fun build(
        timing: ILyricTiming,
        text: String?,
        words: List<LyricWord>?
    ): List<LyricWord>? {
        if (words.isNullOrEmpty() && !text.isNullOrBlank()
            && timing.begin < timing.end && timing.begin >= 0
        ) {
            return listOf(
                LyricWord(
                    text = text,
                    begin = timing.begin,
                    end = timing.end,
                    duration = timing.duration
                )
            )
        }
        return words
    }

    /**
     * 在原时间窗口内为第二行构造可安全分割的语义单元。
     *
     * 英文/拉丁文本以完整单词为单元，中文、日文等其他文本以
     * Unicode 字素为单元。全岛歌词依赖这些边界在摄像头两侧插入中孔；
     * 分离歌词则用左/右半行各自的 timing 生成进度，保证先左后右。
     */
    fun buildByTextUnit(
        timing: ILyricTiming,
        text: String?,
        words: List<LyricWord>?
    ): List<LyricWord>? {
        if (!words.isNullOrEmpty()) {
            return words.flatMap { word ->
                splitTimedText(
                    text = word.text.orEmpty(),
                    begin = word.begin,
                    end = effectiveEnd(word.begin, word.end, word.duration),
                    metadata = word.metadata,
                )
            }
        }
        if (text.isNullOrEmpty()) return words
        return splitTimedText(
            text = text,
            begin = timing.begin,
            end = effectiveEnd(timing.begin, timing.end, timing.duration),
            metadata = null,
        )
    }

    private fun splitTimedText(
        text: String,
        begin: Long,
        end: Long,
        metadata: LyricMetadata?,
    ): List<LyricWord> {
        if (text.isEmpty()) return emptyList()
        val ranges = SecondaryLyricTextUnits.ranges(text)
        if (ranges.isEmpty() || end <= begin) {
            return listOf(
                LyricWord(
                    text = text,
                    begin = begin,
                    end = end,
                    duration = (end - begin).coerceAtLeast(0L),
                    metadata = metadata,
                )
            )
        }
        val totalDuration = end - begin
        return ranges.mapIndexed { index, range ->
            val charBegin = begin + totalDuration * index / ranges.size
            val charEnd = begin + totalDuration * (index + 1) / ranges.size
            LyricWord(
                text = text.substring(range.first, range.last + 1),
                begin = charBegin,
                end = charEnd,
                duration = charEnd - charBegin,
                metadata = metadata,
            )
        }
    }

    private fun effectiveEnd(begin: Long, end: Long, duration: Long): Long = when {
        end > begin -> end
        duration > 0L -> begin + duration
        else -> end
    }
}
