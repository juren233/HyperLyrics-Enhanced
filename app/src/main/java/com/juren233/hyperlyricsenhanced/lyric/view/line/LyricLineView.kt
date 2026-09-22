/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.view.doOnAttach
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.model.LyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.Highlight
import com.juren233.hyperlyricsenhanced.lyric.view.LyricPlayListener
import com.juren233.hyperlyricsenhanced.lyric.view.Marquee
import com.juren233.hyperlyricsenhanced.lyric.view.TextLook
import com.juren233.hyperlyricsenhanced.lyric.view.UpdatableColor
import com.juren233.hyperlyricsenhanced.lyric.view.WordMotion
import com.juren233.hyperlyricsenhanced.lyric.view.LyricHugMeasureWindow
import com.juren233.hyperlyricsenhanced.lyric.view.dp
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.createModel
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.emptyLyricModel
import com.juren233.hyperlyricsenhanced.lyric.view.sp
import kotlin.math.abs
import kotlin.math.ceil

open class LyricLineView(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), UpdatableColor, LyricTextPaintOwner {

    init {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(10.dp)
    }

    override val textPaint: TextPaint = TextPaintX().apply { textSize = 24f.sp }

    val model: LyricModel get() = _model
    private var _model: LyricModel = emptyLyricModel()

    private val interludeDotsRenderer = InterludeDotsRenderer()

    /**
     * 分离歌词右槽：仍绑定整条间奏指示器行（保持测量宽度与状态机一致），
     * 但不重复绘制第二组指示点——整岛只保留左槽（条带起点）的一组，
     * 与全岛歌词一致。
     */
    var hideInterludeIndicator: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    val lineWidth: Float
        get() = if (interludeDotsRenderer.isIndicator(_model)) {
            interludeDotsRenderer.width(textPaint.textSize)
        } else {
            _model.width
        }

    // ---- Metadata marquee overrides (called from HyperLyrics Enhanced) ----
    fun setMarqueeSpeed(speed: Float) { scrollRenderer.scrollSpeed = speed }
    fun setMarqueeInitialDelay(ms: Int) { scrollRenderer.initialDelayMs = ms }
    fun setMarqueeLoopDelay(ms: Int) { scrollRenderer.loopDelayMs = ms }
    fun setMarqueeRepeatCount(count: Int) { scrollRenderer.repeatCount = count }
    fun setMarqueeStopAtEnd(stop: Boolean) { scrollRenderer.stopAtEnd = stop }
    val isPlainText: Boolean get() = _model.isPlainText
    val isInterludeIndicator: Boolean get() = interludeDotsRenderer.isIndicator(_model)
    val isWordSync: Boolean get() = !isPlainText

    /**
     * 跑马灯判定与滚动使用的可见宽度：布局宽度优先。
     * 原生岛会以展开几何做瞬态测量（spec 远大于动态上限后的最终布局），
     * measuredWidth 会被瞬态污染——渲染器据此误判"文本放得下"并永久判完成，
     * 而布局宽度才是用户实际看到的宽度。首次布局前回退 measuredWidth。
     */
    val scrollWidth: Int
        get() = width.takeIf { it > 0 } ?: measuredWidth

    val isOverflow: Boolean get() = lineWidth > scrollWidth
    val isPlaying: Boolean get() = activeRenderer.isPlaying
    val isFinished: Boolean get() = activeRenderer.isFinished
    val isStarted: Boolean get() = activeRenderer.isStarted

    /** Read-only, Debug-only call site: correlate drawn text with the island viewport. */
    internal fun overlapDiagnosticState(): String =
        "text=${_model.text.length}/${_model.text.hashCode()} plain=$isPlainText " +
            "lineW=$lineWidth scrollW=$scrollWidth overflow=$isOverflow " +
            "offset=${lineState.scrollOffset} progress=${scrollRenderer.scrollProgress} " +
            "unlocked=$scrollUnlocked started=$scrollStarted renderer=$isStarted/$isPlaying/$isFinished " +
            "static=$isStaticPreview align=$alignRight center=$centerIfPossible textX=${currentTextStartX()}"

    var isScrollOnly: Boolean = false
        set(value) {
            field = value
            syncRenderer.isScrollOnly = value
        }

    var centerIfPossible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncRenderer.centerIfPossible = value
            scrollRenderer.centerIfPossible = value
            traceSwitch("centering_changed")
            invalidate()
        }

    var alignRight: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncRenderer.alignRight = value
            scrollRenderer.alignRight = value
            traceSwitch("right_alignment_changed")
            invalidate()
        }

    var playListener: LyricPlayListener? = null
        set(value) {
            field = value
            syncRenderer.playListener = value
        }

    /**
     * 动态长度模式：测量宽度收缩为当前行文字实际宽度（不超过可用宽度），
     * 让系统按内容实测宽度计算超级岛总宽度；文字超长时回到可用宽度并沿用滚动。
     */
    var hugContentWidth: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) hugWidthFloor = null
            requestLayout()
        }

    /**
     * 动态长度二段测量的宽度下限，由 RichLyricLineView 在测量期下发：
     * 取组内最长行（对唱固定长度时为全曲最长行）与自身 hug 宽度的较大值，
     * 让短于下限的行保留“视图宽度 − 文字宽度”的换边/居中偏移空间。
     * null 表示不设下限，hug 行为不变。
     */
    internal var hugWidthFloor: Int? = null

    var isWordCharMotionEnabled: Boolean
        get() = syncRenderer.isCharMotionEnabled
        set(value) {
            if (syncRenderer.isCharMotionEnabled == value) return
            syncRenderer.isCharMotionEnabled = value
            requestLayout()
            invalidate()
        }

    var wordMotion: WordMotion = WordMotion()
        set(value) {
            if (field == value) return
            field = value
            syncRenderer.isCharMotionEnabled = value.enabled
            syncRenderer.cjkMotionLiftFactor = value.cjkLiftFactor
            syncRenderer.cjkMotionWaveFactor = value.cjkWaveFactor
            syncRenderer.latinMotionLiftFactor = value.latinLiftFactor
            syncRenderer.latinMotionWaveFactor = value.latinWaveFactor
            requestLayout()
            invalidate()
        }

    private val lineState = LineState()
    private val scrollRenderer = ScrollTextRenderer()
    private val syncRenderer = WordSyncRenderer(this)
    private val lineShadowRenderer = LineShadowRenderer()

    override fun forEachDrawingTextPaint(action: (TextPaint) -> Unit) {
        // Host shadow parameters live only on textPaint. Word-sync color paints remain untouched.
        action(textPaint)
    }

    private val animator = Animator()

    private var baseTypeface: Typeface = Typeface.DEFAULT
    private var narrowTypeface: Typeface? = null

    private val currentTypefaceSelector: ((Char) -> Typeface)?
        get() = MixedTypefaceText.typefaceSelector(baseTypeface, narrowTypeface)

    private var activeRenderer: LineRenderer = scrollRenderer

    private var primaryColors = intArrayOf()
    private var backgroundColors = intArrayOf()
    private var highlightColors = intArrayOf()

    private var ghostSpacing: Float = 40f.dp
    private var scrollStarted = false
    private var scrollUnlocked = false
    private var playbackActive = true

    var isStaticPreview: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                animator.stop()
                scrollUnlocked = false
                scrollStarted = false
                lineState.reset()
            }
            updatePlainTextColors()
            invalidate()
        }

    val textSize: Float get() = textPaint.textSize

    fun currentTextStartX(availableWidthOverride: Float? = null): Float =
        resolveTextStartX(lineWidth, _model.isAlignedRight, availableWidthOverride = availableWidthOverride)

    fun textStartX(
        text: String?,
        isAlignedRight: Boolean,
        centerIfPossibleOverride: Boolean? = null,
        alignRightOverride: Boolean? = null,
        availableWidthOverride: Float? = null
    ): Float = resolveTextStartX(
        measureLineTextWidth(text),
        isAlignedRight,
        centerIfPossibleOverride,
        alignRightOverride,
        availableWidthOverride
    )

    /**
     * 与 LyricModel.updateSizes 同管线测宽：混排窄体开启时英数走窄字体
     * （≈0.8×宽），朴素 measureText 全按基础字体会高估 ~25%。供提升动画
     * 计算落定文本锚点（中点/右缘）使用。
     */
    fun measureLineTextWidth(text: String?): Float {
        val raw = text.orEmpty()
        val selector = currentTypefaceSelector
        return if (selector != null) {
            MixedTypefaceText.measureText(textPaint, raw, selector)
        } else {
            val measureWidth = textPaint.measureText(raw)
            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(raw, 0, raw.length, bounds)
            if (bounds.right > measureWidth) bounds.right.toFloat() else measureWidth
        }
    }

    fun setTextSize(size: Float) {
        val needsUpdate = textPaint.textSize != size || syncRenderer.bgPaint.textSize != size
        if (!needsUpdate) return
        textPaint.textSize = size
        syncRenderer.setTextSize(size)
        refreshSizes()
        syncRenderer.updateLayout(_model, lineState, scrollWidth, measuredHeight)
        invalidate()
    }

    private fun applyCurrentTypeface() {
        textPaint.typeface = baseTypeface
        syncRenderer.setTypeface(baseTypeface)
        syncRenderer.typefaceSelector = currentTypefaceSelector
        scrollRenderer.typefaceSelector = currentTypefaceSelector
    }

    fun setLyric(rawLine: LyricLine?) {
        val line = if (rawLine?.text.isNullOrBlank()) null else rawLine

        traceSwitch("before_bind", dumpHistory = true)
        reset()
        scrollUnlocked = false
        scrollStarted = false

        _model = line?.normalize()?.createModel() ?: emptyLyricModel()
        applyCurrentTypeface()
        activeRenderer = if (_model.isPlainText) scrollRenderer else syncRenderer
        refreshSizes()
        updateColorsIfReady()
        traceSwitch("after_bind")
        invalidate()
    }

    fun configureWith(
        text: TextLook, highlight: Highlight, marquee: Marquee,
        gradient: Boolean, fadingEdge: Int, center: Boolean
    ) {
        this.centerIfPossible = center
        updateColor(text.color, highlight.background, highlight.foreground)
        setTextSize(text.size)
        baseTypeface = text.typeface
        narrowTypeface = text.narrowTypeface
        applyCurrentTypeface()
        syncRenderer.isGradientEnabled = gradient

        scrollRenderer.apply {
            scrollSpeed = marquee.speed
            ghostSpacing = marquee.spacing
            initialDelayMs = marquee.initialDelay
            loopDelayMs = marquee.loopDelay
            repeatCount = marquee.repeatCount
            stopAtEnd = marquee.stopAtEnd
        }
        ghostSpacing = marquee.spacing

        if (fadingEdge <= 0) {
            setFadingEdgeLength(0)
            isHorizontalFadingEdgeEnabled = false
        } else {
            setFadingEdgeLength(fadingEdge)
            isHorizontalFadingEdgeEnabled = true
        }

        refreshSizes()
        animator.stop()
        if (!isStaticPreview && playbackActive) animator.startIfNeeded()
        invalidate()
    }

    fun requestScroll() {
        if (isStaticPreview) return
        doOnAttach {
            if (isStaticPreview) return@doOnAttach
            if (!scrollUnlocked) {
                MarqueeDiag.d(this, "unlocked") { "overflow=$isOverflow playing=$playbackActive" }
            }
            scrollUnlocked = true
            if (isPlainText && playbackActive) startScrolling()
        }
    }

    /**
     * 换句切换过渡（淡出窗口）暂停滚动/逐字步进：布局冻结管不住 draw 层动画，
     * 淡出中的旧句若继续滚动，视觉上就是"换句前位置先移动"。暂停让旧句在被
     * 替换前像素级静止；新内容落地后由正常进度 tick 恢复。
     */
    private var contentSwitchPaused = false
    private val switchTrace = if (BuildConfig.DEBUG) LyricSwitchTrace(this) else null

    private fun traceSwitch(event: String, dumpHistory: Boolean = false) {
        if (!BuildConfig.DEBUG || !hugContentWidth || _model.text.isEmpty()) return
        switchTrace?.record(
            event, _model, scrollWidth, lineState.scrollOffset,
            centerIfPossible, alignRight, contentSwitchPaused,
            animator.isFrameLoopRunning, dumpHistory
        )
    }

    fun pauseForContentSwitch() {
        if (contentSwitchPaused) return
        traceSwitch("before_pause", dumpHistory = true)
        contentSwitchPaused = true
        animator.stop()
        invalidate()
    }

    fun resumeFromContentSwitch() {
        if (!contentSwitchPaused) return
        contentSwitchPaused = false
        invalidate()
    }

    fun seekTo(posMs: Long) {
        if (contentSwitchPaused || isStaticPreview) return
        if (isInterludeIndicator) {
            interludeDotsRenderer.updatePosition(posMs)
            invalidate()
            return
        }
        if (isPlainText) {
            if (playbackActive) startScrolling()
        } else {
            activeRenderer.seek(_model, lineState, posMs, scrollWidth, measuredHeight)
            if (playbackActive) {
                animator.startIfNeeded()
            } else {
                animator.stop()
                invalidate()
            }
        }
    }

    fun updatePosition(posMs: Long) {
        if (contentSwitchPaused || isStaticPreview) return
        if (isInterludeIndicator) {
            interludeDotsRenderer.updatePosition(posMs)
            if (playbackActive) postInvalidateOnAnimation() else invalidate()
            return
        }
        if (isWordSync) {
            if (syncRenderer.isScrollOnly && !isOverflow) return
            if (playbackActive) {
                activeRenderer.update(_model, lineState, posMs, scrollWidth, measuredHeight)
                if (syncRenderer.isPlaying && !syncRenderer.isFinished) {
                    animator.startIfNeeded()
                }
            } else {
                activeRenderer.seek(_model, lineState, posMs, scrollWidth, measuredHeight)
                animator.stop()
                invalidate()
            }
        } else {
            if (playbackActive) startScrolling()
        }
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) {
            if (active) resumePlaybackAnimation() else animator.stop()
            return
        }
        playbackActive = active
        interludeDotsRenderer.setPlaybackActive(active)
        if (!active) {
            animator.stop()
            (activeRenderer as? WordSyncRenderer)?.let { renderer ->
                renderer.freeze(_model, lineState, scrollWidth)
                if (isShown) invalidate()
            }
            return
        }

        resumePlaybackAnimation()
    }

    private fun resumePlaybackAnimation() {
        if (contentSwitchPaused) {
            traceSwitch("resume_blocked_while_switch_paused")
            return
        }
        if (isPlainText) {
            if (!scrollUnlocked) return
            if (!scrollStarted) {
                startScrolling()
            } else if (activeRenderer.isPlaying) {
                animator.startIfNeeded()
            }
        } else if (activeRenderer.isPlaying && !activeRenderer.isFinished) {
            animator.startIfNeeded()
        }
    }

    fun refreshSizes() {
        _model.updateSizes(textPaint, currentTypefaceSelector)
    }

    fun relayout() {
        traceSwitch("before_relayout")
        if (isWordSync) syncRenderer.updateLayout(_model, lineState, scrollWidth, measuredHeight)
        traceSwitch("after_relayout")
    }

    override fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        primaryColors = primary
        backgroundColors = background
        highlightColors = highlight

        updatePlainTextColors()
        syncRenderer.setColors(background, highlight)
        invalidate()
    }

    fun reset() {
        animator.stop()
        interludeDotsRenderer.reset()
        lineState.reset()
        scrollRenderer.reset(lineState)
        syncRenderer.reset(lineState)
        lineShadowRenderer.clear()
        _model = emptyLyricModel()
        activeRenderer = scrollRenderer
        lastWidthOverflow = null
        refreshSizes()
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) relayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            refreshSizes()
            updateColorsIfReady()
        }
        if (w != oldw && w > 0) {
            MarqueeDiag.d(this, "width_changed") {
                "w=$w oldw=$oldw overflow=$isOverflow lineWidth=$lineWidth"
            }
            onAvailableWidthChanged()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        evaluateShownRecovery()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        evaluateShownRecovery()
    }

    /** 上一次可见性评估结果；null 表示尚未评估过。 */
    private var lastShownState: Boolean? = null

    /**
     * 隐藏窗口（岛收起、fake 过渡、暂停隐藏）里 Animator.doFrame 因 !isShown
     * 自停且无人再踢，形成"渲染器自认在播放但帧回调没跑"的卡死态。重新可见时
     * 直接续播（不重置滚动位置）；判定交给 MarqueeRestartPolicy.canResumeFrameLoopOnShown。
     */
    private fun evaluateShownRecovery() {
        val shown = isShown
        val previous = lastShownState
        lastShownState = shown
        if (previous == null || previous == shown) return
        MarqueeDiag.i(this, "shown_flip") {
            "shown=$shown overflow=$isOverflow unlocked=$scrollUnlocked " +
                "started=$scrollStarted rendererPlaying=${scrollRenderer.isPlaying} " +
                "frameRunning=${animator.isFrameLoopRunning}"
        }
        if (!shown) return
        if (!MarqueeRestartPolicy.canResumeFrameLoopOnShown(
                playbackActive = playbackActive,
                isStaticPreview = isStaticPreview,
                isPlainText = isPlainText,
                scrollUnlocked = scrollUnlocked,
                isOverflow = isOverflow,
                rendererPlaying = scrollRenderer.isPlaying,
                frameLoopRunning = animator.isFrameLoopRunning,
            )
        ) {
            return
        }
        MarqueeDiag.i(this, "shown_resume") { "帧回调在隐藏窗口死亡，重新可见后续播" }
        animator.startIfNeeded()
    }

    /**
     * 上一次宽度评估时的溢出状态基线。null 表示尚无基线（新行/首次布局）。
     * 宽度变化只在"放得下 → 放不下"翻转时才允许重启跑马灯；放不下 → 放不下
     * 的宽度变化（换句重算、状态栏内容变化）不得打断或重启本侧行。
     */
    private var lastWidthOverflow: Boolean? = null

    /**
     * 动态宽度/动态上限会在同一行显示期间改变岛宽（换句、状态栏内容变化触发
     * 重算）。宽度实际变化时按溢出状态翻转重新评估跑马灯，判定交给
     * MarqueeRestartPolicy。
     */
    private fun onAvailableWidthChanged() {
        val newOverflow = isOverflow
        val previous = lastWidthOverflow
        lastWidthOverflow = newOverflow
        val canRestart = MarqueeRestartPolicy.canRestartOnWidthChange(
            playbackActive = playbackActive,
            isStaticPreview = isStaticPreview,
            isPlainText = isPlainText,
            scrollUnlocked = scrollUnlocked,
            previousOverflow = previous,
            isOverflow = newOverflow,
            isShown = isShown,
            rendererPlaying = scrollRenderer.isPlaying,
            frameLoopRunning = animator.isFrameLoopRunning,
        )
        MarqueeDiag.i(this, "width_flip_eval") {
            "prev=$previous new=$newOverflow restarted=$canRestart " +
                "playing=$playbackActive unlocked=$scrollUnlocked started=$scrollStarted " +
                "shown=$isShown rendererPlaying=${scrollRenderer.isPlaying} " +
                "frameRunning=${animator.isFrameLoopRunning}"
        }
        if (!canRestart) {
            return
        }
        scrollStarted = false
        startScrolling()
    }

    override fun onDraw(canvas: Canvas) {
        if (interludeDotsRenderer.isIndicator(_model)) {
            if (!hideInterludeIndicator) {
                interludeDotsRenderer.draw(
                    canvas,
                    _model,
                    textPaint,
                    scrollWidth,
                    measuredHeight,
                    centerIfPossible,
                    alignRight
                )
                if (playbackActive && !isStaticPreview && isShown) postInvalidateOnAnimation()
            }
        } else {
            drawShadowAndContent(canvas, scrollWidth)
        }
    }

    private fun drawShadowAndContent(canvas: Canvas, availableWidth: Int) {
        traceSwitch("draw")
        lineShadowRenderer.draw(
            canvas = canvas,
            model = _model,
            sourcePaint = textPaint,
            typefaceSelector = currentTypefaceSelector,
            fontSignature = currentFontSignature(),
            viewWidth = availableWidth,
            viewHeight = measuredHeight,
            scrollOffset = lineState.scrollOffset,
            centerIfPossible = centerIfPossible,
            alignRight = alignRight,
            ghostSpacing = ghostSpacing,
        )
        textPaint.withoutShadowLayer {
            activeRenderer.draw(canvas, _model, textPaint, lineState, availableWidth, measuredHeight)
        }
    }

    internal fun currentFontSignature(): Int =
        31 * System.identityHashCode(baseTypeface) + System.identityHashCode(narrowTypeface)

    override fun getLeftFadingEdgeStrength(): Float {
        if (lineWidth <= width || horizontalFadingEdgeLength <= 0) return 0f
        val edgeL = horizontalFadingEdgeLength.toFloat()

        val offsetInUnit = if (isPlainText) {
            scrollRenderer.scrollProgress
        } else {
            -lineState.scrollOffset
        }

        if (offsetInUnit <= 0f) return 0f
        if (isPlainText && offsetInUnit > lineWidth) return 0f
        return (offsetInUnit / edgeL).coerceIn(0f, 1f)
    }

    override fun getRightFadingEdgeStrength(): Float {
        if (lineWidth <= width || horizontalFadingEdgeLength <= 0) return 0f
        val viewW = width.toFloat()
        val edgeL = horizontalFadingEdgeLength.toFloat()

        if (isPlainText) {
            if (lineState.isScrollFinished) {
                val remaining = lineWidth + lineState.scrollOffset - viewW
                return (remaining / edgeL).coerceIn(0f, 1f)
            }
            val offsetInUnit = scrollRenderer.scrollProgress
            val primaryRightEdge = lineWidth - offsetInUnit
            val ghostLeftEdge = primaryRightEdge + ghostSpacing
            return if (primaryRightEdge < viewW && ghostLeftEdge > viewW) 0f else 1.0f
        } else {
            if (isFinished) return 0f
        }

        val remaining = lineWidth + lineState.scrollOffset - viewW
        return (remaining / edgeL).coerceIn(0f, 1f)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val specW = MeasureSpec.getSize(wSpec)
        val w = if (hugContentWidth) resolveHugContentWidth(specW) else specW
        val charMotionPadding = if (isWordCharMotionEnabled) {
            val maxLift = maxOf(wordMotion.cjkLiftFactor, wordMotion.latinLiftFactor)
            ceil(textPaint.textSize * maxLift).toInt()
        } else {
            0
        }
        val textHeight = (textPaint.descent() - textPaint.ascent()).toInt() + charMotionPadding
        setMeasuredDimension(w, resolveSize(textHeight, hSpec))
    }

    private fun resolveHugContentWidth(specWidth: Int): Int {
        val shadowRadius = textPaint.getShadowLayerRadius()
        val shadowPad = if (shadowRadius > 0f) {
            ceil(shadowRadius + abs(textPaint.getShadowLayerDx())).toInt()
        } else {
            0
        }
        val hugWithFloor = maxOf(ceil(lineWidth).toInt() + shadowPad, hugWidthFloor ?: 0)
        // 两种测量角色分开（160156/160157 各错一半）：
        // - 岛宽计算探测窗口内报告固有宽度（不被 spec 截断），计算输入恒定，岛宽不振荡；
        // - 窗口外的真实布局测量按可用宽度截断，行布局与绘制不超出实际胶囊。
        // floor 未设置时保持原行为：超长行回到可用宽度并沿用滚动。
        val hug = when {
            hugWidthFloor == null -> hugWithFloor.coerceIn(0, specWidth)
            LyricHugMeasureWindow.reportIntrinsicWidth -> hugWithFloor
            else -> hugWithFloor.coerceIn(0, specWidth)
        }
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                "LyricHug",
                "slot=${(parent as? View)?.tag}, lineWidth=$lineWidth, shadowPad=$shadowPad, " +
                    "spec=$specWidth, floor=${hugWidthFloor ?: 0}, final=$hug, " +
                    "view=${System.identityHashCode(this).toString(16)}"
            )
        }
        return hug
    }

    /**
     * 用本视图当前的绘制参数（字号/字体选择器/阴影/间奏点）测量一行歌词
     * 落定后的 hug 宽度，不改变视图任何状态。
     * 与 [setLyric] 后的 [lineWidth] 实测走同一条管线，用于动态长度在
     * 预览提升动画期间同步预判内容落地后的岛宽。
     */
    fun measureIncomingHugWidth(rawLine: LyricLine?): Int {
        val line = if (rawLine?.text.isNullOrBlank()) null else rawLine
        val model = line?.normalize()?.createModel() ?: return 0
        val width = if (interludeDotsRenderer.isIndicator(model)) {
            interludeDotsRenderer.width(textPaint.textSize)
        } else {
            model.updateSizes(textPaint, currentTypefaceSelector)
            model.width
        }
        val shadowRadius = textPaint.getShadowLayerRadius()
        val shadowPad = if (shadowRadius > 0f) {
            ceil(shadowRadius + abs(textPaint.getShadowLayerDx())).toInt()
        } else {
            0
        }
        return ceil(width).toInt() + shadowPad
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && playbackActive) {
            resumePlaybackAnimation()
        } else {
            animator.stop()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        MarqueeDiag.i(this, "detached") {
            "reset 会清空 unlocked/started/宽度基线：overflow=$isOverflow"
        }
        reset()
    }

    private fun startScrolling() {
        // Do not latch before the overflow check: with dynamic island width the first
        // requestScroll can run before the final measured width exists. Latching there
        // permanently blocked scrolling even after the text really overflowed, so
        // truncated text was clipped instead of scrolling. Staying retryable lets the
        // next position tick start the marquee once the width has settled.
        val canStart = MarqueeStartPolicy.canStart(
            playbackActive = playbackActive,
            isStaticPreview = isStaticPreview,
            isPlainText = isPlainText,
            scrollUnlocked = scrollUnlocked,
            scrollStarted = scrollStarted,
            isOverflow = isOverflow,
        )
        MarqueeDiag.d(this, if (canStart) "start_latch" else "start_skip") {
            "overflow=$isOverflow lineWidth=$lineWidth scrollWidth=$scrollWidth measuredWidth=$measuredWidth " +
                "playing=$playbackActive unlocked=$scrollUnlocked started=$scrollStarted " +
                "shown=$isShown frameRunning=${animator.isFrameLoopRunning} " +
                "rendererPlaying=${scrollRenderer.isPlaying}"
        }
        if (!canStart) {
            return
        }
        scrollStarted = true
        lineState.reset()
        post {
            scrollRenderer.update(_model, lineState, 0, scrollWidth, measuredHeight)
            animator.stop()
            animator.startIfNeeded()
        }
    }

    private fun updateColorsIfReady() {
        if (primaryColors.isNotEmpty() && backgroundColors.isNotEmpty() && highlightColors.isNotEmpty()) {
            updateColor(primaryColors, backgroundColors, highlightColors)
        }
    }

    private fun updatePlainTextColors() {
        val colors = if (isStaticPreview && backgroundColors.isNotEmpty()) {
            backgroundColors
        } else {
            primaryColors
        }
        textPaint.apply {
            color = colors.firstOrNull() ?: Color.BLACK
            shader = if (colors.size > 1) makeRainbowShader(colors) else null
        }
    }

    private fun resolveTextStartX(
        textWidth: Float,
        isAlignedRight: Boolean,
        centerIfPossibleOverride: Boolean? = null,
        alignRightOverride: Boolean? = null,
        availableWidthOverride: Float? = null
    ): Float {
        // 布局宽度优先：原生岛以展开几何做瞬态测量时 measuredWidth 会被抬到
        // hug 下限（如对唱全曲最长行），据此算出的换边/居中偏移会把文本推出
        // 实际岛宽造成裁切；scrollWidth 才是用户可见宽度，语义同 scrollWidth 属性。
        // availableWidthOverride 供提升动画按落定宽度（pendingHugWidth）预算
        // 横向目标：按当前旧宽算会因溢出分支把上升线锚到左缘、落地才居中。
        val availableWidth = availableWidthOverride ?: scrollWidth.toFloat()
        val centerFlag = centerIfPossibleOverride ?: centerIfPossible
        val rightFlag = alignRightOverride ?: alignRight
        return when {
            textWidth >= availableWidth -> 0f
            rightFlag -> availableWidth - textWidth
            centerFlag -> (availableWidth - textWidth) / 2f
            isAlignedRight -> availableWidth - textWidth
            else -> 0f
        }
    }

    private var rainbowShader: Shader? = null
    private var rainbowShaderHash = 0
    private var rainbowShaderWidth = -1f

    private fun makeRainbowShader(colors: IntArray): Shader {
        val hash = colors.contentHashCode()
        if (rainbowShader != null && rainbowShaderHash == hash && rainbowShaderWidth == lineWidth) {
            return rainbowShader!!
        }
        val positions = FloatArray(colors.size) { i -> i.toFloat() / (colors.size - 1) }
        rainbowShader =
            LinearGradient(0f, 0f, lineWidth, 0f, colors, positions, Shader.TileMode.CLAMP)
        rainbowShaderHash = hash
        rainbowShaderWidth = lineWidth
        return rainbowShader!!
    }

    private inner class Animator : Choreographer.FrameCallback {
        private var running = false
        private var lastFrameNanos = 0L
        private var lastReportedFinished = false

        val isFrameLoopRunning: Boolean get() = running

        fun startIfNeeded() {
            if (playbackActive && !running && isAttachedToWindow && isShown) {
                running = true
                lastReportedFinished = false
                lastFrameNanos = 0L
                post { Choreographer.getInstance().postFrameCallback(this) }
            }
        }

        fun stop() {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            lastFrameNanos = 0L
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running || !playbackActive || !isAttachedToWindow || !isShown) {
                running = false
                return
            }

            val deltaNanos = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
            lastFrameNanos = frameTimeNanos

            val renderer = activeRenderer
            val changed = renderer.step(deltaNanos, _model, lineState, scrollWidth)
            if (changed) postInvalidateOnAnimation()

            val finishedNow = renderer.isFinished
            if (finishedNow && !lastReportedFinished) {
                MarqueeDiag.i(this@LyricLineView, "renderer_finished") {
                    "overflow=$isOverflow scrollWidth=$scrollWidth measuredWidth=$measuredWidth " +
                        "lineWidth=$lineWidth stopAtEnd=${scrollRenderer.stopAtEnd} " +
                        "repeat=${scrollRenderer.repeatCount}"
                }
            }
            lastReportedFinished = finishedNow

            if (running && renderer.isPlaying) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastFrameNanos = 0L
            }
        }
    }
}
