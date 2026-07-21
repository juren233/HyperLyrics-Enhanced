package com.lidesheng.hyperlyric.common.lyric

import android.graphics.Paint
import com.lidesheng.hyperlyric.lyric.model.LyricWord
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine
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
    fun split(line: IRichLyricLine, paint: Paint, maxWidthPx: Float, textSizeRatio: Float = 0.7f, centerLyric: Boolean = false): SplitLineResult {
        val text = line.text ?: return SplitLineResult(line as? RichLyricLine ?: RichLyricLine(), RichLyricLine())

        val totalWidth = paint.measureText(text)
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
        val splitIndex = computeSplitIndex(text, paint, maxWidthPx)
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

        // 分割 translation（用独立 Paint 测量，翻译字号更小）
        val secondaryPaint = Paint(paint).apply { textSize = paint.textSize * textSizeRatio }
        val translationSplitIndex = computeTranslationSplitIndex(line, secondaryPaint, maxWidthPx, centerLyric)
        val transText = line.translation
        val leftTransText: String?
        val rightTransText: String?
        val leftTransWords: List<LyricWord>?
        val rightTransWords: List<LyricWord>?

        if (translationSplitIndex != null && !transText.isNullOrEmpty()) {
            if (!line.translationWords.isNullOrEmpty()) {
                // 有词级 timing：分割 words
                val (ltw, rtw) = splitWordsAtCharIndex(line.translationWords, translationSplitIndex)
                leftTransWords = ltw
                rightTransWords = rtw
                leftTransText = ltw.joinToString("") { it.text.orEmpty() }.takeIf { it.isNotEmpty() }
                rightTransText = rtw.joinToString("") { it.text.orEmpty() }.takeIf { it.isNotEmpty() }
            } else {
                // 无词级 timing：直接按比例截取文本
                leftTransWords = null
                rightTransWords = null
                leftTransText = transText.substring(0, translationSplitIndex.coerceAtMost(transText.length)).takeIf { it.isNotEmpty() }
                rightTransText = transText.substring(translationSplitIndex.coerceAtMost(transText.length)).takeIf { it.isNotEmpty() }
            }
        } else if (!transText.isNullOrEmpty()) {
            // 翻译未超出，全部给左侧
            leftTransText = transText
            rightTransText = null
            leftTransWords = line.translationWords
            rightTransWords = null
        } else {
            leftTransText = null
            rightTransText = null
            leftTransWords = null
            rightTransWords = null
        }

        // 分割 secondary（按像素宽度独立计算）
        val secondarySplitIndex = computeSecondarySplitIndex(line, secondaryPaint, maxWidthPx, centerLyric)
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

        val leftLine = RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = 0,
            isAlignedRight = false,
            metadata = line.metadata,
            text = text.substring(0, splitIndex),
            words = leftWords,
            secondary = leftSecText,
            secondaryWords = leftSecWords,
            translation = leftTransText,
            translationWords = leftTransWords,
            roma = null
        )

        val rightLine = RichLyricLine(
            begin = rightWords.firstOrNull()?.begin ?: line.begin,
            end = line.end,
            duration = 0,
            isAlignedRight = false,
            metadata = line.metadata,
            text = text.substring(splitIndex),
            words = rightWords,
            secondary = rightSecText,
            secondaryWords = rightSecWords,
            translation = rightTransText,
            translationWords = rightTransWords,
            roma = null
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
    private fun computeSplitIndex(text: String, paint: Paint, maxWidthPx: Float): Int {
        var splitIndex = paint.breakText(text, true, maxWidthPx, null)

        if (splitIndex < text.length && paint.measureText(text, 0, splitIndex) < maxWidthPx) {
            if (paint.measureText(text, 0, splitIndex + 1) <= maxWidthPx) {
                splitIndex++
            }
        }

        splitIndex = splitIndex.coerceIn(0, text.length)
        return adjustForWordBoundary(text, splitIndex, maxWidthPx, paint)
    }

    /**
     * 词边界调整：避免在英文单词中间切割
     */
    private fun adjustForWordBoundary(
        text: String, originalIndex: Int, maxLimitPx: Float, paint: Paint
    ): Int {
        if (originalIndex <= 0 || originalIndex >= text.length) {
            return originalIndex.coerceIn(0, text.length)
        }

        val isAsciiAlnum = { c: Char -> c.isLetterOrDigit() && c.code < 128 }
        if (!isAsciiAlnum(text[originalIndex - 1]) || !isAsciiAlnum(text[originalIndex])) {
            return originalIndex
        }

        var backSplit = originalIndex
        while (backSplit > 0 && isAsciiAlnum(text[backSplit - 1])) backSplit--

        var forwardSplit = originalIndex
        while (forwardSplit < text.length && isAsciiAlnum(text[forwardSplit])) forwardSplit++

        val forwardPx = paint.measureText(text, 0, forwardSplit)
        if (forwardPx > maxLimitPx) return backSplit

        val forwardDiff = abs(forwardSplit - (text.length - forwardSplit))
        val backDiff = abs(backSplit - (text.length - backSplit))

        return if (backDiff < forwardDiff) backSplit else forwardSplit
    }

    /**
     * 计算 translation 的分割索引（按像素宽度，翻译字号更小所以独立计算）
     */
    private fun computeTranslationSplitIndex(line: IRichLyricLine, paint: Paint, maxWidthPx: Float, centerLyric: Boolean): Int? {
        val transText = line.translation ?: return null
        if (transText.isEmpty()) return null
        val totalWidth = paint.measureText(transText)
        val splitLimit = if (centerLyric) (totalWidth / 2f).coerceAtMost(maxWidthPx) else maxWidthPx
        if (totalWidth <= splitLimit) return null
        val splitIdx = paint.breakText(transText, true, splitLimit, null).coerceIn(0, transText.length)
        return adjustForWordBoundary(transText, splitIdx, splitLimit, paint)
    }

    private fun computeSecondarySplitIndex(line: IRichLyricLine, paint: Paint, maxWidthPx: Float, centerLyric: Boolean): Int? {
        val secText = line.secondary ?: return null
        if (secText.isEmpty()) return null
        val totalWidth = paint.measureText(secText)
        val splitLimit = if (centerLyric) (totalWidth / 2f).coerceAtMost(maxWidthPx) else maxWidthPx
        if (totalWidth <= splitLimit) return null
        val splitIdx = paint.breakText(secText, true, splitLimit, null).coerceIn(0, secText.length)
        return adjustForWordBoundary(secText, splitIdx, splitLimit, paint)
    }
}
