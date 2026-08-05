/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplaceManualRangeWithIndicesCalls")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.model.LyricLine

object LyricsLineParser {

    internal fun parser(any: Any, access: AppleLyricsParserAccess): MutableList<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val size = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD)
            as? Long ?: 0
        for (i in 0..<size) {
            val ptr = access.call(
                any,
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                i,
            ) ?: continue
            val lineNative = access.call(
                ptr,
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
            ) ?: continue
            lines.add(parserLyricsLineNative(lineNative, access))
        }
        return lines
    }

    private fun parserLyricsLineNative(o: Any, access: AppleLyricsParserAccess): LyricLine {
        val line = LyricLine()
        LyricsTimingParser.parser(line, o, access)

        line.htmlLineText = access.call(
            o,
            AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD,
        ) as? String
        val words = access.call(o, AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD)
        words?.let { line.words = LyricsWordParser.parser(it, access) }
        line.htmlTranslationLineText = access.call(
            o,
            AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD,
        ) as? String

        val backgroundWords = access.call(
            o,
            AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
            false,
        )
        backgroundWords?.let { line.backgroundWords = LyricsWordParser.parser(it, access) }

        line.htmlBackgroundVocalsLineText = access.call(
            o,
            AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD,
        ) as? String
        line.htmlTranslatedBackgroundVocalsLineText = access.call(
            o,
            AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATED_BACKGROUND_TEXT_METHOD,
        ) as? String

        line.htmlPronunciationLineText = access.call(
            o,
            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
        ) as? String
        line.htmlPronunciationBackgroundVocalsLineText = access.call(
            o,
            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD,
        ) as? String
        return line
    }
}
