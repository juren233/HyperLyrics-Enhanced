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

    /** 纯遮挡接缝布局；null 表示非拼接模式，条带连续绘制。 */
    var seamLayout: SeamOcclusionLayout? = null

    /** 接缝（虚拟坐标）＝左槽宽；仅 [seamLayout] 非空时有意义。 */
    var seamX: Float = 0f
    var nextLineOnRight: Boolean = false
    private fun hasRightPreview(viewWidth: Int): Boolean =
        nextLineOnRight && seamX >= 0f && seamX < viewWidth

    val scrollProgress get() = currentUnitOffset

    private fun layoutFor(model: LyricModel, viewWidth: Int) = SpaceGateLineLayout(
        model.width, viewWidth.toFloat(), seamLayout, seamX,
        model.isAlignedRight, centerIfPossible, alignRight,
        nextLineOnRight = nextLineOnRight,
    )

    override fun layoutWidthFor(model: LyricModel, viewWidth: Int): Float =
        layoutFor(model, viewWidth).scrollWidth

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
        if (BuildConfig.DEBUG) logPlainTrace(state, layoutWidthFor(model, viewWidth), viewWidth.toFloat())

        if (finished) return false

        val vw = viewWidth.toFloat()
        val contentWidth = layoutWidthFor(model, viewWidth)
        if (contentWidth <= vw && !hasRightPreview(viewWidth)) {
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

        if (hasRightPreview(viewWidth)) {
            // The upcoming line replaces the repeated ghost. A short current line
            // stays in place while the same clock slides its preview into spare room.
            val travel = maxOf(contentWidth - vw, (vw - seamX) / 2f)
            currentUnitOffset = minOf(currentUnitOffset, travel)
            state.scrollOffset = -minOf(currentUnitOffset, (contentWidth - vw).coerceAtLeast(0f))
            if (currentUnitOffset >= travel) markFinished(state)
            return true
        }

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
        val layout = layoutFor(model, viewWidth)
        val contentWidth = layout.scrollWidth
        val drawnOffset = layout.textOrigin(state.scrollOffset)
        // 接缝方案（静止绕孔分段/滚动边缘滑过渐隐）由共享行布局纯函数给出，
        // 主从两槽与阴影同输入同结果。
        val plan = layout.plan(state.scrollOffset)

        if (cachedViewHeight != viewHeight) {
            val fm = paint.fontMetrics
            cachedBaseline = (viewHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
            cachedViewHeight = viewHeight
        }

        val visible = drawnOffset < vw && drawnOffset + contentWidth > 0
        if (visible) {
            drawStrip(canvas, model, paint, drawnOffset, plan)
        }

        // Space gate doesn't loop ghost texts across the portal, but keep it for normal marquee
        if (contentWidth > vw && !hasRightPreview(viewWidth)) {
            val rightEdge = drawnOffset + contentWidth
            if (rightEdge < vw) {
                val ghostX = rightEdge + ghostSpacing
                if (ghostX < vw) {
                    // ghost 仅存在于滚动中：纯过缝方案（无段带），跨缝渐隐同正文。
                    val ghostPlan = seamLayout?.let { SeamStripPlan.transit(it, ghostX, seamX) }
                    drawStrip(canvas, model, paint, ghostX, ghostPlan)
                }
            }
        }
    }

    /**
     * 按接缝方案分段绘制：每段带在自身平移量内以自然条带坐标起笔（shader/
     * 坐标系恒自然），跨缝单元在所属段内以多数侧裁剪＋渐隐绘制。
     */
    private fun drawStrip(
        canvas: Canvas,
        model: LyricModel,
        paint: TextPaint,
        startX: Float,
        plan: SeamStripPlan?
    ) {
        val text = model.text
        if (text.isEmpty()) return
        val selector = typefaceSelector
        if (plan == null) {
            canvas.withTranslation(x = startX) {
                if (selector != null) {
                    MixedTypefaceText.drawText(canvas, text, 0f, cachedBaseline, paint, selector)
                } else {
                    drawText(text, 0f, cachedBaseline, paint)
                }
            }
            return
        }
        canvas.withTranslation(x = startX) {
            for (band in plan.bands) {
                if (band.charEnd <= band.charStart) continue
                canvas.withTranslation(x = band.delta) {
                    val st = plan.straddler
                    if (st != null && st.unit.charStart >= band.charStart && st.unit.charEnd <= band.charEnd) {
                        val fade = plan.fadeAt(startX, band.delta)
                        if (st.unit.charStart > band.charStart) {
                            drawRun(canvas, text, band.charStart, st.unit.charStart, band.startAdvance, paint, selector, null)
                        }
                        drawRun(canvas, text, st.unit.charStart, st.unit.charEnd, st.unit.start, paint, selector, fade)
                        if (st.unit.charEnd < band.charEnd) {
                            drawRun(canvas, text, st.unit.charEnd, band.charEnd, st.unit.end, paint, selector, null)
                        }
                    } else {
                        drawRun(canvas, text, band.charStart, band.charEnd, band.startAdvance, paint, selector, null)
                    }
                }
            }
        }
    }

    private fun drawRun(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        paint: TextPaint,
        selector: ((Char) -> Typeface)?,
        fade: SeamStripPlan.SeamFade?
    ) {
        if (end <= start) return
        canvas.withSeamFade(fade, paint) {
            canvas.withTranslation(x = x) {
                if (selector != null) {
                    MixedTypefaceText.drawText(canvas, text.substring(start, end), 0f, cachedBaseline, paint, selector)
                } else {
                    drawText(text, start, end, 0f, cachedBaseline, paint)
                }
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

    override fun seamPlanFor(model: LyricModel, state: LineState, viewWidth: Int): SeamStripPlan? =
        layoutFor(model, viewWidth).plan(state.scrollOffset)

    fun syncFrom(other: SpaceGateScrollTextRenderer) {
        this.isRunning = other.isRunning
        this.isPendingDelay = other.isPendingDelay
        this.finished = other.finished
        this.currentRepeat = other.currentRepeat
        this.delayRemainingNanos = other.delayRemainingNanos
        this.currentUnitOffset = other.currentUnitOffset
    }
}
