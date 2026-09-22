/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.TextPaint
import android.view.animation.LinearInterpolator
import androidx.core.graphics.withTranslation
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.view.dp
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel
import kotlin.math.abs

internal class SpaceGateScrollTextRenderer : LineRenderer {

    companion object {
        private const val DEFAULT_SPEED_DP = 40f
    }

    private val interpolator = LinearInterpolator()

    var ghostSpacing: Float = 40f.dp
    var scrollSpeed: Float = pxPerMs(DEFAULT_SPEED_DP)
        set(value) { field = pxPerMs(value) }
    var initialDelayMs: Int = 400
    var loopDelayMs: Int = 800
    var repeatCount: Int = -1
    var stopAtEnd: Boolean = false

    override val isPlaying get() = isRunning || isPendingDelay
    override val isFinished get() = finished
    override val isStarted get() = true
    override var centerIfPossible = false
    override var alignRight = false

    var typefaceSelector: ((Char) -> Typeface)? = null

    /** 分离模式挖孔布局；null 表示未挖孔，条带连续。 */
    var gateSplit: GateSplitLayout? = null

    val scrollProgress get() = currentUnitOffset

    private fun contentWidthOf(model: LyricModel): Float =
        gateSplit?.holedWidth ?: model.width

    var isRunning = false
    var isPendingDelay = false
    var finished = false
    var currentRepeat = 0
    var delayRemainingNanos = 0L
    var currentUnitOffset = 0f
    private var lastViewWidth = 0
    private var lastLyricWidth = 0f
    private var cachedBaseline = 0f
    private var cachedViewHeight = -1

    override fun step(
        deltaNanos: Long,
        model: LyricModel,
        state: LineState,
        viewWidth: Int
    ): Boolean {
        lastViewWidth = viewWidth
        lastLyricWidth = model.width
        if (BuildConfig.DEBUG) logPlainTrace(state, contentWidthOf(model), viewWidth.toFloat())

        if (finished) return false

        val vw = viewWidth.toFloat()
        val contentWidth = contentWidthOf(model)
        if (contentWidth <= vw) {
            state.scrollOffset = 0f
            state.isScrollFinished = true
            markFinished(state)
            return false
        }

        if (isPendingDelay) {
            delayRemainingNanos -= deltaNanos
            if (delayRemainingNanos <= 0) {
                isPendingDelay = false
                isRunning = true
                return false
            } else {
                return false
            }
        }

        if (!isRunning) return false

        val unit = contentWidth + ghostSpacing
        val deltaPx = scrollSpeed * (deltaNanos / 1_000_000f)
        currentUnitOffset += deltaPx

        val isLastRepeat = repeatCount > 0 && (currentRepeat + 1) >= repeatCount

        if (stopAtEnd && isLastRepeat) {
            val targetStopOffset = contentWidth - vw
            if (currentUnitOffset >= targetStopOffset) {
                currentUnitOffset = targetStopOffset
                state.scrollOffset = -targetStopOffset
                state.isScrollFinished = true
                markFinished(state)
                return true
            }
        }

        if (currentUnitOffset >= unit) {
            currentUnitOffset -= unit
            currentRepeat++
            if (repeatCount < 0 || currentRepeat < repeatCount) {
                scheduleDelay(loopDelayMs.toLong())
                state.scrollOffset = 0f
                state.isScrollFinished = false
            } else {
                state.scrollOffset = 0f
                state.isScrollFinished = true
                markFinished(state)
            }
            return true
        }

        val progress = (currentUnitOffset / unit).coerceIn(0f, 1f)
        val easedOffset = -interpolator.getInterpolation(progress) * unit
        state.scrollOffset = easedOffset
        state.isScrollFinished = false
        return true
    }

    override fun draw(
        canvas: Canvas,
        model: LyricModel,
        paint: TextPaint,
        state: LineState,
        viewWidth: Int,
        viewHeight: Int
    ) {
        val vw = viewWidth.toFloat()
        val contentWidth = contentWidthOf(model)
        val offset = resolvePlainTextOffset(
            contentWidth,
            vw,
            state.scrollOffset,
            model.isAlignedRight,
            centerIfPossible,
            alignRight
        )

        if (cachedViewHeight != viewHeight) {
            val fm = paint.fontMetrics
            cachedBaseline = (viewHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
            cachedViewHeight = viewHeight
        }

        val visible = offset < vw && offset + contentWidth > 0
        if (visible) {
            drawStrip(canvas, model, paint, offset)
        }

        // Space gate doesn't loop ghost texts across the portal, but keep it for normal marquee
        if (contentWidth > vw) {
            val rightEdge = offset + contentWidth
            if (rightEdge < vw) {
                val ghostX = rightEdge + ghostSpacing
                if (ghostX < vw) {
                    drawStrip(canvas, model, paint, ghostX)
                }
            }
        }
    }

    /** 挖孔时按字符边界拆两段绘制，后段从 [GateSplitLayout.runBStripStart] 起笔。 */
    private fun drawStrip(
        canvas: Canvas,
        model: LyricModel,
        paint: TextPaint,
        startX: Float
    ) {
        val split = gateSplit
        val selector = typefaceSelector
        if (split == null || split.holeWidth <= 0f || split.splitCharIndex <= 0) {
            val text = model.text
            if (text.isEmpty()) return
            canvas.withTranslation(x = startX) {
                if (selector != null) {
                    MixedTypefaceText.drawText(canvas, text, 0f, cachedBaseline, paint, selector)
                } else {
                    drawText(text, 0f, cachedBaseline, paint)
                }
            }
            return
        }
        val k = split.splitCharIndex.coerceIn(0, model.text.length)
        canvas.withTranslation(x = startX) {
            drawRun(canvas, model.text, 0, k, 0f, paint, selector)
        }
        canvas.withTranslation(x = startX) {
            drawRun(canvas, model.text, k, model.text.length, split.runBStripStart - split.runAWidth, paint, selector)
        }
    }

    private fun drawRun(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        extraShift: Float,
        paint: TextPaint,
        selector: ((Char) -> Typeface)?
    ) {
        if (end <= start) return
        canvas.withTranslation(x = extraShift) {
            if (selector != null) {
                MixedTypefaceText.drawText(canvas, text.substring(start, end), 0f, cachedBaseline, paint, selector)
            } else {
                drawText(text, start, end, 0f, cachedBaseline, paint)
            }
        }
    }

    override fun seek(
        model: LyricModel,
        state: LineState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int
    ) {
        lastViewWidth = viewWidth
        lastLyricWidth = model.width
        startFromBeginning(model, state)
    }

    override fun update(
        model: LyricModel,
        state: LineState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int
    ) {
        lastViewWidth = viewWidth
        lastLyricWidth = model.width
        startFromBeginning(model, state)
    }

    private fun startFromBeginning(model: LyricModel, state: LineState) {
        resetInternal()
        state.reset()

        if (repeatCount == 0) {
            markFinished(state)
            return
        }
        scheduleDelay(initialDelayMs.toLong())
    }

    override fun reset(state: LineState) {
        resetInternal()
        state.reset()
    }

    private fun resetInternal() {
        isRunning = false
        isPendingDelay = false
        finished = false
        currentRepeat = 0
        currentUnitOffset = 0f
        delayRemainingNanos = 0L
    }

    private fun scheduleDelay(delayMs: Long) {
        if (delayMs <= 0L) {
            isRunning = true
            isPendingDelay = false
        } else {
            delayRemainingNanos = delayMs * 1_000_000L
            isPendingDelay = true
            isRunning = false
        }
    }

    private fun markFinished(state: LineState) {
        isRunning = false
        isPendingDelay = false
        finished = true
        state.isScrollFinished = true
    }

    /** Debug-only 纯文本跑马灯轨迹：状态翻转或位移 ≥8px 时输出一条。 */
    private fun logPlainTrace(state: LineState, contentWidth: Float, vw: Float) {
        val flags =
            "run=$isRunning pend=$isPendingDelay fin=$finished rep=$currentRepeat/${repeatCount}c stopEnd=$stopAtEnd"
        if (flags != lastTraceFlags || abs(currentUnitOffset - lastTraceUnitOffset) >= 8f) {
            lastTraceFlags = flags
            lastTraceUnitOffset = currentUnitOffset
            HookLogger.d(
                "IslandScroll",
                "plain unitOffset=$currentUnitOffset unit=${contentWidth + ghostSpacing} " +
                    "content=$contentWidth vw=$vw scrollOffset=${state.scrollOffset} $flags"
            )
        }
    }

    private var lastTraceUnitOffset = Float.NaN
    private var lastTraceFlags = ""

    private fun pxPerMs(dpPerSec: Float): Float {
        return (dpPerSec * Resources.getSystem().displayMetrics.density) / 1000f
    }

    fun syncFrom(other: SpaceGateScrollTextRenderer) {
        this.isRunning = other.isRunning
        this.isPendingDelay = other.isPendingDelay
        this.finished = other.finished
        this.currentRepeat = other.currentRepeat
        this.delayRemainingNanos = other.delayRemainingNanos
        this.currentUnitOffset = other.currentUnitOffset
    }
}
