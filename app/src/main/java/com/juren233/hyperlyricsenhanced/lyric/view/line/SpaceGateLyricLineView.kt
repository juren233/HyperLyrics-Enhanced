/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_RIGHT_TEXT

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
import android.view.ViewGroup
import android.view.ViewParent
import androidx.core.graphics.withSave
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

open class SpaceGateLyricLineView(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), UpdatableColor, LyricTextPaintOwner, PositionUpdateConsumer {

    // Keep the requested length when switching between the belt and independent slots.
    private var configuredFadingEdgeLength = 10.dp
    private var fullIslandFadingEdges = SpaceGateFadingEdges.NONE

    init {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(configuredFadingEdgeLength)
    }

    // Space Gate synchronization settings
    var isRightSide = false
        set(value) {
            field = value
            updateRightPreviewMode()
        }
    var siblingView: SpaceGateLyricLineView? = null
    var spaceGateEnabled = true
        set(value) {
            if (field == value) return
            field = value
            updateRightPreviewMode()
            updateFadingEdges()
            if (value && !isRightSide) {
                // 全岛歌词只允许右侧 Master 驱动帧循环。
                animator.stop()
            } else if (playbackActive) {
                // 切到分离歌词后，原先的左侧 Slave 也要立即恢复独立进度。
                resumePlaybackAnimation()
            }
            invalidate()
        }

    /** 纯遮挡接缝布局；null 表示非拼接模式（无对端槽），条带连续绘制。 */
    private var cachedSeamLayout: SeamOcclusionLayout? = null
    private var seamLayoutKey: List<Any?>? = null

    /** 最近一次分发到渲染器的接缝（虚拟坐标）＝左槽宽；-1 表示未分发。 */
    private var distributedSeamX: Int = -1


    override val textPaint: TextPaint = TextPaintX().apply { textSize = 24f.sp }

    val model: LyricModel get() = _model
    private var _model: LyricModel = emptyLyricModel()

    private val interludeDotsRenderer = InterludeDotsRenderer()

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
    // 溢出按真实行宽加避让需求判定：静止行绕孔分段避让摄像头（用户拍板），
    // 右段平移后行尾会超出岛缘的行改走正常滚动。右对齐/居中行由方案内
    // 自行处理，不参与右移超尾判定。接缝布局未就绪时退化为纯行宽判定。
    val isOverflow: Boolean
        get() = hasRightPreview || getSpaceGateVirtualWidth().let { vw -> activeRenderer.layoutWidthFor(_model, vw) > vw }
    val isPlaying: Boolean get() = activeRenderer.isPlaying
    val isFinished: Boolean get() = activeRenderer.isFinished
    val isStarted: Boolean get() = activeRenderer.isStarted

    /** Read-only, Debug-only call site: correlate drawn text with the island viewport. */
    internal fun overlapDiagnosticState(): String =
        "text=${_model.text.length}/${_model.text.hashCode()} plain=$isPlainText " +
            "lineW=$lineWidth scrollW=$scrollWidth overflow=$isOverflow " +
            "offset=${lineState.scrollOffset} progress=${scrollRenderer.scrollProgress} " +
            "unlocked=$scrollUnlocked started=$scrollStarted renderer=$isStarted/$isPlaying/$isFinished " +
            "static=$isStaticPreview rightSide=$isRightSide gate=$spaceGateEnabled " +
            "align=$alignRight center=$centerIfPossible textX=${currentTextStartX()}"

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
            siblingView?.invalidate()
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
     * 动态长度二段测量的宽度下限，由 SpaceGateRichLyricLineView 在测量期下发：
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

    internal val lineState = LineState()
    internal val scrollRenderer = SpaceGateScrollTextRenderer()
    internal val syncRenderer = SpaceGateWordSyncRenderer(this)
    private val lineShadowRenderer = LineShadowRenderer()
    private val rightPreview = SpaceGateRightPreviewRenderer()
    private val hasRightPreview: Boolean
        get() = rightPreview.hasText && if (spaceGateEnabled) {
            cachedSeamLayout != null && distributedSeamX > 0
        } else isRightSide

    private fun updateRightPreviewMode() {
        val enabled = (spaceGateEnabled || isRightSide) && rightPreview.hasText
        scrollRenderer.nextLineOnRight = enabled
        syncRenderer.nextLineOnRight = enabled
    }

    override fun forEachDrawingTextPaint(action: (TextPaint) -> Unit) {
        // Host shadow parameters live only on textPaint. Word-sync color paints remain untouched.
        action(textPaint)
    }

    private val animator = Animator()

    private var baseTypeface: Typeface = Typeface.DEFAULT
    private var narrowTypeface: Typeface? = null

    private val currentTypefaceSelector: ((Char) -> Typeface)?
        get() = MixedTypefaceText.typefaceSelector(baseTypeface, narrowTypeface)

    internal var activeRenderer: LineRenderer = scrollRenderer

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
            siblingView?.invalidate()
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
     * 与 LyricModel.updateSizes 同管线测宽：混排窄体开启时英数走窄字体，
     * 朴素 measureText 会高估宽度（见 LyricLineView 同名方法）。供提升动画
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

    private fun resolveTextStartX(
        textWidth: Float,
        isAlignedRight: Boolean,
        centerIfPossibleOverride: Boolean? = null,
        alignRightOverride: Boolean? = null,
        availableWidthOverride: Float? = null
    ): Float {
        // availableWidthOverride 供提升动画按落定宽度（pendingHugWidth）预算横向
        // 目标；语义与 LineRenderer.resolvePlainTextOffset / LyricLineView.resolveTextStartX 一致。
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

    fun setTextSize(size: Float) {
        val needsUpdate = textPaint.textSize != size || syncRenderer.bgPaint.textSize != size
        if (!needsUpdate) return
        textPaint.textSize = size
        syncRenderer.setTextSize(size)
        refreshSizes()
        syncRenderer.updateLayout(_model, lineState, getSpaceGateVirtualWidth(), measuredHeight)
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
        rightPreview.bind(_model.metadata?.get(METADATA_NEXT_LINE_RIGHT_TEXT))
        updateRightPreviewMode()
        applyCurrentTypeface()
        activeRenderer = if (_model.isPlainText) scrollRenderer else syncRenderer
        if (BuildConfig.DEBUG && _model.text.isNotEmpty()) {
            HookLogger.d(
                "IslandScroll",
                "bind view=${Integer.toHexString(System.identityHashCode(this))} right=$isRightSide " +
                    "plain=${_model.isPlainText} width=${_model.width} " +
                    "len=${_model.text.length} hash=${_model.text.hashCode()}"
            )
        }
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

        configuredFadingEdgeLength = fadingEdge.coerceAtLeast(0)
        updateFadingEdges()

        refreshSizes()
        animator.stop()
        if (!isStaticPreview && playbackActive) animator.startIfNeeded()
        invalidate()
    }

    private fun updateFadingEdges() {
        setFadingEdgeLength(configuredFadingEdgeLength)
        isHorizontalFadingEdgeEnabled = configuredFadingEdgeLength > 0
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
    private var layoutRolePaused = false

    internal fun setLayoutRolePaused(paused: Boolean) {
        layoutRolePaused = paused
        if (paused) animator.stop() else resumePlaybackAnimation()
    }
    private val switchTrace = if (BuildConfig.DEBUG) LyricSwitchTrace(this) else null

    private fun traceSwitch(event: String, dumpHistory: Boolean = false) {
        if (!BuildConfig.DEBUG || !hugContentWidth || _model.text.isEmpty()) return
        if (spaceGateEnabled) return // Split rendering has a separate virtual canvas.
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
        if (contentSwitchPaused || layoutRolePaused || isStaticPreview) return
        if (isInterludeIndicator) {
            interludeDotsRenderer.updatePosition(posMs)
            invalidate()
            siblingView?.invalidate()
            return
        }
        if (!isRightSide && spaceGateEnabled) return // Slave view delegates animation to Master
        if (isPlainText) {
            if (playbackActive) startScrolling()
        } else {
            activeRenderer.seek(_model, lineState, posMs, getSpaceGateVirtualWidth(), measuredHeight)
            if (playbackActive) {
                animator.startIfNeeded()
            } else {
                animator.stop()
                invalidate()
                siblingView?.invalidate()
            }
        }
    }

    fun updatePosition(posMs: Long) {
        if (contentSwitchPaused || layoutRolePaused || isStaticPreview) return
        if (isInterludeIndicator) {
            interludeDotsRenderer.updatePosition(posMs)
            if (playbackActive) {
                postInvalidateOnAnimation()
                siblingView?.postInvalidateOnAnimation()
            } else {
                invalidate()
                siblingView?.invalidate()
            }
            return
        }
        if (!isRightSide && spaceGateEnabled) return // Slave view delegates animation to Master
        if (isWordSync) {
            if (syncRenderer.isScrollOnly && !isOverflow) return
            if (playbackActive) {
                activeRenderer.update(_model, lineState, posMs, getSpaceGateVirtualWidth(), measuredHeight)
                if (syncRenderer.isPlaying && !syncRenderer.isFinished) {
                    animator.startIfNeeded()
                }
            } else {
                activeRenderer.seek(_model, lineState, posMs, getSpaceGateVirtualWidth(), measuredHeight)
                animator.stop()
                invalidate()
                siblingView?.invalidate()
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
            (activeRenderer as? SpaceGateWordSyncRenderer)?.let { renderer ->
                renderer.freeze(_model, lineState, getSpaceGateVirtualWidth())
                if (isShown) invalidate()
                siblingView?.takeIf { it.isShown }?.invalidate()
            }
            return
        }

        resumePlaybackAnimation()
    }

    private fun resumePlaybackAnimation() {
        if (contentSwitchPaused || layoutRolePaused) {
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
        rightPreview.configure(textPaint, currentTypefaceSelector, backgroundColors, currentFontSignature())
    }

    fun relayout() {
        traceSwitch("before_relayout")
        ensureSeamLayout()
        if (isWordSync) syncRenderer.updateLayout(_model, lineState, getSpaceGateVirtualWidth(), measuredHeight)
        traceSwitch("after_relayout")
    }

    override fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        primaryColors = primary
        backgroundColors = background
        highlightColors = highlight

        updatePlainTextColors()
        syncRenderer.setColors(background, highlight)
        rightPreview.configure(textPaint, currentTypefaceSelector, backgroundColors, currentFontSignature())
        invalidate()
    }

    fun reset() {
        animator.stop()
        interludeDotsRenderer.reset()
        lineState.reset()
        scrollRenderer.reset(lineState)
        syncRenderer.reset(lineState)
        lineShadowRenderer.clear()
        rightPreview.bind(null)
        updateRightPreviewMode()
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
                "w=$w oldw=$oldw overflow=$isOverflow lineWidth=$lineWidth virtualWidth=${getSpaceGateVirtualWidth()}"
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

    /** 上一次可见性评估结果；null 表示尚未评估过。语义见 LyricLineView.evaluateShownRecovery。 */
    private var lastShownState: Boolean? = null

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

    /** 上一次宽度评估时的溢出状态基线，语义见 LyricLineView.lastWidthOverflow。 */
    private var lastWidthOverflow: Boolean? = null

    /**
     * See LyricLineView.onAvailableWidthChanged. 拼接模式下溢出判定用的是左右
     * 两个槽位的虚拟总宽：任一侧槽宽变化都会改变另一侧的溢出结论，所以触发时
     * 通知对侧视图重新评估（通知不再回传，避免互相递归）。
     */
    private fun onAvailableWidthChanged(notifySibling: Boolean = true) {
        if (notifySibling) {
            siblingView?.onAvailableWidthChanged(notifySibling = false)
        }
        if (!isRightSide && spaceGateEnabled) return // Slave delegates animation
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

    override fun draw(canvas: Canvas) {
        // View asks for fading strengths before onDraw copies the master's state.
        // Resolve once per draw from the live master, including band displacement,
        // then let View fade both the glyphs and their shadows at the physical edges.
        if (spaceGateEnabled) fullIslandFadingEdges = resolveFullIslandFadingEdges()
        super.draw(canvas)
    }

    private fun resolveFullIslandFadingEdges(): SpaceGateFadingEdges {
        if (configuredFadingEdgeLength <= 0) return SpaceGateFadingEdges.NONE
        val master = if (isRightSide) this else siblingView ?: return SpaceGateFadingEdges.NONE
        master.ensureSeamLayout()
        val vw = master.getSpaceGateVirtualWidth()
        val renderer = master.activeRenderer
        val model = master._model
        val renderedScrollWidth = renderer.layoutWidthFor(model, vw)
        if (renderedScrollWidth <= vw && !master.hasRightPreview) return SpaceGateFadingEdges.NONE
        val offset = master.lineState.scrollOffset
        val seam = if (isRightSide) siblingView?.scrollWidth else scrollWidth
        val edges = SpaceGateFadingEdges.resolve(
            textWidth = maxOf(model.width, master.cachedSeamLayout?.totalAdvance ?: 0f),
            scrollWidth = renderedScrollWidth,
            viewWidth = vw.toFloat(),
            seam = seam?.toFloat() ?: 0f,
            offset = offset,
            edgeLength = configuredFadingEdgeLength.toFloat(),
            plan = renderer.seamPlanFor(model, master.lineState, vw),
            ghostStart = resolveShadowGhostStartX(
                primaryStartX = offset,
                textWidth = renderedScrollWidth,
                viewWidth = vw.toFloat(),
                ghostSpacing = master.ghostSpacing,
                isPlainText = master.isPlainText && !master.hasRightPreview,
            ),
        )
        val previewStart = master.rightPreviewStart(vw) ?: return edges
        if (previewStart >= vw) return edges
        return edges.copy(outerRight = maxOf(edges.outerRight,
            ((previewStart + master.rightPreview.width - vw) / configuredFadingEdgeLength).coerceIn(0f, 1f)))
    }

    override fun onDraw(canvas: Canvas) {
        if (!spaceGateEnabled) {
            drawContent(canvas, scrollWidth)
            return
        }

        val master = if (isRightSide) this else siblingView
        if (master == null) {
            // 无对端时退回单槽渲染，接缝布局引用一并清掉，避免残留遮挡绘制。
            if (seamLayoutKey != null || distributedSeamX >= 0) clearSeamLayout()
            drawContent(canvas, scrollWidth)
            return
        }
        ensureSeamLayout()

        val sibling = siblingView
        val (leftView, rightView) = if (isRightSide) {
            Pair(sibling ?: this, this)
        } else {
            Pair(this, sibling ?: this)
        }

        val virtualWidth = leftView.width + rightView.width
        val translationX = if (isRightSide) -leftView.width.toFloat() else 0f

        // Slave View copies Master View's drawing offset and renderer progress state
        if (!isRightSide) {
            this.lineState.scrollOffset = master.lineState.scrollOffset
            this.lineState.isScrollFinished = master.lineState.isScrollFinished
            val myRenderer = this.activeRenderer
            val masterRenderer = master.activeRenderer
            if (myRenderer is SpaceGateWordSyncRenderer && masterRenderer is SpaceGateWordSyncRenderer) {
                myRenderer.syncFrom(masterRenderer)
            } else if (myRenderer is SpaceGateScrollTextRenderer && masterRenderer is SpaceGateScrollTextRenderer) {
                myRenderer.syncFrom(masterRenderer)
            }
        }

        canvas.withSave {
            translate(translationX, 0f)
            drawContent(canvas, virtualWidth)
        }

        // If we are Master, request Slave View to redraw in the next frame callback
        if (isRightSide) {
            sibling?.postInvalidateOnAnimation()
        }
    }

    /**
     * 计算并缓存纯遮挡接缝布局。布局只依赖文本与绘制参数（与槽宽/对齐
     * 无关，键因此更小）；接缝（左槽宽）单独探测并分发。左右两槽基于同
     * 一行内容与同一组输入独立计算，结果确定性一致；键命中即跳过。失效
     * 后同步清空两个渲染器的布局与接缝引用。
     */
    private fun ensureSeamLayout() {
        val sibling = siblingView
        if (!spaceGateEnabled || sibling == null) {
            if (seamLayoutKey != null || distributedSeamX >= 0) clearSeamLayout()
            return
        }
        val leftView = if (isRightSide) sibling else this
        fun laidOutWidth(view: SpaceGateLyricLineView): Int =
            view.width.takeIf { it > 0 } ?: view.measuredWidth
        val seam = laidOutWidth(leftView)

        val key = listOf<Any?>(
            _model.text,
            _model.wordText,
            _model.width.toBits(),
            textPaint.textSize.toBits(),
        )
        val layoutChanged = key != seamLayoutKey
        val seamChanged = seam != distributedSeamX
        if (layoutChanged) {
            seamLayoutKey = key
            cachedSeamLayout = buildSeamLayout()
            scrollRenderer.seamLayout = cachedSeamLayout
            syncRenderer.seamLayout = cachedSeamLayout
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    "IslandScroll",
                    "seamLayout view=${Integer.toHexString(System.identityHashCode(this))} right=$isRightSide " +
                        "seam=$seam textW=${_model.width} plain=${_model.isPlainText} " +
                        "built=${cachedSeamLayout != null}"
                )
            }
        }
        if (seamChanged) {
            distributedSeamX = seam
            scrollRenderer.seamX = seam.toFloat()
            syncRenderer.seamX = seam.toFloat()
        }
        // Both layout and seam must reach the renderer before capacity is evaluated.
        if (layoutChanged || seamChanged) onAvailableWidthChanged()
    }

    /** 与绘制同管线度量逐字符 advance 后构建单元表；退化输入返回 null（连续绘制）。 */
    private fun buildSeamLayout(model: LyricModel = _model): SeamOcclusionLayout? {
        val text = if (model.isPlainText) model.text else model.wordText
        if (text.isEmpty()) return null
        val selector = currentTypefaceSelector
        return if (model.isPlainText) {
            val widths = FloatArray(text.length)
            if (selector != null) {
                MixedTypefaceText.getTextWidths(textPaint, text, selector, widths)
            } else {
                textPaint.getTextWidths(text, widths)
            }
            SeamOcclusionLayout.build(text, widths)
        } else {
            // 逐字行：拼接各词的逐字 advance，与逐字绘制坐标系同源。
            val widths = FloatArray(text.length)
            var filled = 0
            for (word in model.words) {
                val wordLength = word.text.length
                if (filled + wordLength > widths.size) return null
                word.charWidths.copyInto(widths, filled)
                filled += wordLength
            }
            if (filled != widths.size) return null
            SeamOcclusionLayout.build(text, widths)
        }
    }

    /** Uses the same normalized model, font advances and seam layout as the first bound frame. */
    internal fun promotionSnapshot(
        incoming: LyricLine? = null,
        center: Boolean = centerIfPossible,
        right: Boolean = alignRight,
        includeIndependent: Boolean = false,
    ): SpaceGatePromotionSnapshot? {
        val sibling = siblingView ?: return null
        if (!spaceGateEnabled && !includeIndependent) return null
        val model = if (incoming != null) {
            incoming.normalize().createModel().apply { updateSizes(textPaint, currentTypefaceSelector) }
        } else _model
        val units = buildSeamLayout(model) ?: return null
        val master = if (!spaceGateEnabled || isRightSide) this else sibling
        val seam = (if (isRightSide) sibling else this).scrollWidth.toFloat()
        val width = (scrollWidth + sibling.scrollWidth).toFloat()
        if (seam <= 0f || seam >= width) return null
        val endpoint = SpaceGatePromotionGeometry.endpoint(
            units, model.width, if (spaceGateEnabled) width else scrollWidth.toFloat(),
            if (spaceGateEnabled) seam else 0f, model.isAlignedRight, center, right,
            scrollOffset = if (incoming == null) master.lineState.scrollOffset else 0f,
            nextLineOnRight = !model.metadata?.get(METADATA_NEXT_LINE_RIGHT_TEXT).isNullOrBlank(),
        )
        val fm = textPaint.fontMetrics
        return SpaceGatePromotionSnapshot(
            text = if (model.isPlainText) model.text else model.wordText,
            geometry = if (spaceGateEnabled) endpoint else
                SpaceGatePromotionGeometry.inIsland(endpoint, seam, width - seam, isRightSide),
            // The moving line has not landed in the progress renderer yet. Both endpoints use
            // the unplayed palette; never brighten the preview towards the primary text alpha.
            paint = TextPaint(textPaint).apply {
                color = backgroundColors.firstOrNull() ?: syncRenderer.bgPaint.color
                shader = if (backgroundColors.size > 1) {
                    LinearGradient(0f, 0f, model.width.coerceAtLeast(1f), 0f,
                        backgroundColors, null, Shader.TileMode.CLAMP)
                } else null
            },
            typefaceSelector = currentTypefaceSelector,
            baseline = (measuredHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent,
            fadingEdgeLength = configuredFadingEdgeLength.toFloat(),
            fontSignature = currentFontSignature(),
        )
    }

    private fun clearSeamLayout() {
        seamLayoutKey = null
        cachedSeamLayout = null
        distributedSeamX = -1
        scrollRenderer.seamLayout = null
        syncRenderer.seamLayout = null
        scrollRenderer.seamX = 0f
        syncRenderer.seamX = 0f
    }

    private fun drawContent(canvas: Canvas, availableWidth: Int) {
        if (interludeDotsRenderer.isIndicator(_model)) {
            interludeDotsRenderer.draw(
                canvas,
                _model,
                textPaint,
                availableWidth,
                measuredHeight,
                centerIfPossible,
                alignRight
            )
            if (playbackActive && !isStaticPreview && isShown) {
                postInvalidateOnAnimation()
                siblingView?.postInvalidateOnAnimation()
            }
        } else {
            drawShadowAndContent(canvas, availableWidth)
        }
    }

    private fun drawShadowAndContent(canvas: Canvas, availableWidth: Int) {
        traceSwitch("draw")
        // 接缝方案由活动渲染器按当前状态求值（静止绕孔分段/滚动边缘滑过渐
        // 隐），正文在 renderer.draw 内部以同一纯函数重算，两处同输入同结果。
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
            seamPlan = activeRenderer.seamPlanFor(_model, lineState, availableWidth),
            seamLayout = cachedSeamLayout,
            layoutWidth = activeRenderer.layoutWidthFor(_model, availableWidth),
            drawGhost = !hasRightPreview,
        )
        textPaint.withoutShadowLayer {
            activeRenderer.draw(canvas, _model, textPaint, lineState, availableWidth, measuredHeight)
        }
        rightPreviewStart(availableWidth)?.let { start ->
            rightPreview.draw(canvas, start, previewSeam(), availableWidth, measuredHeight, textPaint)
        }
    }

    private fun previewSeam(): Float = if (spaceGateEnabled) distributedSeamX.toFloat() else 0f

    private fun rightPreviewStart(availableWidth: Int): Float? {
        val seam = previewSeam()
        if (!hasRightPreview || seam >= availableWidth) return null
        val layout = SpaceGateLineLayout(_model.width, availableWidth.toFloat(), if (spaceGateEnabled) cachedSeamLayout else null,
            seam, _model.isAlignedRight, centerIfPossible, alignRight,
            nextLineOnRight = true)
        val end = maxOf(_model.width, cachedSeamLayout?.totalAdvance ?: 0f)
        val offset = lineState.scrollOffset
        val drawnEnd = layout.textOrigin(offset) + end + (layout.plan(offset)?.shiftAt(end) ?: 0f)
        val reveal = if (end > availableWidth) 1f else if (isWordSync) {
            syncRenderer.progressAnimator.currentWidth / end.coerceAtLeast(1f)
        } else {
            scrollRenderer.currentUnitOffset / ((availableWidth - seam) / 2f).coerceAtLeast(1f)
        }
        return SpaceGateRightPreviewGeometry.previewStart(drawnEnd, availableWidth.toFloat(),
            seam, textSize, reveal)
    }

    internal fun rightPreviewSnapshot(expectedText: String?): SpaceGatePromotionSnapshot? {
        if (expectedText.isNullOrBlank() || rightPreview.text != expectedText) return null
        val sibling = siblingView ?: return null
        val width = getSpaceGateVirtualWidth()
        val start = rightPreviewStart(width) ?: return null
        val seam = (if (isRightSide) sibling else this).scrollWidth.toFloat()
        val islandWidth = (scrollWidth + sibling.scrollWidth).toFloat()
        if (seam <= 0f || seam >= islandWidth) return null
        return rightPreview.snapshot(
            start + if (!spaceGateEnabled && isRightSide) seam else 0f,
            seam, islandWidth, measuredHeight, configuredFadingEdgeLength.toFloat(),
        )
    }

    internal fun currentFontSignature(): Int =
        31 * System.identityHashCode(baseTypeface) + System.identityHashCode(narrowTypeface)

    private fun findGateRoot(view: View): View? {
        var current: ViewParent? = view.parent
        while (current != null && current.javaClass.simpleName != "DynamicIslandContentView") {
            current = current.parent
        }
        return current as? View
    }

    /**
     * 跑马灯判定与滚动使用的可见宽度：布局宽度优先，语义见 LyricLineView.scrollWidth。
     */
    val scrollWidth: Int
        get() = width.takeIf { it > 0 } ?: measuredWidth

    private fun getSpaceGateVirtualWidth(): Int {
        if (!spaceGateEnabled) return scrollWidth
        val master = if (isRightSide) this else siblingView ?: return scrollWidth

        val sibling = siblingView
        val (leftView, rightView) = if (isRightSide) {
            Pair(sibling ?: this, this)
        } else {
            Pair(this, sibling ?: this)
        }

        // 两侧都按布局宽度取值（未布局时回退测量宽度），避免瞬态测量把
        // 虚拟总宽撑大、令溢出判定失效。
        fun laidOutWidth(view: SpaceGateLyricLineView): Int =
            view.width.takeIf { it > 0 } ?: view.measuredWidth

        val virtualWidth = laidOutWidth(leftView) + laidOutWidth(rightView)
        return maxOf(scrollWidth, virtualWidth)
    }

    override fun getLeftFadingEdgeStrength(): Float {
        if (spaceGateEnabled) {
            return if (isRightSide) fullIslandFadingEdges.camera else fullIslandFadingEdges.outerLeft
        }
        val vw = getSpaceGateVirtualWidth()
        if (hasRightPreview && horizontalFadingEdgeLength > 0) {
            return (-lineState.scrollOffset / horizontalFadingEdgeLength).coerceIn(0f, 1f)
        }
        if (lineWidth <= vw || horizontalFadingEdgeLength <= 0) return 0f
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
        if (spaceGateEnabled) {
            return if (isRightSide) fullIslandFadingEdges.outerRight else fullIslandFadingEdges.camera
        }
        val vw = getSpaceGateVirtualWidth()
        if (hasRightPreview && horizontalFadingEdgeLength > 0) {
            val previewStart = rightPreviewStart(vw)
            val previewEnd = if (previewStart != null && previewStart < vw) previewStart + rightPreview.width else 0f
            val end = maxOf(lineWidth + lineState.scrollOffset, previewEnd)
            return ((end - vw) / horizontalFadingEdgeLength).coerceIn(0f, 1f)
        }
        if (lineWidth <= vw || horizontalFadingEdgeLength <= 0) return 0f
        val viewW = vw.toFloat()
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

    override val needsFrequentPositionUpdates: Boolean
        get() = isAttachedToWindow && isShown && playbackActive &&
            !isStaticPreview && !contentSwitchPaused && !layoutRolePaused && isWordSync &&
            !syncRenderer.isFinished && (isRightSide || !spaceGateEnabled) &&
            !(syncRenderer.isScrollOnly && !isOverflow)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PositionUpdateDemand.active.attach(this)
    }

    override fun onDetachedFromWindow() {
        PositionUpdateDemand.active.detach(this)
        super.onDetachedFromWindow()
        MarqueeDiag.i(this, "detached") {
            "reset 会清空 unlocked/started/宽度基线：overflow=$isOverflow"
        }
        reset()
    }

    private fun startScrolling() {
        if (layoutRolePaused) return
        if (!isRightSide && spaceGateEnabled) return // Slave delegates animation
        // See LyricLineView.startScrolling: the overflow test must gate the latch, otherwise
        // the marquee is permanently unavailable when the first request ran before the final
        // dynamic width existed.
        val canStart = MarqueeStartPolicy.canStart(
            playbackActive = playbackActive,
            isStaticPreview = isStaticPreview,
            isPlainText = isPlainText,
            scrollUnlocked = scrollUnlocked,
            scrollStarted = scrollStarted,
            isOverflow = isOverflow,
        )
        MarqueeDiag.d(this, if (canStart) "start_latch" else "start_skip") {
            "overflow=$isOverflow lineWidth=$lineWidth virtualWidth=${getSpaceGateVirtualWidth()} " +
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
            scrollRenderer.update(_model, lineState, 0, getSpaceGateVirtualWidth(), measuredHeight)
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
            if (layoutRolePaused) return
            if (!isRightSide && spaceGateEnabled) return // Slave doesn't run frame callback
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
            if (!running || !playbackActive || layoutRolePaused || !isAttachedToWindow || !isShown) {
                running = false
                return
            }

            ensureSeamLayout()
            val virtualWidth = getSpaceGateVirtualWidth()
            val deltaNanos = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
            lastFrameNanos = frameTimeNanos

            val renderer = activeRenderer
            val changed = renderer.step(deltaNanos, _model, lineState, virtualWidth)
            if (changed) {
                postInvalidateOnAnimation()
                siblingView?.postInvalidateOnAnimation()
            }

            val finishedNow = renderer.isFinished
            if (finishedNow && !lastReportedFinished) {
                MarqueeDiag.i(this@SpaceGateLyricLineView, "renderer_finished") {
                    "overflow=$isOverflow virtualWidth=$virtualWidth lineWidth=$lineWidth " +
                        "stopAtEnd=${scrollRenderer.stopAtEnd} repeat=${scrollRenderer.repeatCount}"
                }
            }
            lastReportedFinished = finishedNow

            // 词间空转保活：逐字行一词一目标，词内由帧钟驱动平滑推进；词斜坡
            // 到底时 isPlaying 短暂为 false，若此刻停帧循环，下一词要等位置
            // tick＋post 跳板＋重启帧零增量才恢复——每个词边界一次可感停顿
            // （真机「歌词进度掉帧」）。行未唱完且仍会收到动画目标期间保持
            // 帧回调存活；空转帧 step 返回 false，不触发重绘。滚动模式非溢出
            // 行永无动画目标，不保活。
            val keepAlive = renderer.isPlaying || (
                playbackActive && renderer === syncRenderer &&
                    !renderer.isFinished &&
                    !(syncRenderer.isScrollOnly && !isOverflow)
                )
            if (running && keepAlive) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastFrameNanos = 0L
            }
        }
    }
}
