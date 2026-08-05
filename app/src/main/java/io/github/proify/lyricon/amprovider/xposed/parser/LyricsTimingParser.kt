/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.model.LyricTiming

object LyricsTimingParser {

    internal fun parser(timing: LyricTiming, any: Any, access: AppleLyricsParserAccess) {
        timing.agent = access.call(
            any,
            AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_METHOD,
        ) as? String
        timing.begin = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)
            as? Int ?: 0
        timing.end = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD)
            as? Int ?: 0
        timing.duration = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_DURATION_METHOD)
            as? Int ?: 0
    }
}
