package com.juren233.hyperlyricsenhanced.common.lyric

import android.graphics.Paint
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * 歌词行分割工具
 * 将 RichLyricLine 按像素宽度分割为左右两部分，保留词级 timing
 */
object RichLyricLineSplitter {

    data class SplitLineResult(
        val left: IRichLyricLine,
        val right: IRichLyricLine
    )

    /**
     * 将 RichLyricLine 按像素宽度分割为左右两部分
     *
     * @param line 原始歌词行
     * @param paint 用于测量文本宽度的 Paint
     * @param maxWidthPx 左侧最大像素宽度
     * @return 分割后的左右 RichLyricLine
     */
    fun split(
        line: IRichLyricLine,
        paint: Paint,
        maxWidthPx: Float,
        textSizeRatio: Float = 0.7f,
        centerLyric: Boolean = false,
        measureWidth: ((Paint, String) -> Float)? = null,
    ): SplitLineResult {
        val text = line.text ?: return SplitLineResult(line as? RichLyricLine ?: RichLyricLine(), RichLyricLine())

        val totalWidth = measuredWidth(paint, text, measureWidth)
        if (totalWidth <= maxWidthPx) {
            // 文本未超出，全部放左侧，右侧为空
            val richLine = line as? RichLyricLine ?: RichLyricLine(
                begin = line.begin, end = line.end, duration = line.duration,
                text = line.text, words = line.words,
                secondary = line.secondary, secondaryWords = line.secondaryWords,
                translation = line.translation, translationWords = line.translationWords,
                roma = line.roma
            )
            return SplitLineResult(richLine, RichLyricLine(
                begin = line.end, end = line.end, duration = 0,
                text = "", words = emptyList()
            ))
        }

        // 计算分割索引
        val splitIndex = computeSplitIndex(text, paint, maxWidthPx, measureWidth)
        if (splitIndex <= 0) {
            return SplitLineResult(line, RichLyricLine())
        }
        if (splitIndex >= text.length) {
            return SplitLineResult(line, RichLyricLine(
                begin = line.end, end = line.end, duration = 0,
                text = "", words = emptyList()
            ))
        }

        // 分割主文本 words
        val (leftWords, rightWords) = splitWordsAtCharIndex(line.words, splitIndex)
        val lineEnd = effectiveLineEnd(line)
        val splitTime = resolveSplitTime(line, splitIndex, text.length, leftWords, rightWords)

        // 第二行按完整语义单元分割（英文按词，其他文本按字素）。
        // 翻译 timing 故意不沿用原整行 words：左/右半行必须和主句
        // 共用 splitTime，由 LyricLineAssembler 在各自时间窗内重建进度。
        val secondaryPaint = Paint(paint).apply { textSize = paint.textSize * textSizeRatio }
        val translationSplitIndex = computeTranslationSplitIndex(line, secondaryPaint, maxWidthPx, centerLyric, measureWidth)
        val transText = line.translation
        val leftTransText: String?
        val rightTransText: String?

        if (translationSplitIndex != null && !transText.isNullOrEmpty()) {
            leftTransText = transText.substring(0, translationSplitIndex).takeIf { it.isNotEmpty() }
            rightTransText = transText.substring(translationSplitIndex).takeIf { it.isNotEmpty() }
        } else if (!transText.isNullOrEmpty()) {
            leftTransText = transText
            rightTransText = null
        } else {
            leftTransText = null
            rightTransText = null
        }

        // 分割 secondary（按像素宽度独立计算）
        val secondarySplitIndex = computeSecondarySplitIndex(line, secondaryPaint, maxWidthPx, centerLyric, measureWidth)
        val secText = line.secondary
        val leftSecWords: List<LyricWord>?
        val rightSecWords: List<LyricWord>?
        val leftSecText: String?
        val rightSecText: String?

        if (secondarySplitIndex != null && !secText.isNullOrEmpty()) {
            if (!line.secondaryWords.isNullOrEmpty()) {
                val (lsw, rsw) = splitWordsAtCharIndex(line.secondaryWords, secondarySplitIndex)
                leftSecWords = lsw
                rightSecWords = rsw
                leftSecText = lsw.joinToString("") { it.text.orEmpty() }.takeIf { it.isNotEmpty() }
                rightSecText = rsw.joinToString("") { it.text.orEmpty() }.takeIf { it.isNotEmpty() }
            } else {
                leftSecWords = null
                rightSecWords = null
                leftSecText = secText.substring(0, secondarySplitIndex.coerceAtMost(secText.length)).takeIf { it.isNotEmpty() }
                rightSecText = secText.substring(secondarySplitIndex.coerceAtMost(secText.length)).takeIf { it.isNotEmpty() }
            }
        } else if (!secText.isNullOrEmpty()) {
            leftSecWords = line.secondaryWords
            rightSecWords = null
            leftSecText = secText
            rightSecText = null
        } else {
            leftSecWords = null
            rightSecWords = null
            leftSecText = null
            rightSecText = null
        }

        val romaSplitIndex = computeSupplementSplitIndex(
            text = line.roma,
            paint = secondaryPaint,
            maxWidthPx = maxWidthPx,
            centerLyric = centerLyric,
            measureWidth = measureWidth,
        )
        val leftRoma = line.roma?.let { roma ->
            if (romaSplitIndex == null) roma else roma.substring(0, romaSplitIndex)
        }?.takeIf { it.isNotEmpty() }
        val rightRoma = line.roma?.let { roma ->
            romaSplitIndex?.let(roma::substring)
        }?.takeIf { it.isNotEmpty() }

        val leftLine = RichLyricLine(
            begin = line.begin,
            end = splitTime,
            duration = (splitTime - line.begin).coerceAtLeast(0L),
            isAlignedRight = false,
            metadata = line.metadata,
            text = text.substring(0, splitIndex),
            words = leftWords,
            secondary = leftSecText,
            secondaryWords = leftSecWords,
            translation = leftTransText,
            translationWords = null,
            roma = leftRoma
        )

        val rightLine = RichLyricLine(
            begin = splitTime,
            end = lineEnd,
            duration = (lineEnd - splitTime).coerceAtLeast(0L),
            isAlignedRight = false,
            metadata = line.metadata,
            text = text.substring(splitIndex),
            words = rightWords,
            secondary = rightSecText,
            secondaryWords = rightSecWords,
            translation = rightTransText,
            translationWords = null,
            roma = rightRoma
        )

        return SplitLineResult(leftLine, rightLine)
    }

    /**
     * 按字符索引分割 words 列表，跨界 word 按字符比例插值 timing
     */
    private fun splitWordsAtCharIndex(
        words: List<LyricWord>?,
        charIndex: Int
    ): Pair<List<LyricWord>, List<LyricWord>> {
        if (words.isNullOrEmpty()) return Pair(emptyList(), emptyList())

        val leftWords = mutableListOf<LyricWord>()
        val rightWords = mutableListOf<LyricWord>()
        var charPos = 0

        for (word in words) {
            val wordText = word.text.orEmpty()
            val wordEnd = charPos + wordText.length

            when {
                wordEnd <= charIndex -> leftWords.add(word)
                charPos >= charIndex -> rightWords.add(word)
                else -> {
                    // 跨界 word：按字符比例插值 timing
                    val leftLen = charIndex - charPos
                    val rightLen = wordText.length - leftLen
                    val duration = word.end - word.begin
                    val splitMs = word.begin + (duration * leftLen) / wordText.length

                    if (leftLen > 0) {
                        leftWords.add(LyricWord(
                            begin = word.begin, end = splitMs, duration = splitMs - word.begin,
                            text = wordText.substring(0, leftLen), metadata = word.metadata
                        ))
                    }
                    if (rightLen > 0) {
                        rightWords.add(LyricWord(
                            begin = splitMs, end = word.end, duration = word.end - splitMs,
                            text = wordText.substring(leftLen), metadata = word.metadata
                        ))
                    }
                }
            }
            charPos = wordEnd
        }
        return Pair(leftWords, rightWords)
    }

    /**
     * 计算分割索引：breakText + 词边界调整
     */
    private fun computeSplitIndex(
        text: String,
        paint: Paint,
        maxWidthPx: Float,
        measureWidth: ((Paint, String) -> Float)?,
    ): Int {
        var splitIndex = fittingPrefixIndex(text, paint, maxWidthPx, measureWidth)

        if (splitIndex < text.length && measuredWidth(paint, text.substring(0, splitIndex), measureWidth) < maxWidthPx) {
            if (measuredWidth(paint, text.substring(0, splitIndex + 1), measureWidth) <= maxWidthPx) {
                splitIndex++
            }
        }

        splitIndex = previousCharacterBoundary(text, splitIndex.coerceIn(0, text.length))
        return adjustForWordBoundary(text, splitIndex, maxWidthPx, paint, measureWidth)
    }

    /**
     * 词边界调整：英文以空格为界，分割点不得落进词簇内部。
     * 词簇 = 连续的英文字母数字（撇号/连字符是词内连接符，如 I'm、
     * well-known），加上紧贴词尾、中间无空格的标点（逗号等随左侧单词走，
     * 不能单独出现在右段开头）。中文等非拉丁文本不受影响，仍在任意
     * 字素边界分割。
     */
    private fun adjustForWordBoundary(
        text: String,
        originalIndex: Int,
        maxLimitPx: Float,
        paint: Paint,
        measureWidth: ((Paint, String) -> Float)?,
    ): Int {
        if (originalIndex <= 0 || originalIndex >= text.length) {
            return originalIndex.coerceIn(0, text.length)
        }
        val bounds = nearestSafeSplitIndexes(text, originalIndex) ?: return originalIndex
        val (backSplit, forwardSplit) = bounds

        val forwardPx = measuredWidth(paint, text.substring(0, forwardSplit), measureWidth)
        if (forwardPx > maxLimitPx) return backSplit

        val forwardDiff = abs(forwardSplit - (text.length - forwardSplit))
        val backDiff = abs(backSplit - (text.length - backSplit))

        return if (backDiff < forwardDiff) backSplit else forwardSplit
    }

    /**
     * 候选分割点落在词簇内部时，给出最近的两个安全边界（词簇起点与终点）；
     * 候选本身已是安全边界（空格/字素交界）时返回 null 表示无需调整。
     */
    internal fun nearestSafeSplitIndexes(text: String, index: Int): Pair<Int, Int>? {
        if (index <= 0 || index >= text.length) return null
        if (!isInsideWordCluster(text, index)) return null

        var backSplit = index
        while (backSplit > 0 && isInsideWordCluster(text, backSplit)) backSplit--

        var forwardSplit = index
        while (forwardSplit < text.length && isInsideWordCluster(text, forwardSplit)) forwardSplit++

        return backSplit to forwardSplit
    }

    private fun isInsideWordCluster(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return false
        val before = text[index - 1]
        val after = text[index]
        return when {
            isWordClusterChar(before) -> isWordClusterChar(after) || isAttachedPunctuation(after)
            isAttachedPunctuation(before) -> isAttachedPunctuation(after)
            else -> false
        }
    }

    private fun isWordClusterChar(c: Char): Boolean =
        (c.code < 128 && c.isLetterOrDigit()) || isIntraWordConnector(c)

    /** 词内连接符：撇号（I'm）与连字符（well-known）两侧的字母同属一个词簇。 */
    private fun isIntraWordConnector(c: Char): Boolean = c == '\'' || c == '’' || c == '-'

    private fun isAttachedPunctuation(c: Char): Boolean =
        (c.code < 128 && c in ",.!?;:") || c == '…'

    /**
     * 计算 translation 的分割索引（按像素宽度，翻译字号更小所以独立计算）
     */
    private fun computeTranslationSplitIndex(
        line: IRichLyricLine,
        paint: Paint,
        maxWidthPx: Float,
        centerLyric: Boolean,
        measureWidth: ((Paint, String) -> Float)?,
    ): Int? {
        return computeSupplementSplitIndex(line.translation, paint, maxWidthPx, centerLyric, measureWidth)
    }

    private fun computeSecondarySplitIndex(
        line: IRichLyricLine,
        paint: Paint,
        maxWidthPx: Float,
        centerLyric: Boolean,
        measureWidth: ((Paint, String) -> Float)?,
    ): Int? {
        return computeSupplementSplitIndex(line.secondary, paint, maxWidthPx, centerLyric, measureWidth)
    }

    private fun computeSupplementSplitIndex(
        text: String?,
        paint: Paint,
        maxWidthPx: Float,
        centerLyric: Boolean,
        measureWidth: ((Paint, String) -> Float)?,
    ): Int? {
        if (text.isNullOrEmpty()) return null
        val totalWidth = measuredWidth(paint, text, measureWidth)
        val splitLimit = if (centerLyric) (totalWidth / 2f).coerceAtMost(maxWidthPx) else maxWidthPx
        if (totalWidth <= splitLimit) return null
        val measured = fittingPrefixIndex(text, paint, splitLimit, measureWidth)
        return SecondaryLyricTextUnits.adjustSplitIndex(text, measured) { candidate ->
            measuredWidth(paint, text.substring(0, candidate), measureWidth) <= splitLimit
        }.takeIf { it in 1 until text.length }
    }

    private fun measuredWidth(
        paint: Paint,
        text: String,
        measureWidth: ((Paint, String) -> Float)?,
    ): Float = measureWidth?.invoke(paint, text) ?: paint.measureText(text)

    private fun fittingPrefixIndex(
        text: String,
        paint: Paint,
        maxWidthPx: Float,
        measureWidth: ((Paint, String) -> Float)?,
    ): Int {
        if (measureWidth == null) return paint.breakText(text, true, maxWidthPx, null)
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (measuredWidth(paint, text.substring(0, middle), measureWidth) <= maxWidthPx) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return low
    }

    private fun previousCharacterBoundary(text: String, index: Int): Int {
        if (index <= 0 || index >= text.length) return index.coerceIn(0, text.length)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        return if (iterator.isBoundary(index)) index else iterator.preceding(index).coerceAtLeast(0)
    }

    internal fun resolveSplitTime(
        line: IRichLyricLine,
        splitIndex: Int,
        textLength: Int,
        leftWords: List<LyricWord>,
        rightWords: List<LyricWord>,
    ): Long {
        val lineEnd = effectiveLineEnd(line)
        val timedBoundary = rightWords.firstOrNull()?.begin
            ?: leftWords.lastOrNull()?.end
        if (timedBoundary != null && timedBoundary in line.begin..lineEnd) {
            return timedBoundary
        }
        if (lineEnd <= line.begin || textLength <= 0) return line.begin
        return line.begin + (lineEnd - line.begin) * splitIndex / textLength
    }

    private fun effectiveLineEnd(line: IRichLyricLine): Long = when {
        line.end > line.begin -> line.end
        line.duration > 0L -> line.begin + line.duration
        else -> line.begin
    }
}
