/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.LyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf

internal const val METADATA_NEXT_LINE_PREVIEW = "nextLinePreview"
internal const val METADATA_NEXT_LINE_PREVIEW_ALIGNED_RIGHT = "nextLinePreviewAlignedRight"
internal const val METADATA_NEXT_LINE_PREVIEW_CENTERED = "nextLinePreviewCentered"

internal fun shouldPromoteNextLinePreview(
    wasPreview: Boolean,
    currentMainText: String?,
    previewText: String?,
    nextMainText: String?,
    lineAdvanced: Boolean
): Boolean = wasPreview &&
    currentMainText != null &&
    lineAdvanced &&
    previewText == nextMainText

internal fun hasLyricLineAdvanced(
    previousLine: IRichLyricLine?,
    targetLine: IRichLyricLine?
): Boolean = previousLine != null && targetLine != null && (
    previousLine.begin != targetLine.begin ||
        previousLine.end != targetLine.end ||
        previousLine.duration != targetLine.duration
    )

internal fun canAnimateNextLinePromotion(
    wasPreview: Boolean,
    currentMainText: String?,
    previewText: String?,
    nextMainText: String?,
    lineAdvanced: Boolean,
    attached: Boolean,
    mainHeight: Int,
    secondaryHeight: Int
): Boolean = shouldPromoteNextLinePreview(
    wasPreview = wasPreview,
    currentMainText = currentMainText,
    previewText = previewText,
    nextMainText = nextMainText,
    lineAdvanced = lineAdvanced
) && attached && mainHeight > 0 && secondaryHeight > 0

private enum class SecondaryChoice {
    Translation,
    Roma,
}

internal class LyricLineAssembler(
    private var displayMode: Int = RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_DISPLAY,
    private var fallback: Boolean = RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_FALLBACK,
    private var hideSecondaryContent: Boolean = false,
    private var enableRelativeProgress: Boolean = false,
    private var enableRelativeHighlight: Boolean = false,
) {
    private val wordBuilder = RelativeWordBuilder()

    fun updateFlags(
        displayMode: Int,
        fallback: Boolean,
        hideSecondaryContent: Boolean,
        enableRelativeProgress: Boolean,
        enableRelativeHighlight: Boolean
    ) {
        this.displayMode = displayMode
        this.fallback = fallback
        this.hideSecondaryContent = hideSecondaryContent
        this.enableRelativeProgress = enableRelativeProgress
        this.enableRelativeHighlight = enableRelativeHighlight
    }

    fun updateFlags(
        displayTranslation: Boolean,
        displayRoma: Boolean,
        enableRelativeProgress: Boolean,
        enableRelativeHighlight: Boolean
    ) {
        val mode = when {
            displayTranslation -> RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
            displayRoma -> RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            else -> RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
        }
        updateFlags(
            displayMode = mode,
            fallback = false,
            hideSecondaryContent = mode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
            enableRelativeProgress = enableRelativeProgress,
            enableRelativeHighlight = enableRelativeHighlight
        )
    }

    data class MainResult(val line: LyricLine, val isScrollOnly: Boolean)

    fun buildMain(source: IRichLyricLine?): MainResult {
        if (source == null) return MainResult(LyricLine(), false)

        val hasOriginalWords = !source.words.isNullOrEmpty()
        val shouldGen = enableRelativeProgress && source.isTitleLine().not()
        val words = if (shouldGen) {
            wordBuilder.build(source, source.text, source.words)
        } else source.words

        val generated = !hasOriginalWords && words !== source.words
        val line = LyricLine(
            begin = source.begin, end = source.end, duration = source.duration,
            isAlignedRight = source.isAlignedRight, metadata = source.metadata,
            text = source.text, words = words
        )
        return MainResult(line, generated && !enableRelativeHighlight)
    }

    data class SecondaryResult(
        val line: LyricLine,
        val alwaysShow: Boolean,
        val isScrollOnly: Boolean,
        val isNextLinePreview: Boolean
    )

    fun buildSecondary(source: IRichLyricLine?): SecondaryResult {
        if (source == null) return SecondaryResult(LyricLine(), false, false, false)

        var generated = false
        val isNextLinePreview = source.metadata?.getBoolean(METADATA_NEXT_LINE_PREVIEW) == true
        val line = LyricLine().apply {
            begin = source.begin; end = source.end; duration = source.duration
            isAlignedRight = if (isNextLinePreview) {
                source.metadata?.getBoolean(
                    METADATA_NEXT_LINE_PREVIEW_ALIGNED_RIGHT,
                    source.isAlignedRight
                ) ?: source.isAlignedRight
            } else {
                source.metadata?.getBoolean(
                    LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT,
                    source.isAlignedRight
                ) ?: source.isAlignedRight
            }

            val hasSecondary = !source.secondary.isNullOrBlank() || !source.secondaryWords.isNullOrEmpty()
            val hasTranslation = !source.translation.isNullOrBlank() || !source.translationWords.isNullOrEmpty()
            val hasRoma = !source.roma.isNullOrBlank()

            val effectiveSecondary = if (hideSecondaryContent) null else when (displayMode) {
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION -> when {
                    hasTranslation -> SecondaryChoice.Translation
                    fallback && hasRoma -> SecondaryChoice.Roma
                    else -> null
                }
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION -> when {
                    hasRoma -> SecondaryChoice.Roma
                    fallback && hasTranslation -> SecondaryChoice.Translation
                    else -> null
                }
                else -> null
            }

            when {
                hasSecondary -> {
                    text = source.secondary
                    if (isNextLinePreview) {
                        // 下一句只是预览文本，不能继承当前行时间轴或生成相对时间轴。
                        words = emptyList()
                        metadata = lyricMetadataOf(METADATA_NEXT_LINE_PREVIEW to "true")
                    } else {
                        words = wordBuilder.build(source, source.secondary, source.secondaryWords)
                        generated = words !== source.secondaryWords
                    }
                }
                effectiveSecondary == SecondaryChoice.Translation -> {
                    text = source.translation
                    words = wordBuilder.build(source, source.translation, source.translationWords)
                    metadata = lyricMetadataOf("translation" to "true")
                    generated = words !== source.translationWords
                }
                effectiveSecondary == SecondaryChoice.Roma -> {
                    text = source.roma
                    words = wordBuilder.build(source, source.roma, null)
                    metadata = lyricMetadataOf("roma" to "true")
                    generated = true
                }
            }
        }

        val alwaysShow = line.text?.isNotBlank() == true || !line.words.isNullOrEmpty()

        return SecondaryResult(line, alwaysShow, generated && !enableRelativeHighlight, isNextLinePreview)
    }
}
