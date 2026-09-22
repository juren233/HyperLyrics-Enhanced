package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.view.View
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.IslandLyricPosition
import com.juren233.hyperlyricsenhanced.common.lyric.AdjacentTranslationPolicy

internal data class IslandSlotRuntimeConfig(
    val activeMode: Int,
    val leftMode: Int,
    val rightMode: Int,
    val showAlbum: Boolean,
    val showRhythm: Boolean,
    val leftPaddingLeftDp: Int,
    val leftPaddingRightDp: Int,
    val rightPaddingLeftDp: Int,
    val rightPaddingRightDp: Int,
    val leftMaxWidthDp: Int,
    val rightMaxWidthDp: Int,
    val dynamicWidthEnabled: Boolean,
    val duetFixedLengthEnabled: Boolean,
    val pauseBehavior: Int,
    val forceNextSongAtEnd: Boolean,
    val nextSongDurationSeconds: Int,
    val nextSongPreviewStyle: Int,
    val nextSongPreviewPosition: Int,
    val nextSongPreviewWeight: Int,
    val textSizeSp: Int,
    val textSizeRatio: Float,
    val fontWeight: Int,
    val fontItalic: Boolean,
    val fadingEdgeLength: Int,
    val gradientProgress: Boolean,
    val leftLyricPosition: Int,
    val rightLyricPosition: Int,
    val centerGroupVocals: Boolean,
    val lyricAnimationEnabled: Boolean,
    val lyricAnimationId: String,
    val switchAnimRate: String,
    val switchAnimCustomRate: Float,
    val lyricMarqueeEnabled: Boolean,
    val lyricMarqueeSpeed: Int,
    val lyricMarqueeDelay: Int,
    val lyricMarqueeLoopDelay: Int,
    val lyricMarqueeInfinite: Boolean,
    val lyricMarqueeStopEnd: Boolean,
    val metadataMarqueeEnabled: Boolean,
    val metadataMarqueeSpeed: Int,
    val metadataMarqueeDelay: Int,
    val metadataMarqueeLoopDelay: Int,
    val metadataMarqueeInfinite: Boolean,
    val syllableRelative: Boolean,
    val syllableHighlight: Boolean,
    val translationDisplayMode: Int,
    val translationFallback: Boolean,
    val translationOnly: Boolean,
    val swapTranslation: Boolean,
    val nextLyricLine: Boolean,
    val adjacentBackgroundTranslation: Boolean,
    val extractCoverTextColor: Boolean,
    val extractCoverTextGradient: Boolean,
    val customTextColorEnabled: Boolean,
    val customTextColor: Int,
    val monetTextColorEnabled: Boolean,
    val customFontPath: String,
    val narrowLatinFont: Boolean,
    val wordMotionEnabled: Boolean,
    val wordMotionCjkLift: Float,
    val wordMotionCjkWave: Float,
    val wordMotionLatinLift: Float,
    val wordMotionLatinWave: Float,
    val dynamicLimitEnabled: Boolean = false,
) {
    val translationDisplay: Boolean
        get() = translationDisplayMode != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF

    val isSingleSideMode: Boolean
        get() = activeMode == RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE

    val isFullIslandMode: Boolean
        get() = activeMode == RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND

    val isSeparatedMode: Boolean
        get() = activeMode == RootConstants.HOOK_LYRIC_MODE_SEPARATED

    val usesBothLyricSlots: Boolean
        get() = isFullIslandMode || isSeparatedMode

    val usesSpaceGateView: Boolean
        get() = isFullIslandMode

    fun lyricPosition(isLeft: Boolean): Int = if (isLeft) leftLyricPosition else rightLyricPosition

    // 双槽歌词模式由模式本身决定两侧布局，不消费单侧歌词的位置偏好。
    fun centerLyric(isLeft: Boolean): Boolean =
        !usesBothLyricSlots && IslandLyricPosition.centers(lyricPosition(isLeft))

    fun rightAlignLyric(isLeft: Boolean): Boolean =
        !usesBothLyricSlots && IslandLyricPosition.alignsRight(lyricPosition(isLeft))

    /**
     * Horizontal placement for content-width slots. Apply this at both the
     * injected wrapper and the native module boundary: aligning only the inner
     * wrapper cannot override the right native module's END layout gravity.
     */
    fun wrapperHorizontalGravity(isLeft: Boolean): Int = when {
        centerLyric(isLeft) -> android.view.Gravity.CENTER_HORIZONTAL
        rightAlignLyric(isLeft) -> android.view.Gravity.END
        else -> android.view.Gravity.START
    }

    /**
     * 动态长度下歌词槽的行级对唱锚点：位置偏好为「默认」时，行级右对唱
     * （isAlignedRight）必须把 wrapper 与原生模块整体锚到 END 才可见——
     * 原生机型公式把左右 area 镜像成等宽，多出的镜像余量在 wrapper 之外，
     * 行内偏移够不到（160169 真机：42px 模块内部 + 27px area 余量全部留在右侧）。
     * 只在歌词槽（模式 7）且用户未显式选居中/靠右时生效；读「已绑定行」，
     * 淡出窗口内保持旧句方向，与内容同点落地。
     */
    fun wrapperHorizontalGravity(isLeft: Boolean, duetLineAlignedRight: Boolean?): Int {
        val base = wrapperHorizontalGravity(isLeft)
        // 双槽歌词由模式固定左右槽的位置，不再让单槽对唱锚点移动任一侧 wrapper。
        if (usesBothLyricSlots) return base
        if (duetLineAlignedRight != true || !dynamicWidthEnabled) return base
        if (centerLyric(isLeft) || rightAlignLyric(isLeft)) return base
        val isLyricSlot = (if (isLeft) leftMode else rightMode) == 7
        return if (isLyricSlot) android.view.Gravity.END else base
    }

    val groupVocalCenteringEnabled: Boolean
        get() = IslandLyricPosition.supportsGroupVocalCentering(
            lyricMode = activeMode,
            leftContent = leftMode,
            rightContent = rightMode
        )

    /** 换句动画速率倍率（各样式内置时长为 1x），仅用于歌词切换动画的出/入段缩放。 */
    val switchAnimRateFactor: Float
        get() = RootConstants.resolveSwitchAnimRateFactor(switchAnimRate, switchAnimCustomRate)

    val styleSignature: String = listOf(
        activeMode,
        textSizeSp,
        dynamicWidthEnabled,
        duetFixedLengthEnabled,
        dynamicLimitEnabled,
        textSizeRatio,
        fontWeight,
        fontItalic,
        fadingEdgeLength,
        gradientProgress,
        leftLyricPosition,
        rightLyricPosition,
        centerGroupVocals,
        lyricAnimationEnabled,
        lyricAnimationId,
        switchAnimRate,
        switchAnimCustomRate,
        lyricMarqueeEnabled,
        lyricMarqueeSpeed,
        lyricMarqueeDelay,
        lyricMarqueeLoopDelay,
        lyricMarqueeInfinite,
        lyricMarqueeStopEnd,
        metadataMarqueeEnabled,
        metadataMarqueeSpeed,
        metadataMarqueeDelay,
        metadataMarqueeLoopDelay,
        metadataMarqueeInfinite,
        syllableRelative,
        syllableHighlight,
        translationDisplayMode,
        translationFallback,
        translationOnly,
        swapTranslation,
        nextLyricLine,
        adjacentBackgroundTranslation,
        forceNextSongAtEnd,
        nextSongDurationSeconds,
        nextSongPreviewStyle,
        nextSongPreviewPosition,
        nextSongPreviewWeight,
        extractCoverTextColor,
        extractCoverTextGradient,
        customTextColorEnabled,
        customTextColor,
        monetTextColorEnabled,
        customFontPath,
        narrowLatinFont,
        wordMotionEnabled,
        wordMotionCjkLift,
        wordMotionCjkWave,
        wordMotionLatinLift,
        wordMotionLatinWave
    ).joinToString("|")

    fun modeForTag(tag: String): Int {
        return if (tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG) leftMode else rightMode
    }

    val supportsAdjacentBackgroundTranslation: Boolean
        get() = AdjacentTranslationPolicy.isEligible(activeMode, leftMode, rightMode)

    val adjacentTranslationTargetIsLeft: Boolean?
        get() = AdjacentTranslationPolicy.targetIsLeft(leftMode, rightMode)

    val shouldInjectLeft: Boolean
        get() = (dynamicLimitEnabled || leftMaxWidthDp > 0) && (leftMode != 0 ||
            nextSongPreviewStyle == RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL ||
            (
                nextSongPreviewStyle == RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF &&
                    halfPreviewTargetIsLeft
                ) || (
            adjacentBackgroundTranslation &&
                supportsAdjacentBackgroundTranslation &&
                adjacentTranslationTargetIsLeft == true
            ))

    val shouldInjectRight: Boolean
        get() = (dynamicLimitEnabled || rightMaxWidthDp > 0) && (rightMode != 0 ||
            nextSongPreviewStyle == RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL ||
            (
                nextSongPreviewStyle == RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF &&
                    !halfPreviewTargetIsLeft
                ) || (
            adjacentBackgroundTranslation &&
                supportsAdjacentBackgroundTranslation &&
                adjacentTranslationTargetIsLeft == false
            ))

    val nextSongPreviewEnabled: Boolean
        get() = nextSongPreviewStyle != RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE

    val nextSongDurationMs: Long
        get() = resolveNextSongPreviewDurationMs(
            style = nextSongPreviewStyle,
            fullDurationSeconds = nextSongDurationSeconds
        )

    val shouldForceNextSongPreview: Boolean
        get() = resolveShouldForceNextSongPreview(
            style = nextSongPreviewStyle,
            fullForceEnabled = forceNextSongAtEnd
        )

    val halfPreviewTargetIsLeft: Boolean
        get() = resolveHalfPreviewTargetIsLeft(
            position = nextSongPreviewPosition,
            leftMode = leftMode,
            rightMode = rightMode
        )

    fun isLeftTag(tag: String): Boolean {
        return tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
    }

    fun isLeftParent(parentName: String): Boolean {
        return parentName.contains("1")
    }

    fun maxWidthDp(parentName: String): Int {
        return if (isLeftParent(parentName)) leftMaxWidthDp else rightMaxWidthDp
    }

    fun paddingLeftDp(parentName: String): Int {
        return if (isLeftParent(parentName)) leftPaddingLeftDp else rightPaddingLeftDp
    }

    fun paddingRightDp(parentName: String): Int {
        val base = if (isLeftParent(parentName)) leftPaddingRightDp else rightPaddingRightDp
        // 律动图标与注入内容在模块内各自布局，间隔只能由内容包装层的右内边距提供。
        return if (!isLeftParent(parentName) && showRhythm) base + RHYTHM_TRAILING_GAP_DP else base
    }

    fun widthPx(rootView: View, parentName: String): Int? {
        val metrics = rootView.resources.displayMetrics
        return contentWidthPx(metrics.widthPixels, metrics.density, isLeftParent(parentName))
    }

    internal fun contentWidthPx(screenWidthPx: Int, density: Float, isLeft: Boolean): Int? {
        // Automatic mode uses a physical measurement bound; the native-result limiter
        // supplies the final status-bar-safe geometry, including cover and cutout space.
        if (dynamicLimitEnabled) return (screenWidthPx / 2).coerceAtLeast(1)
        val maxWidthDp = if (isLeft) leftMaxWidthDp else rightMaxWidthDp
        if (maxWidthDp <= 0) return null
        return (maxWidthDp * density).toInt().coerceAtLeast(1)
    }

    fun paddingLeftPx(rootView: View, parentName: String): Int {
        return (paddingLeftDp(parentName) * rootView.resources.displayMetrics.density).toInt()
    }

    fun paddingRightPx(rootView: View, parentName: String): Int {
        return (paddingRightDp(parentName) * rootView.resources.displayMetrics.density).toInt()
    }

    companion object {
        /** 音频律动开启时，右槽注入内容与律动图标之间的固定间隔。 */
        internal const val RHYTHM_TRAILING_GAP_DP = 6

        internal fun resolveNextSongPreviewStyle(
            hasStoredStyle: Boolean,
            storedStyle: Int,
            legacyDurationSeconds: Int
        ): Int {
            if (!hasStoredStyle) {
                return if (legacyDurationSeconds > 0) {
                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL
                } else {
                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE
                }
            }
            return storedStyle.coerceIn(
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE,
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF
            )
        }

        internal fun resolveHalfPreviewTargetIsLeft(
            position: Int,
            leftMode: Int,
            rightMode: Int
        ): Boolean {
            return when (position) {
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_LEFT -> true
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_RIGHT -> false
                else -> when {
                    leftMode == 7 && rightMode != 7 -> false
                    rightMode == 7 && leftMode != 7 -> true
                    else -> true
                }
            }
        }

        internal fun resolveNextSongPreviewDurationMs(
            style: Int,
            fullDurationSeconds: Int
        ): Long {
            return when (style) {
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL ->
                    fullDurationSeconds * 1_000L
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF ->
                    RootConstants.ISLAND_NEXT_SONG_HALF_PREVIEW_DURATION_MS
                else -> 0L
            }
        }

        internal fun resolveShouldForceNextSongPreview(
            style: Int,
            fullForceEnabled: Boolean
        ): Boolean {
            return when (style) {
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL -> fullForceEnabled
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF -> true
                else -> false
            }
        }

        fun from(prefs: SharedPreferences): IslandSlotRuntimeConfig {
            val dynamicLimitEnabled = runtimeBoolean(prefs,
                RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT,
                RootConstants.DEFAULT_HOOK_ISLAND_DYNAMIC_LIMIT)
            val activeMode = runtimeInt(
                prefs,
                RootConstants.KEY_HOOK_LYRIC_MODE,
                RootConstants.DEFAULT_HOOK_LYRIC_MODE
            )
            val nextSongDurationSeconds = prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_DURATION,
                RootConstants.DEFAULT_HOOK_ISLAND_NEXT_SONG_DURATION
            ).coerceIn(0, 5)
            val storedNextSongPreviewStyle = prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_STYLE,
                Int.MIN_VALUE
            )
            return IslandSlotRuntimeConfig(
                activeMode = activeMode,
                leftMode = if (activeMode == RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE) runtimeInt(
                    prefs,
                    RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
                    RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT
                ) else 7,
                rightMode = if (activeMode == RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE) runtimeInt(
                    prefs,
                    RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT,
                    RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_RIGHT
                ) else 7,
                showAlbum = prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_ALBUM),
                showRhythm = prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_ICON),
                leftPaddingLeftDp = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_PADDING_LEFT),
                leftPaddingRightDp = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_PADDING_RIGHT),
                rightPaddingLeftDp = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_PADDING_LEFT),
                rightPaddingRightDp = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_PADDING_RIGHT),
                // Do not even read manual lengths while automatic control is active.
                leftMaxWidthDp = if (dynamicLimitEnabled) 0 else runtimeInt(prefs, RootConstants.KEY_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH),
                rightMaxWidthDp = if (dynamicLimitEnabled) 0 else runtimeInt(prefs, RootConstants.KEY_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH),
                dynamicLimitEnabled = dynamicLimitEnabled,
                dynamicWidthEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_DYNAMIC_WIDTH),
                duetFixedLengthEnabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_DUET_FIXED_LENGTH,
                    RootConstants.DEFAULT_HOOK_ISLAND_DUET_FIXED_LENGTH
                ),
                pauseBehavior = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE, RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE),
                forceNextSongAtEnd = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_FORCE_NEXT_SONG_AT_END,
                    RootConstants.DEFAULT_HOOK_ISLAND_FORCE_NEXT_SONG_AT_END
                ),
                nextSongDurationSeconds = nextSongDurationSeconds,
                nextSongPreviewStyle = resolveNextSongPreviewStyle(
                    hasStoredStyle = storedNextSongPreviewStyle != Int.MIN_VALUE,
                    storedStyle = storedNextSongPreviewStyle,
                    legacyDurationSeconds = nextSongDurationSeconds
                ),
                nextSongPreviewPosition = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_POSITION,
                    RootConstants.DEFAULT_HOOK_ISLAND_NEXT_SONG_PREVIEW_POSITION
                ).coerceIn(
                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_OTHER_SIDE,
                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_RIGHT
                ),
                nextSongPreviewWeight = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_WEIGHT,
                    RootConstants.DEFAULT_HOOK_ISLAND_NEXT_SONG_PREVIEW_WEIGHT
                ).coerceIn(
                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_WEIGHT_TOP,
                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_WEIGHT_BOTTOM
                ),
                textSizeSp = prefs.getInt(RootConstants.KEY_HOOK_TEXT_SIZE, RootConstants.DEFAULT_HOOK_TEXT_SIZE),
                textSizeRatio = prefs.getFloat(RootConstants.KEY_HOOK_TEXT_SIZE_RATIO, RootConstants.DEFAULT_HOOK_TEXT_SIZE_RATIO),
                fontWeight = prefs.getInt(RootConstants.KEY_HOOK_FONT_WEIGHT, RootConstants.DEFAULT_HOOK_FONT_WEIGHT),
                fontItalic = prefs.getBoolean(RootConstants.KEY_HOOK_FONT_ITALIC, RootConstants.DEFAULT_HOOK_FONT_ITALIC),
                fadingEdgeLength = prefs.getInt(RootConstants.KEY_HOOK_FADING_EDGE_LENGTH, RootConstants.DEFAULT_HOOK_FADING_EDGE_LENGTH),
                gradientProgress = prefs.getBoolean(RootConstants.KEY_HOOK_GRADIENT_PROGRESS, RootConstants.DEFAULT_HOOK_GRADIENT_PROGRESS),
                leftLyricPosition = resolveSideLyricPosition(
                    prefs = prefs,
                    key = RootConstants.KEY_HOOK_ISLAND_LEFT_LYRIC_POSITION
                ),
                rightLyricPosition = resolveSideLyricPosition(
                    prefs = prefs,
                    key = RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION
                ),
                centerGroupVocals = runtimeBoolean(
                    prefs,
                    RootConstants.KEY_HOOK_CENTER_GROUP_VOCALS,
                    RootConstants.DEFAULT_HOOK_CENTER_GROUP_VOCALS
                ),
                lyricAnimationEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_ANIM_ENABLE, RootConstants.DEFAULT_HOOK_ANIM_ENABLE),
                lyricAnimationId = prefs.getString(RootConstants.KEY_HOOK_ANIM_ID, RootConstants.DEFAULT_HOOK_ANIM_ID) ?: RootConstants.DEFAULT_HOOK_ANIM_ID,
                switchAnimRate = prefs.getString(RootConstants.KEY_HOOK_SWITCH_ANIM_RATE, RootConstants.DEFAULT_HOOK_SWITCH_ANIM_RATE) ?: RootConstants.DEFAULT_HOOK_SWITCH_ANIM_RATE,
                switchAnimCustomRate = prefs.getFloat(RootConstants.KEY_HOOK_SWITCH_ANIM_CUSTOM_RATE, RootConstants.DEFAULT_HOOK_SWITCH_ANIM_CUSTOM_RATE),
                lyricMarqueeEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_MODE),
                lyricMarqueeSpeed = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_SPEED, RootConstants.DEFAULT_HOOK_MARQUEE_SPEED),
                lyricMarqueeDelay = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_DELAY),
                lyricMarqueeLoopDelay = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_LOOP_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_LOOP_DELAY),
                lyricMarqueeInfinite = prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_INFINITE, RootConstants.DEFAULT_HOOK_MARQUEE_INFINITE),
                lyricMarqueeStopEnd = prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_STOP_END, RootConstants.DEFAULT_HOOK_MARQUEE_STOP_END),
                metadataMarqueeEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_METADATA_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_MODE),
                metadataMarqueeSpeed = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_SPEED, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_SPEED),
                metadataMarqueeDelay = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_DELAY),
                metadataMarqueeLoopDelay = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_LOOP_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_LOOP_DELAY),
                metadataMarqueeInfinite = prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_METADATA_INFINITE, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_INFINITE),
                syllableRelative = prefs.getBoolean(RootConstants.KEY_HOOK_SYLLABLE_RELATIVE, RootConstants.DEFAULT_HOOK_SYLLABLE_RELATIVE),
                syllableHighlight = prefs.getBoolean(RootConstants.KEY_HOOK_SYLLABLE_HIGHLIGHT, RootConstants.DEFAULT_HOOK_SYLLABLE_HIGHLIGHT),
                translationDisplayMode = com.juren233.hyperlyricsenhanced.root.utils.TranslationHelper.getTranslationDisplayMode(prefs),
                translationFallback = com.juren233.hyperlyricsenhanced.root.utils.TranslationHelper.isTranslationFallback(prefs),
                translationOnly = prefs.getBoolean(RootConstants.KEY_HOOK_TRANSLATION_ONLY, RootConstants.DEFAULT_HOOK_TRANSLATION_ONLY),
                swapTranslation = prefs.getBoolean(RootConstants.KEY_HOOK_SWAP_TRANSLATION, RootConstants.DEFAULT_HOOK_SWAP_TRANSLATION),
                nextLyricLine = prefs.getBoolean(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, RootConstants.DEFAULT_HOOK_NEXT_LYRIC_LINE),
                adjacentBackgroundTranslation = prefs.getBoolean(RootConstants.KEY_HOOK_ADJACENT_BACKGROUND_TRANSLATION, RootConstants.DEFAULT_HOOK_ADJACENT_BACKGROUND_TRANSLATION),
                extractCoverTextColor = runtimeBoolean(
                    prefs,
                    RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR,
                    RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_COLOR
                ),
                extractCoverTextGradient = runtimeBoolean(
                    prefs,
                    RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT,
                    RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_GRADIENT
                ),
                customTextColorEnabled = runtimeBoolean(
                    prefs,
                    RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED,
                    RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR_ENABLED
                ),
                customTextColor = IslandRuntimePreferenceReader.getInt(
                    prefs,
                    RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR,
                    RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR
                ),
                monetTextColorEnabled = runtimeBoolean(
                    prefs,
                    RootConstants.KEY_HOOK_MONET_TEXT_COLOR,
                    RootConstants.DEFAULT_HOOK_MONET_TEXT_COLOR
                ),
                customFontPath = prefs.getString(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, null).orEmpty(),
                narrowLatinFont = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NARROW_LATIN_FONT,
                    RootConstants.DEFAULT_HOOK_NARROW_LATIN_FONT
                ),
                wordMotionEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_WORD_MOTION_ENABLED, RootConstants.DEFAULT_HOOK_WORD_MOTION_ENABLED),
                wordMotionCjkLift = prefs.getFloat(RootConstants.KEY_HOOK_WORD_MOTION_CJK_LIFT, RootConstants.DEFAULT_HOOK_WORD_MOTION_CJK_LIFT),
                wordMotionCjkWave = prefs.getFloat(RootConstants.KEY_HOOK_WORD_MOTION_CJK_WAVE, RootConstants.DEFAULT_HOOK_WORD_MOTION_CJK_WAVE),
                wordMotionLatinLift = prefs.getFloat(RootConstants.KEY_HOOK_WORD_MOTION_LATIN_LIFT, RootConstants.DEFAULT_HOOK_WORD_MOTION_LATIN_LIFT),
                wordMotionLatinWave = prefs.getFloat(RootConstants.KEY_HOOK_WORD_MOTION_LATIN_WAVE, RootConstants.DEFAULT_HOOK_WORD_MOTION_LATIN_WAVE)
            )
        }

    private fun resolveSideLyricPosition(
        prefs: SharedPreferences,
        key: String
    ): Int {
        val legacyGlobalPosition = IslandLyricPosition.resolve(
            storedPosition = runtimeInt(
                prefs,
                RootConstants.KEY_HOOK_LYRIC_POSITION,
                Int.MIN_VALUE
            )
                .takeUnless { it == Int.MIN_VALUE },
            legacyCenterEnabled = runtimeBoolean(
                prefs,
                RootConstants.KEY_HOOK_CENTER_LYRIC,
                RootConstants.DEFAULT_HOOK_CENTER_LYRIC
            )
        )
        return IslandLyricPosition.resolveSide(
            storedSidePosition = runtimeInt(prefs, key, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE },
            legacyGlobalPosition = legacyGlobalPosition,
            legacyCenterEnabled = false
        )
    }

    private fun runtimeInt(
        prefs: SharedPreferences,
        key: String,
        default: Int
    ): Int = IslandRuntimePreferenceOverrides.getInt(
        key,
        prefs.getInt(key, default)
    )

    private fun runtimeBoolean(
        prefs: SharedPreferences,
        key: String,
        default: Boolean
    ): Boolean = IslandRuntimePreferenceReader.getBoolean(prefs, key, default)
}
}
