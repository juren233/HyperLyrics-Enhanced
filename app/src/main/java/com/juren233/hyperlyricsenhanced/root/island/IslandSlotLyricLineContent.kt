/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.Context
import android.content.SharedPreferences
import android.text.TextPaint
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.IslandLyricPosition
import com.juren233.hyperlyricsenhanced.common.lyric.CjkLyricWhitespacePolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.RichLyricLineSplitter
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW_ALIGNED_RIGHT
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW_CENTERED
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.LyricViewStyle
import com.juren233.hyperlyricsenhanced.lyric.view.isTitleLine
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoPresets
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.animateEntrance
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.animateUpdate
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.CoverColorHelper
import com.juren233.hyperlyricsenhanced.root.utils.CoverColorDiagnostics
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.LyricStyleHelper
import com.juren233.hyperlyricsenhanced.root.utils.TranslationHelper
import java.util.WeakHashMap

internal fun IslandSlotContentAssembler.lineContentSignature(line: IRichLyricLine?): Int {
    if (line == null) return 0
    return listOf(
        line.begin,
        line.end,
        line.duration,
        line.text,
        line.words,
        line.secondary,
        line.secondaryWords,
        line.translation,
        line.translationWords,
        line.roma,
        line.isAlignedRight,
        line.metadata
    ).hashCode()
}

internal fun IslandSlotContentAssembler.hasViewLineContentChanged(view: View, targetLine: IRichLyricLine?): Boolean {
    val currentLine = when (view) {
        is RichLyricLineView -> view.line
        is SpaceGateRichLyricLineView -> view.line
        else -> null
    }
    return hasLineContentChanged(currentLine, targetLine)
}

internal fun IslandSlotContentAssembler.recordLyricAvailability(view: View, targetLine: IRichLyricLine?): Boolean {
    val songVersion = LyriconDataBridge.versionCounter.get()
    val hasLyrics = isActualLyricAvailable(
        sourceLine = LyriconDataBridge.currentLyricLine,
        targetLine = targetLine
    )
    val previousAvailability = synchronized(lastLyricAvailability) {
        val previous = lastLyricAvailability[view]
        val availability = when {
            previous?.songVersion == songVersion -> previous.hasLyrics
            previous?.hasLyrics == false -> false
            else -> null
        }
        lastLyricAvailability[view] = IslandSlotContentAssembler.LyricAvailability(songVersion, hasLyrics)
        availability
    }
    return previousAvailability == false && hasLyrics
}

internal fun IslandSlotContentAssembler.isActualLyricAvailable(
    sourceLine: IRichLyricLine?,
    targetLine: IRichLyricLine?
): Boolean = IslandSlotContentAssembler.hasVisibleLyricContent(sourceLine) && IslandSlotContentAssembler.hasVisibleLyricContent(targetLine)

internal fun IslandSlotContentAssembler.hasLineContentChanged(
    currentLine: IRichLyricLine?,
    targetLine: IRichLyricLine?
): Boolean = lineContentSignature(currentLine) != lineContentSignature(targetLine)

internal fun IslandSlotContentAssembler.isEmptyToPopulatedLyricTransition(
    currentLine: IRichLyricLine?,
    targetLine: IRichLyricLine?
): Boolean = !IslandSlotContentAssembler.hasVisibleLyricContent(currentLine) && IslandSlotContentAssembler.hasVisibleLyricContent(targetLine)

internal fun IslandSlotContentAssembler.hasVisibleLyricContent(line: IRichLyricLine?): Boolean =
    line?.isTitleLine() != true && (
        !line?.text.isNullOrBlank() ||
        !line?.words.isNullOrEmpty() ||
        !line?.secondary.isNullOrBlank() ||
        !line?.secondaryWords.isNullOrEmpty() ||
        !line?.translation.isNullOrBlank() ||
        !line?.translationWords.isNullOrEmpty() ||
        !line?.roma.isNullOrBlank()
    )

internal fun IslandSlotContentAssembler.shouldCenterLine(
    config: IslandSlotRuntimeConfig,
    line: IRichLyricLine?,
    isLeft: Boolean?
): Boolean = isLeft != null && config.centerLyric(isLeft) || (
    config.groupVocalCenteringEnabled &&
    config.centerGroupVocals &&
        line?.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true
    )

internal fun IslandSlotContentAssembler.applyLineCentering(
    view: View,
    centerMain: Boolean,
    centerSecondary: Boolean = centerMain
) {
    when (view) {
        is RichLyricLineView -> {
            view.setLineCentering(centerMain, centerSecondary)
        }
        is SpaceGateRichLyricLineView -> {
            view.setLineCentering(centerMain, centerSecondary)
        }
    }
}

internal fun IslandSlotContentAssembler.applyLineRightAlignment(
    view: View,
    alignMainRight: Boolean,
    alignSecondaryRight: Boolean = alignMainRight
) {
    when (view) {
        is RichLyricLineView -> view.setLineAlignmentRight(
            alignMainRight,
            alignSecondaryRight
        )
        is SpaceGateRichLyricLineView -> view.setLineAlignmentRight(
            alignMainRight,
            alignSecondaryRight
        )
    }
}

internal fun IslandSlotContentAssembler.isNextLinePreviewEnabled(
    prefs: SharedPreferences,
    config: IslandSlotRuntimeConfig,
    currentLine: IRichLyricLine? = LyriconDataBridge.currentLyricLine
): Boolean {
    if (!config.nextLyricLine || config.isSplitMode) return false
    if (LyriconDataBridge.isTextMode) return false
    val source = prefs.getString(RootConstants.KEY_HOOK_LYRIC_SOURCE, RootConstants.DEFAULT_HOOK_LYRIC_SOURCE)
    if (source != "lyricon" && source != "lyricinfo") return false
    return shouldUseNextLinePreview(
        config.translationDisplayMode,
        config.translationFallback,
        currentLine
    )
}

internal fun IslandSlotContentAssembler.shouldUseNextLinePreview(
    translationDisplayMode: Int,
    translationFallback: Boolean,
    currentLine: IRichLyricLine?
): Boolean {
    val hasSecondary = !currentLine?.secondary.isNullOrBlank() ||
        !currentLine?.secondaryWords.isNullOrEmpty()
    val hasTranslation = !currentLine?.translation.isNullOrBlank() ||
        !currentLine?.translationWords.isNullOrEmpty()
    val hasRoma = !currentLine?.roma.isNullOrBlank()

    val hasDisplayedExtra = when (translationDisplayMode) {
        RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION ->
            hasTranslation || (translationFallback && hasRoma)
        RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION ->
            hasRoma || (translationFallback && hasTranslation)
        else -> false
    }
    return !hasDisplayedExtra && !hasSecondary
}

internal fun IslandSlotContentAssembler.shouldUseNextLinePreview(
    translationDisplayed: Boolean,
    currentLine: IRichLyricLine?
): Boolean {
    val mode = if (translationDisplayed) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
    else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
    return shouldUseNextLinePreview(mode, false, currentLine)
}

internal fun IRichLyricLine.withNextLinePreview(
    nextLine: IRichLyricLine?,
    centerNextLine: Boolean
): IRichLyricLine {
    val nextText = nextLine?.text?.takeIf { it.isNotBlank() }
    return RichLyricLine(
        begin = begin,
        end = end,
        duration = duration,
        isAlignedRight = isAlignedRight,
        metadata = lyricMetadataOf(
            *(metadata?.entries?.map { it.key to it.value } ?: emptyList()).toTypedArray(),
            METADATA_NEXT_LINE_PREVIEW to "true",
            METADATA_NEXT_LINE_PREVIEW_ALIGNED_RIGHT to nextLine?.isAlignedRight.toString(),
            METADATA_NEXT_LINE_PREVIEW_CENTERED to centerNextLine.toString()
        ),
        text = text,
        words = words,
        secondary = nextText,
        secondaryWords = emptyList(),
        translation = null,
        translationWords = null,
        roma = null
    )
}
