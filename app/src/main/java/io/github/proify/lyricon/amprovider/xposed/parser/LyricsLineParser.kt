/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplaceManualRangeWithIndicesCalls")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.model.LyricLine

object LyricsLineParser {

    fun parser(any: Any): MutableList<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val size = callMethod(any, "size") as? Long ?: 0
        for (i in 0..<size) {
            val ptr = callMethod(any, "get", i) ?: continue
            val lineNative = callMethod(ptr, "get") ?: continue
            lines.add(parserLyricsLineNative(lineNative))
        }
        return lines
    }

    private fun parserLyricsLineNative(o: Any): LyricLine {
        val line = LyricLine()
        LyricsTimingParser.parser(line, o)

        line.htmlLineText = callMethod(o, "getHtmlLineText") as? String
        val words = callMethod(o, "getWords")
        words?.let { line.words = LyricsWordParser.parser(it) }
        line.htmlTranslationLineText = callMethod(o, "getHtmlTranslationLineText") as? String

        val backgroundWords = callMethod(o, "getBackgroundWords", false)
        backgroundWords?.let { line.backgroundWords = LyricsWordParser.parser(it) }

        line.htmlBackgroundVocalsLineText =
            callMethod(o, "getHtmlBackgroundVocalsLineText") as? String
        line.htmlTranslatedBackgroundVocalsLineText =
            callMethod(o, "getHtmlTranslatedBackgroundVocalsLineText") as? String

        line.htmlPronunciationLineText = callMethod(o, "getHtmlPronunciationLineText") as? String
        line.htmlPronunciationBackgroundVocalsLineText =
            callMethod(o, "getHtmlPronunciationBackgroundVocalsLineText") as? String

        //ObjectUtils.print(o, prefix = arrayOf("get"))
        return line
    }
}