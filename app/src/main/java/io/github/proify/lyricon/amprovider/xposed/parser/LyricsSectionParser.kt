/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplaceManualRangeWithIndicesCalls")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.model.LyricLine
import io.github.proify.lyricon.amprovider.xposed.model.LyricSection

object LyricsSectionParser {

    internal fun parserSectionVector(
        any: Any,
        access: AppleLyricsParserAccess,
    ): MutableList<LyricSection> {
        val sections = mutableListOf<LyricSection>()
        val size = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD)
            as Long
        for (i in 0..<size) {
            val sectionPtr = access.call(
                any,
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                i,
            ) ?: continue
            val sectionNative = access.call(
                sectionPtr,
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
            ) ?: continue
            sections.add(parserSectionNative(sectionNative, access))
        }
        return sections
    }

    private fun parserSectionNative(any: Any, access: AppleLyricsParserAccess): LyricSection {
        val section = LyricSection()
        LyricsTimingParser.parser(section, any, access)

        val lines = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD)
        lines?.let { section.lines = LyricsLineParser.parser(it, access) }
        return section
    }

    fun MutableList<LyricSection>.mergeLyrics(): MutableList<LyricLine> =
        this.flatMap { it.lines }.toMutableList()
}
