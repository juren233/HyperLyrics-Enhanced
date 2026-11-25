/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.annotation.SuppressLint
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
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.view.line.drawInterludeDots
import com.juren233.hyperlyricsenhanced.lyric.view.line.interludeDotsWidth
import com.juren233.hyperlyricsenhanced.lyric.view.line.resolveInterludeDotsFrame
import com.juren233.hyperlyricsenhanced.lyric.view.line.resolveInterludeDotsStartX

/**
 * AOD 的逐字扫过效果与前奏/间奏动态圆点，由「动态效果」类的两个开关分别控制（默认关闭）。
 *
 * 仍是 [TextView]：测量、自动换行、对齐、字号字体都沿用原生行为，因此长句换成多行后
 * 逐字高亮按行依次扫过：唱的时候是一条固定宽度的柔和渐变边缘从左扫到右，停顿、句尾处
 * 柔边在一小段时间内整体往右滑出、补满已唱部分，换行处尾巴随进度继续补亮，不会一帧跳白（见 [AodWordFill]）。
 * 没有逐字数据或文本对不上时完全走 [TextView] 原有绘制。前奏/间奏指示行改画与超级岛
 * 共用的三点指示器（帧与绘制函数同源，见 InterludeDotsRenderer.kt）。
 *
 * 播放期间由自己的 [Choreographer] 帧循环逐帧重绘，并按 [KEEP_ALIVE_INTERVAL_MS] 续
 * 绘制唤醒锁，与流光息屏保活是同一种出帧方式（息屏下无人持锁时不合成帧）。
 */
// AppCompatCustomView 的前提是控件由 AppCompatActivity 在 AppCompat 主题下从 XML 加载、
// 内置控件会被替换为 AppCompat 版本；本类在 SystemUI 进程内以代码创建，宿主没有这套机制，
// 规则不适用。改继承 AppCompatTextView 反而会用模块的资源 ID 去读 SystemUI 主题。
@SuppressLint("AppCompatCustomView")
internal class AodWordSyncTextView(context: Context) : TextView(context) {

    private var syncText = ""
    private var syncRow = ""
    private var syncWords: List<LyricWord>? = null
    /** 这一行预计被换走的时刻，补满要赶在这之前完成。 */
    private var syncLeaveMs: Long? = null
    private var interlude: LongRange? = null
    private var playing = false
    private var keepAlive: (() -> Unit)? = null
    private var loopRunning = false
    private var lastKeepAliveMs = 0L

    /** 当前绑定的是前奏/间奏指示行。 */
    val isInterludeIndicator: Boolean get() = interlude != null

    /** 当前行绑定了可用的逐字数据（逐字扫过生效）。 */
    val hasWordSync: Boolean get() = syncWords != null

    private val dimPaint = TextPaint()
    private val brightPaint = TextPaint()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var fill: AodWordFill? = null
    private var fillText: String? = null
    private var fillWords: List<LyricWord>? = null
    private var fillLayout: Layout? = null
    private var fillTextSize = 0f
    private var fillLeaveMs: Long? = null
    private var charLeft = FloatArray(0)
    private var charRight = FloatArray(0)
    private var profileBuffer = DoubleArray(0)

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
        if (usable != null && usable !== syncWords) AodWordProgress.logBoundWords(row, text, usable)
        syncLeaveMs = if (usable != null) AodWordProgress.displayLeaveMs() else null
        syncRow = row
        syncText = text
        syncWords = usable
        this.interlude = interlude
        this.playing = playing
        this.keepAlive = keepAlive
        if (changed) invalidate()
        if (shouldRun()) startLoop()
    }

    /** 最后一个字唱完后还要留出补亮时间，帧循环跑到补满为止。 */
    private fun animationEnd(): Long = maxOf(
        syncWords?.maxOfOrNull { AodWordProgress.effectiveEnd(it) + AodWordFill.SETTLE_MS.toLong() }
            ?: Long.MIN_VALUE,
        interlude?.last ?: Long.MIN_VALUE,
    )

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
        val position = AodWordProgress.currentPositionMs()
        val chars = if (words != null && layout != null && text.toString() == syncText) {
            AodWordProgress.progressChars(syncText, words, position)?.also {
                AodWordProgress.logProgressSample(syncRow, position, it, syncText.length)
            }
        } else {
            null
        }
        val fill = if (chars != null && words != null && layout != null) fillFor(layout, words) else null
        if (fill == null || layout == null) {
            super.onDraw(canvas)
            return
        }
        AodWordProgress.recordDraw()
        canvas.save()
        canvas.translate(
            (compoundPaddingLeft + scrollX).toFloat(),
            extendedPaddingTop.toFloat(),
        )
        drawProgress(canvas, layout, fill, position.toDouble())
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

    /** 按当前排版量出每个字符的左右横坐标（与旧版 progressX 同一口径），再建补亮模型；排版不变时复用。 */
    private fun fillFor(layout: Layout, words: List<LyricWord>): AodWordFill? {
        val textSize = paint.textSize
        if (fillText == syncText && fillWords === words && fillLayout === layout && fillTextSize == textSize &&
            fillLeaveMs == syncLeaveMs
        ) {
            return fill
        }
        val text = layout.text
        val length = text.length
        val left = FloatArray(length)
        val right = FloatArray(length)
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line).coerceAtMost(length)
            val lineLeft = layout.getLineLeft(line)
            val lineRight = layout.getLineRight(line)
            for (index in start until end) {
                val x0 = if (index <= start) lineLeft else layout.getPrimaryHorizontal(index)
                val x1 = if (index + 1 >= end) lineRight else layout.getPrimaryHorizontal(index + 1)
                left[index] = x0.coerceIn(lineLeft, lineRight)
                right[index] = x1.coerceIn(left[index], maxOf(lineRight, left[index]))
            }
        }
        charLeft = left
        charRight = right
        if (profileBuffer.size < length * 12) profileBuffer = DoubleArray(length * 12)
        fill = AodWordFill.build(
            text = syncText,
            words = words,
            charWidth = { (right[it] - left[it]).toDouble() },
            edgePx = (textSize * EDGE_WIDTH_FACTOR).toDouble(),
            leaveMs = syncLeaveMs?.toDouble(),
        )
        fillLeaveMs = syncLeaveMs
        fillText = syncText
        fillWords = words
        fillLayout = layout
        fillTextSize = textSize
        return fill
    }

    private fun drawProgress(canvas: Canvas, layout: Layout, fill: AodWordFill, positionMs: Double) {
        val bright = currentTextColor
        dimPaint.set(paint)
        dimPaint.color = withAlpha(bright, DIM_ALPHA_FACTOR)
        brightPaint.set(paint)
        brightPaint.color = bright
        brightPaint.shader = null

        val text = layout.text
        val length = minOf(text.length, fill.length)
        val buffer = profileBuffer
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            val left = layout.getLineLeft(line)
            val baseline = layout.getLineBaseline(line).toFloat()
            canvas.drawText(text, start, end, left, baseline, dimPaint)
            val count = fill.profile(start, end.coerceAtMost(length), positionMs, buffer)
            if (count == 0) continue
            var lit = false
            var full = true
            var i = 1
            while (i < count) {
                if (buffer[i] > 0.0) lit = true
                if (buffer[i] < 1.0) full = false
                i += 2
            }
            when {
                !lit -> Unit
                full -> canvas.drawText(text, start, end, left, baseline, brightPaint)
                else -> {
                    val lineLeft = left
                    val lineRight = layout.getLineRight(line)
                    val width = lineRight - lineLeft
                    if (width <= 0f) continue
                    val stops = count / 2
                    val colors = IntArray(stops)
                    val positions = FloatArray(stops)
                    var previous = 0f
                    for (stop in 0 until stops) {
                        val coord = buffer[stop * 2]
                        val index = coord.toInt().coerceIn(start, (end - 1).coerceAtLeast(start)).coerceAtMost(length - 1)
                        val fraction = (coord - index).coerceIn(0.0, 1.0).toFloat()
                        val x = charLeft[index] + (charRight[index] - charLeft[index]) * fraction
                        val position = ((x - lineLeft) / width).coerceIn(previous, 1f)
                        positions[stop] = position
                        previous = position
                        colors[stop] = AodWordFill.stopColor(bright, buffer[stop * 2 + 1])
                    }
                    brightPaint.color = AodWordFill.gradientPaintColor(bright)
                    brightPaint.shader = LinearGradient(
                        lineLeft, 0f, lineRight, 0f, colors, positions, Shader.TileMode.CLAMP,
                    )
                    canvas.drawText(text, start, end, left, baseline, brightPaint)
                    brightPaint.shader = null
                    brightPaint.color = bright
                }
            }
        }
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val alpha = ((color ushr 24) * factor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private companion object {
        /** 未唱部分相对已唱部分的亮度。 */
        const val DIM_ALPHA_FACTOR = 0.4f

        /** 柔边宽度，相对字号。 */
        const val EDGE_WIDTH_FACTOR = 0.6f

        /** 小于 DRAW_WAKE_LOCK_TIMEOUT_MS（1s），保证播放期间锁不断档。 */
        const val KEEP_ALIVE_INTERVAL_MS = 500L
    }
}
