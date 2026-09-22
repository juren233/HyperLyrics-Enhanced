/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.withScale
import androidx.core.view.forEach
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGateLyricLineView
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

@SuppressLint("ViewConstructor")
class SpaceGateRichLyricLineView(
    context: Context,
    var displayTranslation: Boolean = true,
    var enableRelativeProgress: Boolean = false,
    var enableRelativeProgressHighlight: Boolean = false,
    var displayRoma: Boolean = true
) : LinearLayout(context), UpdatableColor {

    val main = SpaceGateLyricLineView(context)
    val secondary = SpaceGateLyricLineView(context).apply { visibleIfChanged = false }

    var alwaysShowSecondary = false

    /**
     * 动态长度模式：主/次行测量宽度收缩为文字实际宽度，见 [SpaceGateLyricLineView.hugContentWidth]。
     */
    var hugContentWidth: Boolean
        get() = main.hugContentWidth
        set(value) {
            main.hugContentWidth = value
            secondary.hugContentWidth = value
        }

    /**
     * 换行内容因换句/预览提升动画被延迟落地后的回调（主线程）。
     * 动态长度模式下岛宽在内容应用返回时立即测量，延迟落地时需借此触发第二次
     * 岛宽重算，否则岛宽恒定落后一行；回调为 null 时无任何额外行为。
     */
    var onDeferredContentApplied: (() -> Unit)? = null

    /** 动态长度的下一行宽度预测；长句上浮时用于提前给岛留位。 */
    private var pendingHugWidth: Int? = null
    /** 飞行期间子行始终按起跳帧宽度绘制，避免重新布局造成横跳。 */
    private var promotionFlightWidth: Int? = null
    /** 长句上浮时只给外层岛预留宽度，子行仍按起跳宽度绘制。 */
    private var promotionReserveWidth: Int? = null
    /** 旧宽子行在预留宽度内的放置比例：左=0、居中=0.5、右=1。 */
    private var promotionReserveOffsetFactor: Float? = null
    /** 预留生效时按落定宽度算出的主行起笔位；落地帧与重排后的稳态共用同一锚点。 */
    private var promotionLandingStartX: Float? = null
    /** 落地后到首次正式重测前，抵消旧宽子行在预留宽度中的临时放置偏移。 */
    private var promotionLandingOffset: Float? = null

    internal fun beginDeferredContentWidth(targetLine: IRichLyricLine?) {
        pendingHugWidth = targetLine?.let(::predictAppliedContentWidth)
    }

    /**
     * 换句切换过渡（淡出开始 → 新内容落地前）的组宽冻结。
     * 动态长度下，淡出期间任何宽度变化都会把“视图宽度 − 文字宽度”的
     * 对唱位置提前应用到仍在上屏的旧句（旧句先移到另一侧再换字）。
     * 冻结让旧句位置保持到内容落地；line 写入即清除，下一行的位置
     * 随其内容一起生效。
     */
    private var contentSwitchFreezeWidth: Int? = null

    internal fun beginContentSwitchFreeze() {
        if (!main.hugContentWidth) return
        val current = width.takeIf { it > 0 } ?: measuredWidth
        if (current <= 0) return
        contentSwitchFreezeWidth = current
        // 布局冻结之外同步暂停行的滚动/逐字步进，旧句在淡出期间像素级静止。
        main.pauseForContentSwitch()
        secondary.pauseForContentSwitch()
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                "SwitchTrace",
                "freeze begin view=${System.identityHashCode(this).toString(16)} width=$current"
            )
        }
    }

    private fun predictAppliedContentWidth(targetLine: IRichLyricLine): Int {
        val mainResult = assembler.buildMain(targetLine)
        val secResult = assembler.buildSecondary(targetLine)
        val mainWidth = main.measureIncomingHugWidth(mainResult.line)
        val secondaryWidth = if (secResult.alwaysShow) {
            secondary.measureIncomingHugWidth(secResult.line)
        } else {
            0
        }
        return resolveMeasureFloor(maxOf(mainWidth, secondaryWidth))
    }

    /**
     * 对唱固定长度：当前歌曲（含对唱行）的全曲最长行 hug 宽度，由主行绘制
     * 管线实测。二段测量把主/次行都抬到该下限，恢复“视图宽度 − 文字宽度”
     * 的原有对唱位置；null 表示未启用或当前歌曲无对唱行，不抬宽。
     */
    private var duetFixedLengthWidth: Int? = null
    private var duetWidthCap: Int? = null
    private var duetWidthCacheKey: DuetWidthCacheKey? = null
    private var duetWidthCacheValue: Int = 0

    /**
     * 动态长度 + 对唱固定长度开关都开启时，由内容装配层传入当前歌曲歌词与
     * 所在 wrapper 的内容宽度上限；未启用开关、关闭动态长度或歌曲不含对唱行
     * 时传 null 歌词。上限截断防止瞬态探测测量把超上限宽度抬进岛宽计算。
     */
    fun applyDuetFixedLength(lyrics: List<RichLyricLine>?, maxWidthPx: Int?) {
        duetWidthCap = maxWidthPx
        val next = when {
            lyrics == null || lyrics.none { it.isAlignedRight } -> null
            else -> measureSongMaxHugWidth(lyrics)
        }
        if (next != duetFixedLengthWidth) {
            duetFixedLengthWidth = next
            requestLayout()
        }
    }

    /**
     * 二段测量下限：组内最宽行 / 对唱全曲最长行，再按内容宽度上限截断。
     * 下限让短行保留“视图宽度 − 文字宽度”的方向偏移空间；上限保证瞬态
     * 展开几何测量不会报告超过真实内容上限的宽度。
     */
    private fun resolveMeasureFloor(groupWidth: Int): Int {
        val floor = maxOf(groupWidth, duetFixedLengthWidth ?: 0)
        // 注意：不能写成 duetWidthCap?.let(::minOf)——可变参数重载 minOf(Int, vararg Int)
        // 会让单参引用适配成"只取 cap"，floor 完全不参与，floor 恒等于 cap。
        val cap = duetWidthCap ?: return floor
        return minOf(floor, cap)
    }

    private fun measureSongMaxHugWidth(lyrics: List<RichLyricLine>): Int {
        val key = DuetWidthCacheKey(
            lyricsIdentity = System.identityHashCode(lyrics),
            lineCount = lyrics.size,
            firstText = lyrics.firstOrNull()?.text,
            textSize = main.textSize,
            fontSignature = main.currentFontSignature()
        )
        if (key == duetWidthCacheKey) return duetWidthCacheValue
        var max = 0
        for (line in lyrics) {
            val width = main.measureIncomingHugWidth(assembler.buildMain(line).line)
            if (width > max) max = width
        }
        duetWidthCacheKey = key
        duetWidthCacheValue = max
        return max
    }

    var renderScale = 1.0f
        private set

    var displayMode: Int = RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_DISPLAY
    var fallback: Boolean = RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_FALLBACK
    var hideSecondaryContent: Boolean = false

    private val assembler = LyricLineAssembler(
        displayMode, fallback, hideSecondaryContent,
        enableRelativeProgress, enableRelativeProgressHighlight
    )

    private var animationTransition = false
    private var pendingLine: IRichLyricLine? = null
    private var pendingPosition: Long? = null
    private var requestMarquee = false
    private var lastPosition: Long = Long.MIN_VALUE

    var rawLine: IRichLyricLine? = null

    /**
     * 当前已绑定行的对唱方向（null=无内容）。注入层在内容落地时读取它决定
     * wrapper/原生模块的 END 锚点；读已绑定行而非目标行，保证淡出窗口内
     * 旧句方向不被提前改写（160164 契约）。
     */
    val currentLineDuetAlignedRight: Boolean?
        get() = rawLine?.isAlignedRight
    private var currentMainText: String? = null
    private var secondaryIsNextLinePreview = false
    private var nextLineTransitionRunning = false
    private var nextLineTransitionGeneration = 0
    private var nextLineWatchdog: Runnable? = null
    private var centerMainLine: Boolean? = null
    private var centerSecondaryLine: Boolean? = null
    // 预览提升窗口的对齐暂存：提升动画期间旧句仍在上屏，视图级居中/靠右
    // 标志必须等提升落地（finishNextLinePromotion）再套用，否则旧句会先按
    // 下一句的方向重渲染（合唱居中句先变靠左再换字）。
    private var stagedPromotionCentering: Pair<Boolean, Boolean>? = null
    private var stagedPromotionRightAlign: Pair<Boolean, Boolean>? = null

    internal fun willAnimateNextLinePromotion(
        targetLine: IRichLyricLine?,
        previousLine: IRichLyricLine? = rawLine
    ): Boolean {
        val nextMainText = assembler.buildMain(targetLine).line.text
        return canAnimateNextLinePromotion(
            wasPreview = secondaryIsNextLinePreview,
            currentMainText = currentMainText,
            previewText = secondary.model.text,
            nextMainText = nextMainText,
            lineAdvanced = hasLyricLineAdvanced(previousLine, targetLine),
            attached = isAttachedToWindow,
            mainHeight = main.height,
            secondaryHeight = secondary.height
        )
    }

    var line: IRichLyricLine?
        get() = rawLine
        set(value) {
            if (shouldFinishRunningPromotionBeforeApplying(nextLineTransitionRunning, rawLine, value)) {
                if (BuildConfig.DEBUG) {
                    HookLogger.d(
                        "SwitchTrace",
                        "promotion preempt view=${System.identityHashCode(this).toString(16)} " +
                            "promoted=${rawLine?.begin}-${rawLine?.end} incoming=${value?.begin}-${value?.end}"
                    )
                }
                finishNextLinePromotion(revealNextPreview = false)
            }
            rawLine = value
            val clearedFreeze = contentSwitchFreezeWidth
            contentSwitchFreezeWidth = null
            if (clearedFreeze != null) {
                main.resumeFromContentSwitch()
                secondary.resumeFromContentSwitch()
            }
            if (BuildConfig.DEBUG && value != null && main.hugContentWidth) {
                val mainLine = assembler.buildMain(value).line
                HookLogger.d(
                    "SwitchTrace",
                    "apply view=${System.identityHashCode(this).toString(16)} " +
                        "next=\"${mainLine.text?.take(12)}\" nextRight=${mainLine.isAlignedRight} " +
                        "freezeCleared=$clearedFreeze"
                )
            }
            lastPosition = Long.MIN_VALUE
            requestMarquee = false
            if (animationTransition) {
                pendingLine = value
            } else {
                refreshLines()
            }
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        addView(main, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(secondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        updateLayoutTransitionX()
    }

    fun setSpaceGateConfig(isRightSide: Boolean, sibling: SpaceGateRichLyricLineView?) {
        main.isRightSide = isRightSide
        main.siblingView = sibling?.main
        secondary.isRightSide = isRightSide
        secondary.siblingView = sibling?.secondary
    }

    fun reset() {
        cancelNextLinePromotion()
        line = null
        renderScale = 1.0f
        animationTransition = false
        pendingLine = null
        pendingPosition = null
        pendingHugWidth = null
        contentSwitchFreezeWidth = null
        main.resumeFromContentSwitch()
        secondary.resumeFromContentSwitch()
        lastPosition = Long.MIN_VALUE
        currentMainText = null
        secondaryIsNextLinePreview = false
        alwaysShowSecondary = false
        duetFixedLengthWidth = null
        refreshLines()
    }

    fun beginAnimationTransition() {
        cancelNextLinePromotion()
        animationTransition = true
    }

    fun endAnimationTransition() {
        animationTransition = false
        if (pendingLine != null) {
            pendingHugWidth = null
            refreshLines()
            pendingPosition?.let { setPosition(it) }
            onDeferredContentApplied?.invoke()
        }
        pendingLine = null
        pendingPosition = null
    }

    fun setTransitionConfig(config: String?) {
        updateLayoutTransitionX(config)
    }

    fun notifyLineChanged() = refreshLines()

    fun setSecondaryTextUnitProgress(enabled: Boolean) {
        assembler.setSecondaryTextUnitProgress(enabled)
    }

    fun setDisplayOptions(
        displayMode: Int,
        fallback: Boolean,
        hideSecondaryContent: Boolean = false
    ) {
        this.displayMode = displayMode
        this.fallback = fallback
        this.hideSecondaryContent = hideSecondaryContent
        this.displayTranslation = displayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
        this.displayRoma = displayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
        assembler.updateFlags(
            displayMode,
            fallback,
            hideSecondaryContent,
            enableRelativeProgress,
            enableRelativeProgressHighlight
        )
    }

    fun setDisplayOptions(showTranslation: Boolean, showRoma: Boolean) {
        val mode = when {
            showTranslation -> RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
            showRoma -> RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            else -> RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
        }
        setDisplayOptions(
            displayMode = mode,
            fallback = false,
            hideSecondaryContent = mode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
        )
    }

    fun seekTo(position: Long) {
        if (animationTransition) {
            pendingPosition = position; return
        }
        if (nextLineTransitionRunning) {
            cancelNextLinePromotion()
            pendingHugWidth = null
            refreshLines(allowNextLinePromotion = false, bypassIdentityCheck = true)
            onDeferredContentApplied?.invoke()
        }
        lastPosition = position
        main.seekTo(position)
        secondary.seekTo(position)
    }

    fun setPosition(position: Long) {
        if (animationTransition) {
            pendingPosition = position; return
        }
        if (lastPosition == position) return
        lastPosition = position
        main.updatePosition(position)
        secondary.updatePosition(position)
    }

    fun setPlaybackActive(active: Boolean) {
        main.setPlaybackActive(active)
        secondary.setPlaybackActive(active)
    }

    fun requestStartMarquee() {
        requestMarquee = true
        main.requestScroll()
        if (!secondaryIsNextLinePreview) secondary.requestScroll()
    }

    fun setMetadataMarqueeConfig(
        speed: Float, initialDelay: Int, loopDelay: Int,
        repeatCount: Int, stopAtEnd: Boolean
    ) {
        listOf(main, secondary).forEach {
            it.setMarqueeSpeed(speed)
            it.setMarqueeInitialDelay(initialDelay)
            it.setMarqueeLoopDelay(loopDelay)
            it.setMarqueeRepeatCount(repeatCount)
            it.setMarqueeStopAtEnd(stopAtEnd)
        }
    }

    fun setStyle(style: LyricViewStyle) {
        assembler.updateFlags(
            displayMode, fallback, hideSecondaryContent,
            style.primary.relativeProgress, style.primary.relativeHighlight
        )
        enableRelativeProgress = style.primary.relativeProgress
        enableRelativeProgressHighlight = style.primary.relativeHighlight

        setTransitionConfig(style.transitionConfig)

        applyLineStyle(
            main,
            style.primary,
            style.highlight,
            style.marquee,
            style.gradient,
            style.fadingEdge,
            style.wordMotion,
            style.centerIfPossible
        )
        applyLineStyle(
            secondary,
            style.secondary,
            style.highlight,
            style.marquee,
            style.gradient,
            style.fadingEdge,
            style.wordMotion,
            style.centerIfPossible
        )
        applyLineCentering()
        setLineAlignmentRight(style.alignRight)
    }

    fun setLineCentering(centerMain: Boolean, centerSecondary: Boolean = centerMain) {
        centerMainLine = centerMain
        centerSecondaryLine = centerSecondary
        applyLineCentering()
    }

    fun setLineAlignmentRight(
        alignMainRight: Boolean,
        alignSecondaryRight: Boolean = alignMainRight
    ) {
        main.alignRight = alignMainRight
        secondary.alignRight = alignSecondaryRight
    }

    private fun applyLineCentering() {
        centerMainLine?.let { main.centerIfPossible = it }
        centerSecondaryLine?.let { secondary.centerIfPossible = it }
    }

    internal fun stagePromotionLandingAlignment(
        centerMain: Boolean,
        centerSecondary: Boolean,
        alignMainRight: Boolean,
        alignSecondaryRight: Boolean
    ) {
        stagedPromotionCentering = centerMain to centerSecondary
        stagedPromotionRightAlign = alignMainRight to alignSecondaryRight
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                "SwitchTrace",
                "stage promotion alignment view=${System.identityHashCode(this).toString(16)} " +
                    "centerMain=$centerMain alignMainRight=$alignMainRight"
            )
        }
    }

    internal val isNextLinePromotionRunning: Boolean
        get() = nextLineTransitionRunning

    /** 提升未实际起跑时立即消费暂存对齐；运行中则交给落地回调。 */
    internal fun settlePromotionLandingAlignment() {
        if (nextLineTransitionRunning) return
        applyStagedPromotionAlignment()
    }

    private fun applyStagedPromotionAlignment() {
        stagedPromotionCentering?.let { (mainCenter, secondaryCenter) ->
            setLineCentering(mainCenter, secondaryCenter)
        }
        stagedPromotionRightAlign?.let { (mainRight, secondaryRight) ->
            setLineAlignmentRight(mainRight, secondaryRight)
        }
        stagedPromotionCentering = null
        stagedPromotionRightAlign = null
    }

    override fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        forEach { if (it is UpdatableColor) it.updateColor(primary, background, highlight) }
    }

    fun setMainLyricPlayListener(listener: LyricPlayListener?) {
        main.playListener = listener
    }

    fun setSecondaryLyricPlayListener(listener: LyricPlayListener?) {
        secondary.playListener = listener
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        promotionFlightWidth?.let { frozen ->
            prepareHugMeasurePass(frozen)
            super.onMeasure(MeasureSpec.makeMeasureSpec(frozen, MeasureSpec.EXACTLY), hSpec)
            promotionReserveWidth?.let { desired ->
                val available = MeasureSpec.getSize(wSpec)
                val reserved = if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED ||
                    LyricHugMeasureWindow.reportIntrinsicWidth
                ) desired else minOf(desired, maxOf(available, frozen))
                if (reserved > frozen) setMeasuredDimension(reserved, measuredHeight)
            }
            return
        }
        val pending = pendingHugWidth
        if (pending != null && !nextLineTransitionRunning) {
            // 预览提升动画期间：按预判的落定内容宽度参与岛宽测量，
            // 受当前可用宽度约束，与 hug 实测的 spec 收敛行为一致
            val target = pending.coerceAtMost(MeasureSpec.getSize(wSpec))
            super.onMeasure(MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY), hSpec)
            return
        }
        contentSwitchFreezeWidth?.let { frozen ->
            // 切换过渡窗口：锁定当前组宽，旧句的对唱位置不随测量变化；
            // 新内容落地时（line 写入）清除冻结，宽度恢复随内容实测。
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    "SwitchTrace",
                    "frozen measure view=${System.identityHashCode(this).toString(16)} " +
                        "frozen=$frozen spec=${MeasureSpec.getSize(wSpec)}"
                )
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(frozen, MeasureSpec.EXACTLY), hSpec)
            return
        }
        if (renderScale != 1.0f && renderScale > 0) {
            val origW = MeasureSpec.getSize(wSpec)
            val mode = MeasureSpec.getMode(wSpec)
            val compW = (origW / renderScale).toInt()
            super.onMeasure(MeasureSpec.makeMeasureSpec(compW, mode), hSpec)
            setMeasuredDimension(origW, measuredHeight)
        } else if (main.hugContentWidth) {
            // 一段前先清掉上一轮下限：组宽必须由当前内容固有宽度决定，
            // 否则宽行留下的下限会棘轮式抬高后续窄行。
            prepareHugMeasurePass(null)
            // 一段：各行按自身文字 hug，得到组内最宽行。
            super.onMeasure(wSpec, hSpec)
            // 二段：以"组内最宽行 / 对唱全曲最长行"为下限重测主/次行，
            // 短于下限的行保留“视图宽度 − 文字宽度”的方向偏移空间
            //（对唱换边、第二行翻译/伴唱/下一句预览定位）。
            val floor = resolveMeasureFloor(measuredWidth)
            if (floor > 0) {
                prepareHugMeasurePass(floor)
                super.onMeasure(wSpec, hSpec)
            }
        } else {
            super.onMeasure(wSpec, hSpec)
        }
    }

    /** Keep the second hug pass from reusing the first pass's cached child measurements. */
    private fun prepareHugMeasurePass(floor: Int?) {
        main.hugWidthFloor = floor
        secondary.hugWidthFloor = floor
        main.forceLayout()
        secondary.forceLayout()
    }

    private var lastLayoutWidth = -1

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val frozen = promotionFlightWidth
        if (frozen != null && promotionReserveWidth != null && width > frozen) {
            val childOffset = ((width - frozen) * (promotionReserveOffsetFactor ?: 0f)).toInt()
            main.offsetLeftAndRight(childOffset)
            secondary.offsetLeftAndRight(childOffset)
        }
        if (promotionLandingOffset != null && frozen == null) {
            promotionLandingOffset = null
            main.translationX = 0f
            clipChildren = true
        }
        // 切换时序诊断：任何真实几何变化（自身宽/在父层中的位置/父层与祖父层
        // 的位置宽度）都记录，用于区分"自身测量宽变化"与"父层/胶囊搬动"。
        if (!BuildConfig.DEBUG || !changed || !main.hugContentWidth) return
        val width = right - left
        val parentView = parent as? View
        val grandView = parentView?.parent as? View
        HookLogger.d(
            "SwitchTrace",
            "layout view=${System.identityHashCode(this).toString(16)} x=$left w=$width" +
                "(old=$lastLayoutWidth) " +
                "parent=${parentView?.javaClass?.simpleName}(x=${parentView?.left},w=${parentView?.width}) " +
                "grand=${grandView?.javaClass?.simpleName}(x=${grandView?.left},w=${grandView?.width})"
        )
        lastLayoutWidth = width
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (renderScale != 1.0f) {
            canvas.withScale(renderScale, renderScale, 0f, height / 2f) {
                super.dispatchDraw(this)
            }
        } else {
            super.dispatchDraw(canvas)
        }
    }

    fun setRenderScale(scale: Float) {
        if (renderScale != scale) {
            renderScale = scale
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    private var oldLine: IRichLyricLine? = null

    private fun refreshLines(allowNextLinePromotion: Boolean = true, bypassIdentityCheck: Boolean = false) {
        if (nextLineTransitionRunning) return
        if (!bypassIdentityCheck && oldLine === line && line.isTitleLine()) return
        val previousLine = oldLine
        oldLine = line

        assembler.updateFlags(
            displayMode,
            fallback,
            hideSecondaryContent,
            enableRelativeProgress,
            enableRelativeProgressHighlight
        )
        val mainResult = assembler.buildMain(line)
        val secResult = assembler.buildSecondary(line)

        val shouldPromote = allowNextLinePromotion &&
            willAnimateNextLinePromotion(line, previousLine)
        if (shouldPromote) {
            animateNextLinePromotion(mainResult.line.text, mainResult.line.isAlignedRight)
            return
        }

        main.setLyric(mainResult.line)
        main.isScrollOnly = mainResult.isScrollOnly
        currentMainText = mainResult.line.text

        alwaysShowSecondary = secResult.alwaysShow
        secondaryIsNextLinePreview = secResult.isNextLinePreview
        secondary.visibleIfChanged = secResult.alwaysShow
        secondary.isStaticPreview = secResult.isNextLinePreview
        secondary.setLyric(secResult.line)
        secondary.isScrollOnly = if (secResult.isNextLinePreview) false else secResult.isScrollOnly

        if (requestMarquee) requestStartMarquee()
    }

    private fun applyLineStyle(
        view: SpaceGateLyricLineView, text: TextLook, highlight: Highlight,
        marquee: Marquee, gradient: Boolean, fadingEdge: Int, wordMotion: WordMotion,
        centerIfPossible: Boolean
    ) {
        view.wordMotion = wordMotion
        view.configureWith(text, highlight, marquee, gradient, fadingEdge, centerIfPossible)
    }

    private fun updateLayoutTransitionX(config: String? = LayoutTransitionX.TRANSITION_CONFIG_SMOOTH) {
        layoutTransition = LayoutTransitionX(config).apply { setAnimateParentHierarchy(true) }
    }

    private fun animateNextLinePromotion(nextMainText: String?, nextMainAlignedRight: Boolean) {
        val generation = ++nextLineTransitionGeneration
        nextLineTransitionRunning = true
        val followsInterlude = main.isInterludeIndicator
        val transitionDuration = if (followsInterlude) {
            INTERLUDE_PROMOTION_DURATION
        } else {
            NEXT_LINE_PROMOTION_DURATION
        }
        val targetTranslationY = (main.top - secondary.top).toFloat()
        // 飞行子行保持起跳宽；长句让外层岛提前增宽。新增空间按落定对齐
        // 放在右侧、两侧或左侧，动画再抵消子行在预留宽度中的位置变化。锚点语义（与
        // SpaceGateLyricLineView.resolveTextStartX 的决策树一一对应）：
        // 溢出行落定后从 0 起笔按左缘、靠右行取右缘、居中行取文本中点、其余左缘。
        val currentWidth = (width.takeIf { it > 0 } ?: measuredWidth).toFloat()
        promotionFlightWidth = currentWidth.toInt().takeIf { main.hugContentWidth && it > 0 }
        val landingWidth = currentWidth
        val secondaryTextStartX = secondary.currentTextStartX()
        val landingRightFlag = stagedPromotionRightAlign?.first ?: main.alignRight
        val landingCenterFlag = stagedPromotionCentering?.first ?: main.centerIfPossible
        val targetScale = (main.textSize / secondary.textSize).coerceIn(0.5f, 2f)
        val targetTextWidth = main.measureLineTextWidth(nextMainText)
        promotionReserveWidth = PromotionWidthGeometry.reserveWidth(
            pendingWidth = pendingHugWidth,
            currentWidth = currentWidth,
            hugContentWidth = main.hugContentWidth,
            scaledPreviewWidth = secondary.lineWidth * targetScale,
            targetTextWidth = targetTextWidth
        )
        promotionReserveOffsetFactor = promotionReserveWidth?.let {
            PromotionWidthGeometry.placementFactor(
                alignRight = landingRightFlag,
                center = landingCenterFlag,
                lineAlignedRight = nextMainAlignedRight
            )
        }
        // 预留生效时落定组宽=预留宽（对唱固定长度会把 hug 宽抬到全曲最长行），
        // 落定几何必须按预留宽计算：否则目标按起跳宽判溢出归零、落地重排后文本
        // 却居中在 (预留宽−文本宽)/2，产生无动画承接的落地横跳。
        val landingStartWidth = promotionReserveWidth?.toFloat() ?: landingWidth
        // 目标 X 按落定对齐（暂存值）计算：此刻旧句标志仍是上一行的方向。
        val targetMainTextStartX = main.textStartX(
            nextMainText,
            nextMainAlignedRight,
            centerIfPossibleOverride = stagedPromotionCentering?.first,
            alignRightOverride = stagedPromotionRightAlign?.first,
            availableWidthOverride = landingStartWidth
        )
        val anchorFactor = when {
            targetTextWidth >= landingStartWidth -> 0f
            landingRightFlag -> 1f
            landingCenterFlag -> 0.5f
            nextMainAlignedRight -> 1f
            else -> 0f
        }
        val anchorFrom = secondaryTextStartX + secondary.lineWidth * anchorFactor
        val anchorTo = targetMainTextStartX + targetTextWidth * anchorFactor
        promotionLandingStartX = promotionReserveWidth?.let { targetMainTextStartX }
        val reserveOffset = PromotionWidthGeometry.childOffset(
            promotionReserveWidth,
            currentWidth,
            promotionReserveOffsetFactor ?: 0f
        )
        val targetTranslationX = (main.left - secondary.left).toFloat() +
            anchorTo - anchorFrom - reserveOffset
        if (promotionReserveWidth != null) {
            clipChildren = false
            requestLayout()
        }
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                "SwitchTrace",
                "promotion begin view=${System.identityHashCode(this).toString(16)} " +
                    "mainLeft=${main.left} secLeft=${secondary.left} " +
                    "mainW=${main.scrollWidth} mainLW=${main.lineWidth} mainCenter=${main.centerIfPossible} " +
                    "secW=${secondary.scrollWidth} secLW=${secondary.lineWidth} secCenter=${secondary.centerIfPossible} " +
                    "landingW=$landingWidth predictedW=$pendingHugWidth reserveW=$promotionReserveWidth secStartX=$secondaryTextStartX " +
                    "targetMainX=$targetMainTextStartX targetW=$targetTextWidth " +
                    "anchorFrom=$anchorFrom anchorTo=$anchorTo factor=$anchorFactor " +
                    "reserveFactor=$promotionReserveOffsetFactor reserveOffset=$reserveOffset landingStartX=$promotionLandingStartX " +
                    "dT=$targetTranslationX dY=$targetTranslationY scale=$targetScale nextRight=$nextMainAlignedRight " +
                    "stagedCenter=${stagedPromotionCentering?.first} stagedRight=${stagedPromotionRightAlign?.first}"
            )
        }

        main.animate().cancel()
        secondary.animate().cancel()
        secondary.pivotX = anchorFrom
        secondary.pivotY = 0f
        main.animate()
            .alpha(0f)
            .translationY(-main.height * 0.65f)
            .setDuration(transitionDuration)
            .withLayer()
            .start()
        secondary.animate()
            .translationX(targetTranslationX)
            .translationY(targetTranslationY)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(transitionDuration)
            .withLayer()
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != nextLineTransitionGeneration) return
                    if (BuildConfig.DEBUG) {
                        HookLogger.d(
                            "SwitchTrace",
                            "promotion anim end view=${System.identityHashCode(this@SpaceGateRichLyricLineView).toString(16)} " +
                                "secTransX=${secondary.translationX} " +
                                // 真实视觉锚点：t + pivot + s·(画布起点 − pivot)
                                "secVisAnchor=${secondary.translationX + secondary.pivotX +
                                    secondary.scaleX * (secondary.currentTextStartX() - secondary.pivotX)} " +
                                "secW=${secondary.scrollWidth} secLW=${secondary.lineWidth} " +
                                "secCenter=${secondary.centerIfPossible}"
                        )
                    }
                    finishNextLinePromotion()
                }
            })
            .start()
        schedulePromotionWatchdog(generation, transitionDuration)
    }

    private fun finishNextLinePromotion(revealNextPreview: Boolean = true) {
        if (!nextLineTransitionRunning) return
        clearNextLineWatchdog()
        val landingOffset = if (promotionReserveWidth != null) {
            ((width - (promotionFlightWidth ?: width)).coerceAtLeast(0) *
                (promotionReserveOffsetFactor ?: 0f))
        } else null
        promotionReserveOffsetFactor = null
        // 预留路径的落定锚点：落地帧文本起点（旧宽子行溢出归零）+ 该补偿
        // 恰好落在按落定宽度算出的稳态起笔位，随后重排前后视觉连续。
        val landingStartX = promotionLandingStartX
        promotionLandingStartX = null
        clearNextLineTransitionState()
        nextLineTransitionRunning = false
        promotionFlightWidth = null
        promotionReserveWidth = null
        promotionLandingOffset = landingOffset
        main.translationX = (landingStartX ?: 0f) - (landingOffset ?: 0f)
        if (landingOffset == null) clipChildren = true
        pendingHugWidth = null
        refreshLines(allowNextLinePromotion = false, bypassIdentityCheck = true)
        // 新内容已渲染，此刻套用暂存对齐——与内容同帧生效，旧句淡出期间不受影响。
        applyStagedPromotionAlignment()
        onDeferredContentApplied?.invoke()
        if (revealNextPreview && alwaysShowSecondary) {
            secondary.alpha = 0f
            secondary.animate()
                .alpha(1f)
                .setDuration(NEXT_LINE_PREVIEW_FADE_DURATION)
                .withLayer()
                .setListener(null)
                .start()
        }
    }

    /**
     * 提升完成回调丢失的兜底：完成依赖 secondary 上 ViewPropertyAnimator 的
     * onAnimationEnd，监听被同视图后续动画替换或回调未触发时，
     * nextLineTransitionRunning 永久为 true，refreshLines 首行守卫会把之后所有
     * 歌词写入静默吞掉（真机实测：apply 持续到达、bind 恒为 0 的整岛卡死）。
     * 看门狗只在代数仍匹配且仍未落地时强制落地；正常落地后是空操作。
     */
    private fun schedulePromotionWatchdog(generation: Int, durationMs: Long) {
        clearNextLineWatchdog()
        val watchdog = Runnable {
            nextLineWatchdog = null
            if (generation != nextLineTransitionGeneration) return@Runnable
            if (!nextLineTransitionRunning) return@Runnable
            main.animate().setListener(null)
            secondary.animate().setListener(null)
            main.animate().cancel()
            secondary.animate().cancel()
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    "SwitchTrace",
                    "promotion watchdog fired view=${System.identityHashCode(this).toString(16)}"
                )
            }
            finishNextLinePromotion()
        }
        nextLineWatchdog = watchdog
        postDelayed(watchdog, durationMs + PROMOTION_WATCHDOG_MARGIN_MS)
    }

    private fun clearNextLineWatchdog() {
        nextLineWatchdog?.let { removeCallbacks(it) }
        nextLineWatchdog = null
    }

    private fun cancelNextLinePromotion() {
        nextLineTransitionGeneration++
        clearNextLineWatchdog()
        main.animate().setListener(null)
        secondary.animate().setListener(null)
        main.animate().cancel()
        secondary.animate().cancel()
        nextLineTransitionRunning = false
        promotionFlightWidth = null
        promotionReserveWidth = null
        promotionReserveOffsetFactor = null
        promotionLandingStartX = null
        promotionLandingOffset = null
        main.translationX = 0f
        clipChildren = true
        stagedPromotionCentering = null
        stagedPromotionRightAlign = null
        clearNextLineTransitionState()
    }

    private fun clearNextLineTransitionState() {
        main.alpha = 1f
        main.translationY = 0f
        secondary.alpha = 1f
        secondary.translationX = 0f
        secondary.translationY = 0f
        secondary.scaleX = 1f
        secondary.scaleY = 1f
    }

    private companion object {
        const val NEXT_LINE_PROMOTION_DURATION = 220L
        const val INTERLUDE_PROMOTION_DURATION = 320L
        const val NEXT_LINE_PREVIEW_FADE_DURATION = 140L
        const val PROMOTION_WATCHDOG_MARGIN_MS = 250L
    }
}
