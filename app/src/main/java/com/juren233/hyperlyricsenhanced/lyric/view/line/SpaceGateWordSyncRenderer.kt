/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.juren233.hyperlyricsenhanced.lyric.view.LyricPlayListener
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.WordModel

internal class SpaceGateWordSyncRenderer(private val view: SpaceGateLyricLineView) : LineRenderer {

    val bgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val hlPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    val progressAnimator = SpaceGateProgressAnimator()
    private val scrollStepper = ScrollStepper()
    private val textDrawer = TextDrawer()

    var isScrollOnly = false
    override var centerIfPossible = false

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

    var playListener: LyricPlayListener? = null
        set(value) {
            field = value
            _playListener = value ?: NoOpPlayListener
        }

    private var _playListener: LyricPlayListener = NoOpPlayListener

    var lastPosition = Long.MIN_VALUE
        private set

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
        val target = animationTargetWidth(posMs, model, word)

        if (word != null && progressAnimator.currentWidth == 0f) {
            progressAnimator.jumpTo(exactTargetWidth(posMs, model, word))
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
        if (progressAnimator.step(deltaNanos)) {
            updateScrollState(model, state, viewWidth)
            notifyProgress(model)
            return true
        }
        return false
    }

    override fun draw(
        canvas: Canvas,
        model: LyricModel,
        paint: TextPaint,
        state: LineState,
        viewWidth: Int,
        viewHeight: Int
    ) {
        textDrawer.draw(
            canvas, model, viewWidth, viewHeight,
            state.scrollOffset, model.width > viewWidth,
            progressAnimator.currentWidth,
            isGradientEnabled, isScrollOnly, isCharMotionEnabled, centerIfPossible,
            bgPaint, hlPaint, paint
        )
    }

    override fun reset(state: LineState) {
        progressAnimator.reset()
        state.reset()
        lastPosition = Long.MIN_VALUE
        textDrawer.clearShaderCache()
    }

    fun freeze(model: LyricModel, state: LineState, viewWidth: Int) {
        progressAnimator.stopAtCurrent()
        updateScrollState(model, state, viewWidth)
        notifyProgress(model)
    }

    private fun updateScrollState(model: LyricModel, state: LineState, viewWidth: Int) {
        val offset = scrollStepper.compute(
            progressAnimator.currentWidth, model.width,
            viewWidth.toFloat(), progressAnimator.hasFinished, state.isScrollFinished
        )
        state.scrollOffset = offset
        if (progressAnimator.hasFinished) {
            state.isScrollFinished = true
        }
    }

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

    private fun interpolateWordWidth(posMs: Long, word: WordModel): Float {
        val duration = (word.end - word.begin).takeIf { it > 0 } ?: word.duration
        if (duration <= 0L) return word.endPosition
        val progress = ((posMs - word.begin).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return word.startPosition + (word.endPosition - word.startPosition) * progress
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

