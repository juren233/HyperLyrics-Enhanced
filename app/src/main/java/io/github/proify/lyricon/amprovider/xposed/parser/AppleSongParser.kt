/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.amprovider.xposed.parser.LyricsSectionParser.mergeLyrics

object AppleSongParser {

    fun parser(songNative: Any): AppleSong = AppleSong().apply {
        adamId = callMethod(songNative, "getAdamId").toString()

        callMethod(songNative, "getAgents")?.let {
            agents = LyricsAgentParser.parserAgentVector(it)
        }

        duration = callMethod(songNative, "getDuration") as? Int ?: 0

        // language = get(o, "getLanguage") as? String
        // lyricsId = get(o, "getLyricsId") as? String
        // queueId = get(o, "getQueueId") as? Long ?: 0L

        val sections = callMethod(songNative, "getSections")
        if (sections != null) {
            lyrics = LyricsSectionParser.parserSectionVector(sections).mergeLyrics()
        }

        // timing = get(o, "getTiming") as? Long ?: 0L
        // timingName = get(o, "getAvailableTiming")?.name()
        // translation = get(o, "getTranslation") as? String
        // translationLanguages = StringVectorParser.parserStringVectorNative(get(o, "getTranslationLanguages"))

        adamId?.let {
            MediaMetadataCache.getMetadataById(it)
                ?.let { metadata ->
                    name = metadata.title
                    artist = metadata.artist
                }
        }
    }
}