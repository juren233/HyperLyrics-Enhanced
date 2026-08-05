/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplaceManualRangeWithIndicesCalls")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.model.LyricWord

object LyricsWordParser {

    internal fun parser(any: Any, access: AppleLyricsParserAccess): MutableList<LyricWord> {
        val words = mutableListOf<LyricWord>()
        val size = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD)
            as? Long ?: 0
        for (i in 0..<size) {
            val ptr: Any = access.call(
                any,
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                i,
            ) ?: continue
            val wordNative = access.call(
                ptr,
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
            ) ?: continue
            words.add(parserWordNative(wordNative, access))
        }
        return words
    }

    private fun parserWordNative(o: Any, access: AppleLyricsParserAccess): LyricWord {
        val word = LyricWord()
        LyricsTimingParser.parser(word, o, access)
        word.text = access.call(o, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
            as? String
        return word
    }
}
