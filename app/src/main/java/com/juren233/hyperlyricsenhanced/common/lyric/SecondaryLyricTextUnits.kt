/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.lyric

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * 第二行歌词的最小分割单元：拉丁文本按词，其他文本按 Unicode 字素。
 */
internal object SecondaryLyricTextUnits {
    fun ranges(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val wordIterator = BreakIterator.getWordInstance(Locale.ROOT)
        wordIterator.setText(text)

        val rawUnits = ArrayList<TextUnit>()
        var start = wordIterator.first()
        var end = wordIterator.next()
        while (end != BreakIterator.DONE) {
            val range = start until end
            val segment = text.substring(start, end)
            if (segment.hasLatinWordCore()) {
                rawUnits += TextUnit(range = range, isLatinWord = true)
            } else {
                characterRanges(segment).forEach { characterRange ->
                    rawUnits += TextUnit(
                        range = (start + characterRange.first)..(start + characterRange.last),
                        isLatinWord = false,
                    )
                }
            }
            start = end
            end = wordIterator.next()
        }
        return mergeConnectorJoinedLatinWords(
            attachLatinSeparators(text, mergeJoinedGraphemes(text, rawUnits)),
            text,
        )
    }

    fun adjustSplitIndex(
        text: String,
        originalIndex: Int,
        prefixFits: (Int) -> Boolean,
    ): Int {
        val index = originalIndex.coerceIn(0, text.length)
        if (index <= 0 || index >= text.length) return index

        val units = ranges(text)
        if (units.any { it.last + 1 == index }) return index
        val containing = units.firstOrNull { index > it.first && index <= it.last } ?: return index
        val back = containing.first
        val forward = containing.last + 1
        if (back <= 0 && forward >= text.length) return 0
        if (back <= 0) return forward
        if (forward >= text.length || !prefixFits(forward)) return back

        val forwardDiff = abs(forward - (text.length - forward))
        val backDiff = abs(back - (text.length - back))
        return if (backDiff < forwardDiff) back else forward
    }

    /** 将空格和标点并入相邻的拉丁词，避免它们单独占用滚动节拍。 */
    private fun attachLatinSeparators(text: String, rawUnits: List<TextUnit>): List<IntRange> {
        if (rawUnits.isEmpty()) return emptyList()
        val result = ArrayList<TextUnit>(rawUnits.size)
        var index = 0
        while (index < rawUnits.size) {
            val unit = rawUnits[index]
            if (!unit.isSeparator(text)) {
                result += unit
                index++
                continue
            }

            var separatorEndIndex = index + 1
            while (separatorEndIndex < rawUnits.size && rawUnits[separatorEndIndex].isSeparator(text)) {
                separatorEndIndex++
            }
            val separatorEnd = rawUnits[separatorEndIndex - 1].range.last
            val previous = result.lastOrNull()
            val next = rawUnits.getOrNull(separatorEndIndex)
            when {
                previous?.isLatinWord == true -> {
                    result[result.lastIndex] = previous.copy(
                        range = previous.range.first..separatorEnd,
                    )
                }
                next?.isLatinWord == true -> {
                    result += TextUnit(
                        range = unit.range.first..next.range.last,
                        isLatinWord = true,
                    )
                    separatorEndIndex++
                }
                else -> {
                    for (separatorIndex in index until separatorEndIndex) {
                        result += rawUnits[separatorIndex]
                    }
                }
            }
            index = separatorEndIndex
        }
        return result.map(TextUnit::range)
    }

    /**
     * 撇号/连字符直接相连的拉丁词（I'm、well-known、rock'n'roll）合并为
     * 一个不可分割单元，防止分割点落在连接符两侧。部分 ICU 词迭代会把
     * 连字符连接的词拆成多段，这里兜底重连。
     */
    private fun mergeConnectorJoinedLatinWords(units: List<IntRange>, text: String): List<IntRange> {
        if (units.isEmpty()) return units
        val result = ArrayList<IntRange>(units.size)
        var index = 0
        while (index < units.size) {
            val range = units[index]
            var end = range.last
            var scan = index + 1
            while (scan < units.size &&
                units[scan].first == end + 1 &&
                text[end].isIntraWordConnector() &&
                text[units[scan].first].isAsciiLetterOrDigit()
            ) {
                end = units[scan].last
                scan++
            }
            result += range.first..end
            index = scan
        }
        return result
    }

    private fun Char.isIntraWordConnector(): Boolean = this == '\'' || this == '’' || this == '-'

    private fun Char.isAsciiLetterOrDigit(): Boolean = code < 128 && isLetterOrDigit()

    /**
     * 部分 Android/JVM `BreakIterator` 会把 ZWJ emoji 序列分成多段，
     * 这里重新合并成一个不可切开的字素。
     */
    private fun mergeJoinedGraphemes(text: String, units: List<TextUnit>): List<TextUnit> {
        if (units.size < 2) return units
        val result = ArrayList<TextUnit>(units.size)
        for (unit in units) {
            val previous = result.lastOrNull()
            if (previous != null && !previous.isLatinWord && !unit.isLatinWord &&
                text[previous.range.last] == ZERO_WIDTH_JOINER
            ) {
                result[result.lastIndex] = previous.copy(
                    range = previous.range.first..unit.range.last,
                )
            } else {
                result += unit
            }
        }
        return result
    }

    private fun characterRanges(text: String): List<IntRange> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val ranges = ArrayList<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            ranges += start until end
            start = end
            end = iterator.next()
        }
        return ranges
    }

    private data class TextUnit(
        val range: IntRange,
        val isLatinWord: Boolean,
    ) {
        fun isSeparator(text: String): Boolean {
            val value = text.substring(range.first, range.last + 1)
            var offset = 0
            while (offset < value.length) {
                val codePoint = value.codePointAt(offset)
                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint) &&
                    !codePoint.isPunctuation()
                ) {
                    return false
                }
                offset += Character.charCount(codePoint)
            }
            return value.isNotEmpty()
        }
    }

    private fun String.hasLatinWordCore(): Boolean {
        var offset = 0
        while (offset < length) {
            val codePoint = codePointAt(offset)
            if (Character.isDigit(codePoint) ||
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
            ) {
                return true
            }
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private fun Int.isPunctuation(): Boolean = when (Character.getType(this)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }

    private const val ZERO_WIDTH_JOINER = '\u200D'
}
