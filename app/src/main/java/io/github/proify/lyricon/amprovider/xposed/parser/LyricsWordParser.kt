/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplaceManualRangeWithIndicesCalls")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.model.LyricWord

object LyricsWordParser {

    fun parser(any: Any): MutableList<LyricWord> {
        val words = mutableListOf<LyricWord>()
        val size = callMethod(any, "size") as? Long ?: 0
        for (i in 0..<size) {
            val ptr: Any = callMethod(any, "get", i) ?: continue
            val wordNative = callMethod(ptr, "get") ?: continue
            words.add(parserWordNative(wordNative))
        }
        return words
    }

    private fun parserWordNative(o: Any): LyricWord {
        val word = LyricWord()
        LyricsTimingParser.parser(word, o)
        word.text = callMethod(o, "getHtmlLineText") as? String
        return word
    }
}