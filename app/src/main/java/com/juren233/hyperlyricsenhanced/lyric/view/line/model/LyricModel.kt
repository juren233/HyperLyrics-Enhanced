/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line.model

import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.view.line.GeometryDiagnostics
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.juren233.hyperlyricsenhanced.lyric.view.line.MixedTypefaceText
import com.juren233.hyperlyricsenhanced.lyric.model.LyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.LyricMetadata
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.extensions.TimingNavigator

data class LyricModel(
    val begin: Long = 0,
    val end: Long = 0,
    val duration: Long = 0,
    val text: String,
    val words: List<WordModel>,
    val isAlignedRight: Boolean = false,
    var metadata: LyricMetadata? = null,
) {
    var width: Float = 0f
        private set

    val wordText: String by lazy { words.toText() }
    val wordTimingNavigator: TimingNavigator<WordModel> by lazy { TimingNavigator(words.toTypedArray()) }
    val isPlainText: Boolean = words.isEmpty()

    fun updateSizes(
        paint: Paint,
        typefaceSelector: ((Char) -> Typeface)? = null,
        preparedTextWidth: Float? = null,
    ) = updateSizesDiagnosed(paint, typefaceSelector, preparedTextWidth, 0)

    internal fun updateSizesDiagnosed(
        paint: Paint,
        typefaceSelector: ((Char) -> Typeface)? = null,
        preparedTextWidth: Float? = null,
        diagnosticReason: Int,
    ) {
        val sampled = BuildConfig.DEBUG && GeometryDiagnostics.begin(
            this, diagnosticReason, words.size, preparedTextWidth != null
        )
        var failed = true
        try {
            val wholeStart = if (sampled) System.nanoTime() else 0L
            width = preparedTextWidth ?: measureLyricTextWidth(paint, text, typefaceSelector)
            if (sampled) GeometryDiagnostics.whole(System.nanoTime() - wholeStart)
            var previous: WordModel? = null
            words.forEach { word ->
                word.updateSizes(previous, paint, typefaceSelector)
                previous = word
            }
            failed = false
        } finally {
            if (BuildConfig.DEBUG) GeometryDiagnostics.end(failed)
        }
    }

}

/** The same natural text width for live drawing and background width preparation. */
internal fun measureLyricTextWidth(paint: Paint, text: String, selector: ((Char) -> Typeface)?): Float {
    if (selector != null) return MixedTypefaceText.measureText(paint, text, selector)
    val advance = paint.measureText(text)
    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    return maxOf(advance, bounds.right.toFloat())
}

internal fun emptyLyricModel(): LyricModel = LyricModel(
    words = emptyList(),
    text = ""
)

/**
 * 将 LyricLine 转换为 LyricModel
 */
internal fun LyricLine.createModel(): LyricModel = LyricModel(
    begin = begin,
    end = end,
    duration = duration,
    text = text.orEmpty(),
    words = words?.toWordModels() ?: emptyList(),
    isAlignedRight = isAlignedRight,
    metadata = metadata
)

/**
 * 将 LyricWord 列表转换为 WordModel 列表，并建立前后引用关系
 */
private fun List<LyricWord>.toWordModels(): List<WordModel> {
    val models = mutableListOf<WordModel>()
    var previousModel: WordModel? = null

    forEach { word ->
        val model = WordModel(
            begin = word.begin,
            end = word.end,
            duration = word.duration,
            text = word.text.orEmpty(),
            metadata = word.metadata
        )

        model.previous = previousModel
        previousModel?.next = model

        models.add(model)
        previousModel = model
    }
    return models
}
