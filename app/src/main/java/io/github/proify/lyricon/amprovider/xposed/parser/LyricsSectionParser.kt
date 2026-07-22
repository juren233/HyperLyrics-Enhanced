/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplaceManualRangeWithIndicesCalls")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.model.LyricLine
import io.github.proify.lyricon.amprovider.xposed.model.LyricSection

object LyricsSectionParser {

    fun parserSectionVector(any: Any): MutableList<LyricSection> {
        val sections = mutableListOf<LyricSection>()
        val size = callMethod(any, "size") as Long
        for (i in 0..<size) {
            val sectionPtr = callMethod(any, "get", i) ?: continue
            val sectionNative = callMethod(sectionPtr, "get") ?: continue
            sections.add(parserSectionNative(sectionNative))
        }
        return sections
    }

    private fun parserSectionNative(any: Any): LyricSection {
        val section = LyricSection()
        LyricsTimingParser.parser(section, any)

        val lines = callMethod(any, "getLines")
        lines?.let { section.lines = LyricsLineParser.parser(it) }
        return section
    }

    fun MutableList<LyricSection>.mergeLyrics(): MutableList<LyricLine> =
        this.flatMap { it.lines }.toMutableList()
}