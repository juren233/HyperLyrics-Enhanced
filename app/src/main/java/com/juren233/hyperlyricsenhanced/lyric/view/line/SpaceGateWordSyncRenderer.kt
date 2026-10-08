/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.view.LyricPlayListener
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.WordModel
import kotlin.math.abs

internal class SpaceGateWordSyncRenderer(private val view: SpaceGateLyricLineView) : LineRenderer {

    val bgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val hlPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    val progressAnimator = SpaceGateProgressAnimator()
    private val scrollStepper = ScrollStepper()
    private val textDrawer = TextDrawer()
    private val progressDiagnostics = if (BuildConfig.DEBUG) SpaceGateProgressDiagnostics() else null

    /** 纯遮挡接缝布局；null 表示非拼接模式，条带连续绘制。 */
    var seamLayout: SeamOcclusionLayout? = null

    /** 接缝（虚拟坐标）＝左槽宽；仅 [seamLayout] 非空时有意义。 */
    var seamX: Float = 0f
    var nextLineOnRight: Boolean = false

    private fun layoutFor(model: LyricModel, viewWidth: Int) = SpaceGateLineLayout(
        model.width, viewWidth.toFloat(), seamLayout, seamX,
        model.isAlignedRight, centerIfPossible, alignRight,
        nextLineOnRight = nextLineOnRight,
    )

    override fun layoutWidthFor(model: LyricModel, viewWidth: Int): Float =
        layoutFor(model, viewWidth).scrollWidth

    var isScrollOnly = false
    override var centerIfPossible = false
    override var alignRight = false

    var isCharMotionEnabled = true

    var cjkMotionLiftFactor: Float
        get() = textDrawer.cjkLiftFactor
        set(value) {
            textDrawer.cjkLiftFactor = value
        }

    var cjkMotionWaveFactor: Float
        get() = textDrawer.cjkWaveFactor
        set(value) {
            textDrawer.cjkWaveFactor = value
        }

    var latinMotionLiftFactor: Float
        get() = textDrawer.latinLiftFactor
        set(value) {
            textDrawer.latinLiftFactor = value
        }

    var latinMotionWaveFactor: Float
        get() = textDrawer.latinWaveFactor
        set(value) {
            textDrawer.latinWaveFactor = value
        }

    var isGradientEnabled = true
        set(value) {
            if (field != value) {
                field = value
                textDrawer.clearShaderCache()
            }
        }

    var typefaceSelector: ((Char) -> Typeface)?
        get() = textDrawer.typefaceSelector
        set(value) {
            textDrawer.typefaceSelector = value
        }

    var playListener: LyricPlayListener? = null
        set(value) {
            field = value
            _playListener = value ?: NoOpPlayListener
        }

    private var _playListener: LyricPlayListener = NoOpPlayListener

    var lastPosition = Long.MIN_VALUE
        private set

    /** reset 后、seek 前的首个进度可走换句追赶，见 [SwitchProgressCatchUp]。 */
    private var catchUpArmed = true
    private var catchUpUntilMs = Long.MIN_VALUE

    private var lastTraceOffset = Float.NaN

    override val isPlaying get() = progressAnimator.isAnimating
    override val isFinished get() = progressAnimator.hasFinished
    override val isStarted get() = progressAnimator.hasStarted

    fun setTextSize(size: Float) {
        bgPaint.textSize = size
        hlPaint.textSize = size
        textDrawer.updateMetrics(bgPaint)
    }

    fun setTypeface(tf: Typeface?) {
        bgPaint.typeface = tf
        hlPaint.typeface = tf
        textDrawer.updateMetrics(bgPaint)
    }

    fun setColors(background: IntArray, highlight: IntArray) {
        if (background.isNotEmpty()) bgPaint.color = background[0]
        if (highlight.isNotEmpty()) hlPaint.color = highlight[0]
        textDrawer.setColors(background, highlight)
        textDrawer.clearShaderCache()
    }

    fun updateLayout(model: LyricModel, state: LineState, viewWidth: Int, viewHeight: Int) {
        textDrawer.updateMetrics(bgPaint)
        if (progressAnimator.hasFinished) {
            progressAnimator.jumpTo(model.width)
        }
        updateScrollState(model, state, viewWidth)
    }

    override fun seek(
        model: LyricModel,
        state: LineState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int
    ) {
        val target = exactTargetWidth(posMs, model)
        if (BuildConfig.DEBUG && view.isRightSide) {
            progressDiagnostics?.reset(System.identityHashCode(view))
            val word = model.wordTimingNavigator.first(posMs)
            progressDiagnostics?.tick(posMs, word?.begin, word?.end)
        }
        cancelSwitchCatchUp()
        progressAnimator.jumpTo(target)
        updateScrollState(model, state, viewWidth)
        lastPosition = posMs
        notifyProgress(model)
    }

    override fun update(
        model: LyricModel,
        state: LineState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (lastPosition != Long.MIN_VALUE && posMs < lastPosition) {
            seek(model, state, posMs, viewWidth, viewHeight)
            return
        }

        val word = model.wordTimingNavigator.first(posMs)
        if (BuildConfig.DEBUG && view.isRightSide) {
            progressDiagnostics?.tick(posMs, word?.begin, word?.end)
        }
        if (posMs < catchUpUntilMs) {
            // 追赶窗口内保持既定目标，窗口结束后由下方逐词动画接续。
            lastPosition = posMs
            return
        }
        val target = animationTargetWidth(posMs, model, word)

        if (progressAnimator.currentWidth == 0f) {
            if (startSwitchCatchUp(posMs, model)) {
                lastPosition = posMs
                return
            }
            if (word != null) progressAnimator.jumpTo(exactTargetWidth(posMs, model, word))
        }
        if (target != progressAnimator.targetWidth) {
            progressAnimator.animateTo(target, remainingDuration(posMs, word))
        }
        lastPosition = posMs
    }

    override fun step(
        deltaNanos: Long,
        model: LyricModel,
        state: LineState,
        viewWidth: Int
    ): Boolean {
        val changed = progressAnimator.step(deltaNanos)
        if (changed) {
            updateScrollState(model, state, viewWidth)
            notifyProgress(model)
        }
        if (BuildConfig.DEBUG && view.isRightSide && layoutWidthFor(model, viewWidth) > viewWidth) {
            progressDiagnostics?.frame(
                System.identityHashCode(view), model.begin, deltaNanos,
                progressAnimator.currentWidth, progressAnimator.targetWidth,
                state.scrollOffset, progressAnimator.isAnimating, changed,
            )
        }
        return changed
    }

    override fun draw(
        canvas: Canvas,
        model: LyricModel,
        paint: TextPaint,
        state: LineState,
        viewWidth: Int,
        viewHeight: Int
    ) {
        // 接缝方案（静止绕孔分段/滚动边缘滑过渐隐）由共享行布局纯函数给
        // 出；主从两槽与阴影同输入同结果。
        val layout = layoutFor(model, viewWidth)
        textDrawer.draw(
            canvas, model, viewWidth, viewHeight,
            state.scrollOffset, layout.isOverflow,
            progressAnimator.currentWidth,
            isGradientEnabled, isScrollOnly, isCharMotionEnabled, centerIfPossible, alignRight,
            bgPaint, hlPaint, paint, layout.plan(state.scrollOffset), layout.textOrigin(state.scrollOffset)
        )
        if (BuildConfig.DEBUG && view.isRightSide) {
            progressDiagnostics?.draw(progressAnimator.currentWidth)
        }
    }

    override fun reset(state: LineState) {
        if (BuildConfig.DEBUG) progressDiagnostics?.reset(System.identityHashCode(view))
        progressAnimator.reset()
        state.reset()
        lastPosition = Long.MIN_VALUE
        catchUpArmed = true
        catchUpUntilMs = Long.MIN_VALUE
        lastTraceOffset = Float.NaN
        textDrawer.clearShaderCache()
    }

    fun freeze(model: LyricModel, state: LineState, viewWidth: Int) {
        cancelSwitchCatchUp()
        progressAnimator.stopAtCurrent()
        updateScrollState(model, state, viewWidth)
        notifyProgress(model)
    }

    private fun updateScrollState(model: LyricModel, state: LineState, viewWidth: Int) {
        // 按每帧真实高亮位置跟随右槽：滚速自然随唱词快慢变化，词间不漂移。
        // 使用实际接缝而非全岛中点，避免高亮长期落在摄像头边缘。
        val highlightWidth = progressAnimator.currentWidth
        val layout = layoutFor(model, viewWidth)
        var followAnchor = resolveSpaceGateFollowAnchor(
            viewWidth.toFloat(), seamX.takeIf { seamLayout != null },
        )
        var offset = scrollStepper.compute(
            highlightWidth, layout.scrollWidth,
            viewWidth.toFloat(), progressAnimator.hasFinished, state.isScrollFinished,
            followAnchor = followAnchor,
        )
        if (!progressAnimator.hasFinished) {
            layout.followProgress(highlightWidth, followAnchor)?.let { followed ->
                offset = followed.offset
                followAnchor = followed.anchor
            }
        }
        state.scrollOffset = offset
        if (progressAnimator.hasFinished) {
            state.isScrollFinished = true
        }
        if (BuildConfig.DEBUG) {
            val finished = progressAnimator.hasFinished
            if (finished || lastTraceOffset.isNaN() || abs(offset - lastTraceOffset) >= 2f) {
                lastTraceOffset = offset
                val drawnProgress = highlightWidth + offset +
                    (layout.plan(offset)?.shiftAt(highlightWidth) ?: 0f)
                HookLogger.d(
                    "IslandScroll",
                    "sync view=${Integer.toHexString(System.identityHashCode(view))} right=${view.isRightSide} " +
                        "offset=$offset hw=$highlightWidth content=${model.width} scrollW=${layout.scrollWidth} " +
                        "vw=$viewWidth finished=$finished gate=${seamLayout != null} seam=$seamX " +
                        "follow=$followAnchor naturalProgressX=${highlightWidth + offset} drawnProgressX=$drawnProgress"
                )
            }
        }
    }

    override fun seamPlanFor(model: LyricModel, state: LineState, viewWidth: Int): SeamStripPlan? =
        layoutFor(model, viewWidth).plan(state.scrollOffset)

    private fun exactTargetWidth(posMs: Long, model: LyricModel, word: WordModel? = null): Float {
        val w = word ?: model.wordTimingNavigator.first(posMs)
        return when {
            w != null -> interpolateWordWidth(posMs, w)
            posMs >= model.end -> model.width
            posMs <= model.begin -> 0f
            else -> progressAnimator.currentWidth
        }
    }

    private fun animationTargetWidth(posMs: Long, model: LyricModel, word: WordModel? = null): Float {
        val w = word ?: model.wordTimingNavigator.first(posMs)
        return when {
            w != null -> w.endPosition
            posMs >= model.end -> model.width
            posMs <= model.begin -> 0f
            else -> progressAnimator.currentWidth
        }
    }

    private fun interpolateWordWidth(posMs: Long, word: WordModel): Float =
        SwitchProgressCatchUp.interpolate(
            posMs, word.begin, word.end, word.duration, word.startPosition, word.endPosition
        )

    /** 新句首个进度已落后于开唱时，从 0 在有限窗口内补到窗口末端的同步宽度。 */
    private fun startSwitchCatchUp(posMs: Long, model: LyricModel): Boolean {
        if (!catchUpArmed) return false
        // 尚未开唱时保持待命，首词开始后再判定。
        if (SwitchProgressCatchUp.syncWidthAt(posMs, model) <= 0f) return false
        catchUpArmed = false
        val firstBegin = model.words.firstOrNull()?.begin ?: return false
        val window = SwitchProgressCatchUp.windowMs(posMs - firstBegin) ?: return false
        val untilMs = posMs + window
        val target = SwitchProgressCatchUp.syncWidthAt(untilMs, model)
        progressAnimator.animateTo(target, window)
        catchUpUntilMs = untilMs
        if (BuildConfig.DEBUG && view.isRightSide) {
            HookLogger.d(
                "SwitchCatchUp",
                "view=${Integer.toHexString(System.identityHashCode(view))} lag=${posMs - firstBegin} " +
                    "window=$window target=$target width=${model.width}"
            )
        }
        return true
    }

    fun cancelSwitchCatchUp() {
        catchUpArmed = false
        catchUpUntilMs = Long.MIN_VALUE
    }

    private fun remainingDuration(posMs: Long, word: WordModel?): Long {
        val w = word ?: return 0L
        return (w.end - posMs).coerceAtLeast(0L)
    }

    private val dummyLyricLineView by lazy { LyricLineView(view.context) }

    private fun notifyProgress(model: LyricModel) {
        val current = progressAnimator.currentWidth
        val total = model.width

        if (!progressAnimator.hasStarted && current > 0f) {
            progressAnimator.hasStarted = true
            _playListener.onPlayStarted(dummyLyricLineView)
        }
        if (!progressAnimator.hasFinished && current >= total) {
            progressAnimator.hasFinished = true
            _playListener.onPlayEnded(dummyLyricLineView)
        }
        _playListener.onPlayProgress(dummyLyricLineView, total, current)
    }

    fun syncFrom(other: SpaceGateWordSyncRenderer) {
        this.progressAnimator.syncFrom(other.progressAnimator)
        this.lastPosition = other.lastPosition
    }

    companion object {
        private val NoOpPlayListener = object : LyricPlayListener {
            override fun onPlayStarted(view: LyricLineView) {}
            override fun onPlayEnded(view: LyricLineView) {}
            override fun onPlayProgress(view: LyricLineView, total: Float, progress: Float) {}
        }
    }
}
