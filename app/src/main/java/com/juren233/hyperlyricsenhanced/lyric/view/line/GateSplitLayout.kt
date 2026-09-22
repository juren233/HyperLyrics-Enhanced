/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Paint
import android.graphics.Typeface
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.WordModel

/**
 * 全岛歌词模式（SpaceGate）的“挖孔”布局。
 *
 * 连续文本带在左右视口边界处会被摄像头区域隔断：如果一个字形恰好横跨
 * 边界，就会出现“摄像头两边各半截字”。挖孔在字符/词边界处把条带断开，
 * 让任何字形都不跨边界：前段留在左视口，后段从右视口左缘精确起笔，
 * [holeWidth] 是插进条带里的空隙（对应左视口装不满的剩余空间）。
 *
 * 坐标约定：条带坐标保持未挖孔的 [LyricModel] 布局不变（逐字填色进度、
 * 滚动步进都在该坐标系），挖孔只影响绘制期的 x 平移——条带 x ≥
 * [runAWidth] 的单元整体右移 [holeWidth]。[holedWidth] 是挖孔后的条带
 * 总宽，供跑马灯溢出判定/循环周期使用。
 *
 * 仅处理静止偏移为 0 的场景：溢出行静止（任何对齐偏好下跑马灯起点都在
 * 条带原点）与默认左对齐的静态行。静态居中/靠右的前段不在条带原点，
 * 挖孔无法落位，返回 null 交给摄像头两侧渐变兜底。
 */
internal class GateSplitLayout internal constructor(
    /** 后段起始字符索引（相对 model.text / wordText）。 */
    val splitCharIndex: Int,
    /** 前段条带宽度（后段首字符的未挖孔条带 x）。 */
    val runAWidth: Float,
    /** 后段绘制用的条带 x（= 左视口宽度）。 */
    val runBStripStart: Float,
    /** 挖孔后的条带总宽。 */
    val holedWidth: Float,
) {
    val holeWidth: Float get() = runBStripStart - runAWidth

    /** 条带坐标 [stripUnitStart] 之后的绘制单元应平移的量。 */
    fun shiftFor(stripUnitStart: Float): Float =
        if (holeWidth > 0f && stripUnitStart >= runAWidth - 0.01f) holeWidth else 0f

    companion object {
        private const val WIDTH_FUZZ = 0.5f

        fun compute(
            model: LyricModel,
            paint: Paint,
            typefaceSelector: ((Char) -> Typeface)?,
            leftWidth: Int,
            rightWidth: Int,
            virtualWidth: Int,
            centerIfPossible: Boolean,
            alignRight: Boolean,
        ): GateSplitLayout? {
            if (leftWidth <= 0 || rightWidth <= 0) return null
            // 逐字行的实际宽度以词累进位置为准（与逐字绘制同源）。
            val textWidth = if (model.isPlainText) {
                model.width
            } else {
                model.words.lastOrNull()?.endPosition ?: return null
            }
            if (textWidth <= 0f) return null
            val isOverflow = textWidth > virtualWidth
            if (!isOverflow && (alignRight || centerIfPossible || model.isAlignedRight)) return null

            val splitLimit = leftWidth.toFloat()
            if (textWidth <= splitLimit) return null // 整行装进左视口，无需挖孔

            return if (model.isPlainText) {
                computePlainText(model, paint, typefaceSelector, textWidth, splitLimit)
            } else {
                computeWordSync(model.words, textWidth, splitLimit)
            }
        }

        private fun computePlainText(
            model: LyricModel,
            paint: Paint,
            typefaceSelector: ((Char) -> Typeface)?,
            textWidth: Float,
            splitLimit: Float,
        ): GateSplitLayout? {
            val text = model.text
            val widths = FloatArray(text.length)
            if (typefaceSelector != null) {
                MixedTypefaceText.getTextWidths(paint, text, typefaceSelector, widths)
            } else {
                paint.getTextWidths(text, widths)
            }
            var acc = 0f
            var k = 0
            for (width in widths) {
                if (acc + width > splitLimit + WIDTH_FUZZ) break
                acc += width
                k++
            }
            // 英数词中间不落边界：回退到词首（保证前段不超限）。
            if (k in 1 until text.length &&
                text[k - 1].isGateWordChar() && text[k].isGateWordChar()
            ) {
                while (k > 0 && text[k - 1].isGateWordChar()) k--
            }
            if (k <= 0 || k >= text.length) return null // 边界无效或整行在前段
            var runA = 0f
            for (i in 0 until k) runA += widths[i]
            return build(k, runA.coerceAtMost(splitLimit), textWidth, splitLimit)
        }

        private fun computeWordSync(
            words: List<WordModel>,
            textWidth: Float,
            splitLimit: Float,
        ): GateSplitLayout? {
            var charCount = 0
            var runA = 0f
            var wordCount = 0
            for (word in words) {
                if (word.endPosition > splitLimit + WIDTH_FUZZ) break
                runA = word.endPosition
                charCount += word.text.length
                wordCount++
            }
            // 首词放不下（词组 timing 首单元超过左槽宽）时返回 null 不挖孔：
            // 强行"前段留空、整行右移"会让整行从右槽起笔、条带被抬高一个
            // 左槽宽，滚动行程多出一个孔宽（真机 210052 取证：k=0/hole=159-187
            // 的主行从右侧起笔并持续左滚，用户判定恶性 bug）。此时退回连续
            // 条带从左侧起笔，摄像头处按连续条带裁切。
            if (wordCount <= 0 || wordCount >= words.size) return null // 首词放不下或全装得下
            return build(charCount, runA.coerceAtMost(splitLimit), textWidth, splitLimit)
        }

        private fun build(
            splitCharIndex: Int,
            runAWidth: Float,
            textWidth: Float,
            splitLimit: Float,
        ): GateSplitLayout {
            val widthB = textWidth - runAWidth
            return GateSplitLayout(
                splitCharIndex = splitCharIndex,
                runAWidth = runAWidth,
                runBStripStart = splitLimit,
                holedWidth = splitLimit + widthB,
            )
        }

        private fun Char.isGateWordChar(): Boolean = isLetterOrDigit() && code < 128
    }
}
