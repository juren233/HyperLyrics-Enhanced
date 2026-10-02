/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.PorterDuff
import android.graphics.Shader
import android.text.TextPaint
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.WordModel
import kotlin.math.abs
import kotlin.math.max

internal class TextDrawer {
    private var bgColors = intArrayOf(Color.GRAY)
    private var hlColors = intArrayOf(Color.WHITE)

    var cjkLiftFactor = DEFAULT_CJK_LIFT_FACTOR
    var cjkWaveFactor = DEFAULT_CJK_WAVE_FACTOR
    var latinLiftFactor = DEFAULT_LATIN_LIFT_FACTOR
    var latinWaveFactor = DEFAULT_LATIN_WAVE_FACTOR

    var typefaceSelector: ((Char) -> Typeface)? = null

    val isRainbowBg get() = bgColors.size > 1
    val isRainbowHl get() = hlColors.size > 1

    private val fontMetrics = Paint.FontMetrics()
    private var baselineOffset = 0f

    private var cachedRainbowShader: LinearGradient? = null
    private var cachedAlphaMaskShader: LinearGradient? = null
    private var lastTotalWidth = -1f
    private var lastHighlightWidth = -1f
    private var lastColorsHash = 0

    fun setColors(background: IntArray, highlight: IntArray) {
        if (background.isNotEmpty()) bgColors = background
        if (highlight.isNotEmpty()) hlColors = highlight
    }

    fun updateMetrics(paint: TextPaint) {
        paint.getFontMetrics(fontMetrics)
        baselineOffset = -(fontMetrics.descent + fontMetrics.ascent) / 2f
    }

    fun clearShaderCache() {
        cachedRainbowShader = null
        cachedAlphaMaskShader = null
        lastTotalWidth = -1f
    }

    fun draw(
        canvas: Canvas,
        model: LyricModel,
        viewWidth: Int,
        viewHeight: Int,
        scrollX: Float,
        isOverflow: Boolean,
        highlightWidth: Float,
        useGradient: Boolean,
        scrollOnly: Boolean,
        charMotionEnabled: Boolean,
        centerIfPossible: Boolean,
        alignRight: Boolean,
        bgPaint: TextPaint,
        hlPaint: TextPaint,
        normPaint: TextPaint,
        seamPlan: SeamStripPlan? = null,
        textOrigin: Float? = null,
    ) {
        val y = (viewHeight / 2f) + baselineOffset
        canvas.withSave {
            val xOffset = textOrigin ?: when {
                isOverflow -> scrollX
                alignRight -> viewWidth - model.width
                centerIfPossible -> (viewWidth - model.width) / 2f
                model.isAlignedRight -> viewWidth - model.width
                else -> 0f
            }
            translate(xOffset, 0f)

            if (scrollOnly) {
                forEachSeamRun(canvas, model, seamPlan, xOffset) { runText, x, fade ->
                    canvas.withSeamFade(fade, normPaint) {
                        val selector = typefaceSelector
                        if (selector != null) {
                            MixedTypefaceText.drawText(canvas, runText, x, y, normPaint, selector)
                        } else {
                            canvas.drawText(runText, x, y, normPaint)
                        }
                    }
                }
                return@withSave
            }

            if (isRainbowBg) {
                bgPaint.shader = getOrCreateRainbowShader(model.width, bgColors)
            } else {
                bgPaint.shader = null
            }

            if (charMotionEnabled) {
                val bgClipStart = if (useGradient) 0f else highlightWidth
                drawAnimatedUnits(
                    canvas,
                    model,
                    highlightWidth,
                    bgClipStart,
                    Float.MAX_VALUE,
                    viewHeight,
                    y,
                    bgPaint,
                    seamPlan,
                    xOffset
                )
            } else if (!useGradient) {
                forEachSeamRun(canvas, model, seamPlan, xOffset) { runText, x, fade ->
                    canvas.withSeamFade(fade, bgPaint) {
                        canvas.withSave {
                            canvas.clipRect(highlightWidth, 0f, Float.MAX_VALUE, viewHeight.toFloat())
                            val selector = typefaceSelector
                            if (selector != null) {
                                MixedTypefaceText.drawText(canvas, runText, x, y, bgPaint, selector)
                            } else {
                                canvas.drawText(runText, x, y, bgPaint)
                            }
                        }
                    }
                }
            } else {
                forEachSeamRun(canvas, model, seamPlan, xOffset) { runText, x, fade ->
                    canvas.withSeamFade(fade, bgPaint) {
                        val selector = typefaceSelector
                        if (selector != null) {
                            MixedTypefaceText.drawText(canvas, runText, x, y, bgPaint, selector)
                        } else {
                            canvas.drawText(runText, x, y, bgPaint)
                        }
                    }
                }
            }

            if (highlightWidth > 0f) {
                //val atEnd = highlightWidth >= model.width
                val atEnd = false
                if (useGradient && !atEnd) {
                    val baseShader = if (isRainbowHl) {
                        getOrCreateRainbowShader(model.width, hlColors)
                    } else {
                        LinearGradient(
                            0f, 0f, model.width, 0f,
                            hlPaint.color, hlPaint.color,
                            Shader.TileMode.CLAMP
                        )
                    }
                    val maskShader = getOrCreateAlphaMaskShader(model.width, highlightWidth)
                    hlPaint.shader = ComposeShader(baseShader, maskShader, PorterDuff.Mode.DST_IN)
                } else {
                    if (isRainbowHl) {
                        hlPaint.shader = getOrCreateRainbowShader(model.width, hlColors)
                    } else {
                        hlPaint.shader = null
                    }
                }
                if (charMotionEnabled) {
                    // 逐字单元自带按 clipStart/clipEnd 的自然条带坐标裁剪，并按
                    // 所属段带整体平移（渐变 mask 的软边坐标系保持自然）。
                    drawAnimatedUnits(
                        canvas,
                        model,
                        highlightWidth,
                        0f,
                        highlightWidth,
                        viewHeight,
                        y,
                        hlPaint,
                        seamPlan,
                        xOffset
                    )
                } else {
                    forEachSeamRun(canvas, model, seamPlan, xOffset) { runText, x, fade ->
                        canvas.withSeamFade(fade, hlPaint) {
                            canvas.withSave {
                                canvas.clipRect(0f, 0f, highlightWidth, viewHeight.toFloat())
                                val selector = typefaceSelector
                                if (selector != null) {
                                    MixedTypefaceText.drawText(canvas, runText, x, y, hlPaint, selector)
                                } else {
                                    canvas.drawText(runText, x, y, hlPaint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 接缝分段遍历（第 6 轮拍板）：每段带在自身平移量内以自然条带坐标回调
     * （shader 与裁剪坐标系恒为自然）；跨缝单元单独回调并携带渐隐参数
     * （[SeamStripPlan.SeamFade]，多数侧裁剪＋按遮盖深度渐隐）。无方案时
     * 整行一段。
     */
    private inline fun forEachSeamRun(
        canvas: Canvas,
        model: LyricModel,
        plan: SeamStripPlan?,
        stripOrigin: Float,
        block: (runText: String, x: Float, fade: SeamStripPlan.SeamFade?) -> Unit
    ) {
        val text = if (model.isPlainText) model.text else model.wordText
        if (text.isEmpty()) return
        if (plan == null) {
            block(text, 0f, null)
            return
        }
        for (band in plan.bands) {
            if (band.charEnd <= band.charStart) continue
            canvas.withTranslation(x = band.delta) {
                val st = plan.straddler
                if (st != null && st.unit.charStart >= band.charStart && st.unit.charEnd <= band.charEnd) {
                    val fade = plan.fadeAt(stripOrigin, band.delta)
                    if (st.unit.charStart > band.charStart) {
                        block(text.substring(band.charStart, st.unit.charStart), band.startAdvance, null)
                    }
                    block(text.substring(st.unit.charStart, st.unit.charEnd), st.unit.start, fade)
                    if (st.unit.charEnd < band.charEnd) {
                        block(text.substring(st.unit.charEnd, band.charEnd), st.unit.end, null)
                    }
                } else {
                    block(text.substring(band.charStart, band.charEnd), band.startAdvance, null)
                }
            }
        }
    }

    private fun drawAnimatedUnits(
        canvas: Canvas,
        model: LyricModel,
        highlightWidth: Float,
        clipStart: Float,
        clipEnd: Float,
        viewHeight: Int,
        baselineY: Float,
        paint: TextPaint,
        seamPlan: SeamStripPlan?,
        stripOrigin: Float
    ) {
        model.words.forEach { word ->
            val motionSpec = word.motionSpec()
            if (!motionSpec.animateByChar) {
                drawAnimatedWordUnit(
                    canvas = canvas,
                    word = word,
                    highlightWidth = highlightWidth,
                    clipStart = clipStart,
                    clipEnd = clipEnd,
                    viewHeight = viewHeight,
                    baselineY = baselineY,
                    paint = paint,
                    motionSpec = motionSpec,
                    seamPlan = seamPlan,
                    stripOrigin = stripOrigin
                )
                return@forEach
            }

            for (i in word.chars.indices) {
                val charStart = word.charStartPositions[i]
                val charEnd = word.charEndPositions[i]
                val xShift = seamPlan?.shiftAt(charStart) ?: 0f
                val fade = seamPlan?.straddleFadeFor(charStart, charEnd, stripOrigin, xShift)
                drawAnimatedTextUnit(
                    canvas = canvas,
                    text = word.text,
                    start = i,
                    end = i + 1,
                    drawX = charStart,
                    unitStart = charStart,
                    unitEnd = charEnd,
                    highlightWidth = highlightWidth,
                    clipStart = clipStart,
                    clipEnd = clipEnd,
                    viewHeight = viewHeight,
                    baselineY = baselineY,
                    paint = paint,
                    motionSpec = motionSpec,
                    xShift = xShift,
                    fade = fade
                )
            }
        }
    }

    /**
     * 整词动画单元（拉丁词）：词内至多一个跨缝单元（单字符＋尾随标点），
     * 按段带与渐隐边界拆笔——各笔在自己的自然 x 与所属段带平移上，
     * 跨缝字符以多数侧裁剪＋渐隐绘制；提升量仍按整词跨度计算，词内动态
     * 连续不跳变。
     */
    private fun drawAnimatedWordUnit(
        canvas: Canvas,
        word: WordModel,
        highlightWidth: Float,
        clipStart: Float,
        clipEnd: Float,
        viewHeight: Int,
        baselineY: Float,
        paint: TextPaint,
        motionSpec: MotionSpec,
        seamPlan: SeamStripPlan?,
        stripOrigin: Float
    ) {
        fun drawRun(start: Int, end: Int, shift: Float, fading: Boolean) {
            if (end <= start) return
            drawAnimatedTextUnit(
                canvas = canvas,
                text = word.text,
                start = start,
                end = end,
                drawX = word.charStartPositions[start],
                unitStart = word.startPosition,
                unitEnd = word.endPosition,
                highlightWidth = highlightWidth,
                clipStart = clipStart,
                clipEnd = clipEnd,
                viewHeight = viewHeight,
                baselineY = baselineY,
                paint = paint,
                motionSpec = motionSpec,
                xShift = shift,
                fade = if (fading) seamPlan?.fadeAt(stripOrigin, shift) else null,
            )
        }
        if (seamPlan == null) {
            drawRun(0, word.text.length, 0f, false)
        } else {
            // timing 组可能跨越词界与段带。静止和滚动均按实际位移/渐隐拆笔，
            // 各笔抬升仍共享整组进度，不能在产生 straddler 后退回整组位移。
            seamPlan.forEachWordRun(word.charStartPositions, word.charEndPositions) { start, end, shift, fading ->
                drawRun(start, end, shift, fading)
            }
        }
    }

    private fun drawAnimatedTextUnit(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        drawX: Float,
        unitStart: Float,
        unitEnd: Float,
        highlightWidth: Float,
        clipStart: Float,
        clipEnd: Float,
        viewHeight: Int,
        baselineY: Float,
        paint: TextPaint,
        motionSpec: MotionSpec,
        xShift: Float = 0f,
        fade: SeamStripPlan.SeamFade? = null
    ) {
        if (unitEnd <= clipStart || unitStart >= clipEnd) return

        val visibleLeft = unitStart.coerceAtLeast(clipStart)
        val visibleRight = unitEnd.coerceAtMost(clipEnd)
        val liftY = computeUnitLift(highlightWidth, unitStart, unitEnd, paint.textSize, motionSpec)

        canvas.withSave {
            translate(xShift, 0f)
            canvas.withSeamFade(fade, paint) {
                clipRect(visibleLeft, 0f, visibleRight, viewHeight.toFloat())
                val selector = typefaceSelector
                if (selector != null) {
                    MixedTypefaceText.drawText(
                        canvas,
                        text.substring(start, end),
                        drawX,
                        baselineY + liftY,
                        paint,
                        selector
                    )
                } else {
                    drawText(text, start, end, drawX, baselineY + liftY, paint)
                }
            }
        }
    }

    private fun computeUnitLift(
        highlightWidth: Float,
        unitStart: Float,
        unitEnd: Float,
        textSize: Float,
        motionSpec: MotionSpec
    ): Float {
        val maxOffset = textSize * motionSpec.liftFactor
        val unitCenter = (unitStart + unitEnd) / 2f
        val waveLength = textSize * motionSpec.waveFactor
        val phase = ((highlightWidth - unitCenter) / waveLength).coerceIn(0f, 1f)
        return maxOffset * (1f - easeOutQuint(phase))
    }

    private fun WordModel.motionSpec(): MotionSpec {
        return if (text.any { it.isCjk() }) {
            MotionSpec(animateByChar = true, liftFactor = cjkLiftFactor, waveFactor = cjkWaveFactor)
        } else {
            MotionSpec(
                animateByChar = false,
                liftFactor = latinLiftFactor,
                waveFactor = latinWaveFactor
            )
        }
    }

    private fun Char.isCjk(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    private fun easeOutQuint(value: Float): Float {
        val inverse = 1f - value
        return 1f - inverse * inverse * inverse * inverse * inverse
    }

    private data class MotionSpec(
        val animateByChar: Boolean,
        val liftFactor: Float,
        val waveFactor: Float
    )

    private fun getOrCreateRainbowShader(totalWidth: Float, colors: IntArray): Shader {
        val colorsHash = colors.contentHashCode()
        if (cachedRainbowShader == null || lastTotalWidth != totalWidth || lastColorsHash != colorsHash) {
            cachedRainbowShader = LinearGradient(
                0f, 0f, totalWidth, 0f,
                colors, null, Shader.TileMode.CLAMP
            )
            lastTotalWidth = totalWidth
            lastColorsHash = colorsHash
        }
        return cachedRainbowShader!!
    }

    private fun getOrCreateAlphaMaskShader(totalWidth: Float, highlightWidth: Float): Shader {
        val edgePosition = max(highlightWidth / totalWidth, 0.9f)
        if (cachedAlphaMaskShader == null || abs(lastHighlightWidth - highlightWidth) > 0.1f) {
            cachedAlphaMaskShader = LinearGradient(
                0f, 0f, highlightWidth, 0f,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, edgePosition, 1f),
                Shader.TileMode.CLAMP
            )
            lastHighlightWidth = highlightWidth
        }
        return cachedAlphaMaskShader!!
    }

    private companion object {
        const val DEFAULT_CJK_LIFT_FACTOR = 0.055f
        const val DEFAULT_CJK_WAVE_FACTOR = 2.8f
        const val DEFAULT_LATIN_LIFT_FACTOR = 0.065f
        const val DEFAULT_LATIN_WAVE_FACTOR = 3.6f
    }
}
