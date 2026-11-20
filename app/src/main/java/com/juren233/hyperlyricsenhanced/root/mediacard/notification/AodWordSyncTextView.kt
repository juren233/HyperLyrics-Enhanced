/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.text.Layout
import android.text.TextPaint
import android.view.Choreographer
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.view.line.drawInterludeDots
import com.juren233.hyperlyricsenhanced.lyric.view.line.interludeDotsWidth
import com.juren233.hyperlyricsenhanced.lyric.view.line.resolveInterludeDotsFrame
import com.juren233.hyperlyricsenhanced.lyric.view.line.resolveInterludeDotsStartX
import kotlin.math.floor

/**
 * AOD 的逐字扫过效果与前奏/间奏动态圆点，由「动态效果」类的两个开关分别控制（默认关闭）。
 *
 * 仍是 [AppCompatTextView]：测量、自动换行、对齐、字号字体都沿用 TextView 原生行为，因此长句换成多行后
 * 逐字高亮按行依次扫过（已唱行全亮，当前行带柔和渐变边缘从左扫到右，未唱行压暗）。
 * 没有逐字数据或文本对不上时完全走 TextView 原有绘制。前奏/间奏指示行改画与超级岛
 * 共用的三点指示器（帧与绘制函数同源，见 InterludeDotsRenderer.kt）。
 *
 * 播放期间由自己的 [Choreographer] 帧循环逐帧重绘，并按 [KEEP_ALIVE_INTERVAL_MS] 续
 * 绘制唤醒锁，与流光息屏保活是同一种出帧方式（息屏下无人持锁时不合成帧）。
 */
// hook 进程内纯代码创建；继承 AppCompatTextView 与仓库自定义 TextView 惯例一致，
// appcompat 类已随模块包加载进目标进程（ProviderLogo 同样引用）。
internal class AodWordSyncTextView(context: Context) : AppCompatTextView(context) {

    private var syncText = ""
    private var syncWords: List<LyricWord>? = null
    private var interlude: LongRange? = null
    private var playing = false
    private var keepAlive: (() -> Unit)? = null
    private var loopRunning = false
    private var lastKeepAliveMs = 0L

    /** 当前绑定的是前奏/间奏指示行。 */
    val isInterludeIndicator: Boolean get() = interlude != null

    private val dimPaint = TextPaint()
    private val brightPaint = TextPaint()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val frameCallback = Choreographer.FrameCallback {
        loopRunning = false
        if (!shouldRun()) return@FrameCallback
        AodWordProgress.recordLoopFrame()
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeepAliveMs >= KEEP_ALIVE_INTERVAL_MS) {
            lastKeepAliveMs = now
            keepAlive?.invoke()
        }
        invalidate()
        if (AodWordProgress.currentPositionMs() < animationEnd()) startLoop()
    }

    /**
     * 绑定当前行的逐字数据（[row] 仅用于诊断日志）。[words] 为空、或与 [text] 对不上时清除
     * 逐字状态，恢复原生绘制；[interlude] 非空表示本行是前奏/间奏指示行。
     */
    fun bindWordSync(
        row: String,
        text: String,
        words: List<LyricWord>?,
        interlude: LongRange? = null,
        playing: Boolean,
        keepAlive: () -> Unit,
    ) {
        val usable = words?.takeIf { text.isNotEmpty() }?.takeIf {
            (AodWordProgress.progressChars(text, it, 0L) != null).also { matched ->
                if (!matched) AodWordProgress.logMismatch(row, text, it)
            }
        }
        val changed = syncText != text || syncWords !== usable ||
            this.interlude != interlude || this.playing != playing
        syncText = text
        syncWords = usable
        this.interlude = interlude
        this.playing = playing
        this.keepAlive = keepAlive
        if (changed) invalidate()
        if (shouldRun()) startLoop()
    }

    private fun animationEnd(): Long =
        maxOf(syncWords?.lastOrNull()?.end ?: Long.MIN_VALUE, interlude?.last ?: Long.MIN_VALUE)

    private fun shouldRun(): Boolean =
        playing && (syncWords != null || interlude != null) && isAttachedToWindow && isShown

    private fun startLoop() {
        if (loopRunning) return
        loopRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (shouldRun()) startLoop()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        loopRunning = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && shouldRun()) startLoop()
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        val range = interlude
        if (range != null && layout != null && text.toString() == syncText) {
            AodWordProgress.recordDraw()
            canvas.save()
            canvas.translate((compoundPaddingLeft + scrollX).toFloat(), extendedPaddingTop.toFloat())
            drawInterlude(canvas, layout, range)
            canvas.restore()
            return
        }
        val words = syncWords
        val chars = if (words != null && layout != null && text.toString() == syncText) {
            AodWordProgress.progressChars(syncText, words, AodWordProgress.currentPositionMs())
        } else {
            null
        }
        if (chars == null || layout == null) {
            super.onDraw(canvas)
            return
        }
        AodWordProgress.recordDraw()
        canvas.save()
        canvas.translate(
            (compoundPaddingLeft + scrollX).toFloat(),
            extendedPaddingTop.toFloat(),
        )
        drawProgress(canvas, layout, chars)
        canvas.restore()
    }

    private fun drawInterlude(canvas: Canvas, layout: Layout, range: LongRange) {
        val textSize = paint.textSize
        val horizontal = gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val position = AodWordProgress.currentPositionMs().coerceIn(range.first, range.last)
        val frame = resolveInterludeDotsFrame(position, range.first, range.last)
        val startX = resolveInterludeDotsStartX(
            totalWidth = interludeDotsWidth(textSize),
            viewWidth = layout.width.toFloat(),
            isAlignedRight = false,
            centerIfPossible = horizontal == Gravity.CENTER_HORIZONTAL,
            alignRight = horizontal == Gravity.RIGHT || horizontal == Gravity.END,
        )
        dotPaint.color = currentTextColor
        dotPaint.shader = null
        drawInterludeDots(canvas, dotPaint, frame, textSize, startX, layout.height / 2f)
    }

    private fun drawProgress(canvas: Canvas, layout: Layout, chars: Float) {
        val bright = currentTextColor
        dimPaint.set(paint)
        dimPaint.color = withAlpha(bright, DIM_ALPHA_FACTOR)
        brightPaint.set(paint)
        brightPaint.color = bright
        brightPaint.shader = null

        val text = layout.text
        val length = text.length
        val charIndex = floor(chars).toInt().coerceIn(0, length)
        val fraction = chars - charIndex
        val activeLine = if (length == 0) 0 else layout.getLineForOffset(charIndex.coerceAtMost(length - 1))
        val allSung = chars >= length

        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            val left = layout.getLineLeft(line)
            val baseline = layout.getLineBaseline(line).toFloat()
            canvas.drawText(text, start, end, left, baseline, dimPaint)
            when {
                allSung || line < activeLine ->
                    canvas.drawText(text, start, end, left, baseline, brightPaint)
                line == activeLine -> {
                    val x = progressX(layout, line, charIndex, fraction)
                    val edge = paint.textSize * EDGE_WIDTH_FACTOR
                    brightPaint.shader = LinearGradient(
                        x - edge, 0f, x, 0f,
                        bright, bright and 0x00FFFFFF,
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawText(text, start, end, left, baseline, brightPaint)
                    brightPaint.shader = null
                }
            }
        }
    }

    /** 当前行内高亮边缘的横坐标：字符边界之间按小数部分线性插值。 */
    private fun progressX(layout: Layout, line: Int, charIndex: Int, fraction: Float): Float {
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        val x0 = if (charIndex <= lineStart) {
            layout.getLineLeft(line)
        } else {
            layout.getPrimaryHorizontal(charIndex.coerceAtMost(lineEnd))
        }
        val nextIndex = charIndex + 1
        val x1 = if (nextIndex >= lineEnd) {
            layout.getLineRight(line)
        } else {
            layout.getPrimaryHorizontal(nextIndex)
        }
        return (x0 + (x1 - x0) * fraction).coerceIn(layout.getLineLeft(line), layout.getLineRight(line))
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val alpha = ((color ushr 24) * factor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private companion object {
        /** 未唱部分相对已唱部分的亮度。 */
        const val DIM_ALPHA_FACTOR = 0.4f

        /** 渐变边缘宽度，相对字号。 */
        const val EDGE_WIDTH_FACTOR = 0.6f

        /** 小于 DRAW_WAKE_LOCK_TIMEOUT_MS（1s），保证播放期间锁不断档。 */
        const val KEEP_ALIVE_INTERVAL_MS = 500L
    }
}
