/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

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
    nextMainText: String?
): Boolean = wasPreview &&
    currentMainText != null &&
    currentMainText != nextMainText &&
    previewText == nextMainText

internal class LyricLineAssembler(
    private var displayTranslation: Boolean = true,
    private var displayRoma: Boolean = true,
    private var enableRelativeProgress: Boolean = false,
    private var enableRelativeHighlight: Boolean = false,
) {
    private val wordBuilder = RelativeWordBuilder()

    fun updateFlags(displayTranslation: Boolean, displayRoma: Boolean,
                    enableRelativeProgress: Boolean, enableRelativeHighlight: Boolean) {
        this.displayTranslation = displayTranslation
        this.displayRoma = displayRoma
        this.enableRelativeProgress = enableRelativeProgress
        this.enableRelativeHighlight = enableRelativeHighlight
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
                source.isAlignedRight
            }

            when {
                !source.secondary.isNullOrBlank() || !source.secondaryWords.isNullOrEmpty() -> {
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
                displayTranslation && (!source.translation.isNullOrBlank()
                        || !source.translationWords.isNullOrEmpty()) -> {
                    text = source.translation
                    words = wordBuilder.build(source, source.translation, source.translationWords)
                    metadata = lyricMetadataOf("translation" to "true")
                    generated = words !== source.translationWords
                }
                displayRoma -> {
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
