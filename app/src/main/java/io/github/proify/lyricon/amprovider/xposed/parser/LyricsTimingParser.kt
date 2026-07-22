/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.model.LyricTiming

object LyricsTimingParser {

    fun parser(timing: LyricTiming, any: Any) {
        timing.agent = callMethod(any, "getAgent") as? String
        timing.begin = callMethod(any, "getBegin") as? Int ?: 0
        timing.end = callMethod(any, "getEnd") as? Int ?: 0
        timing.duration = callMethod(any, "getDuration") as? Int ?: 0
    }
}