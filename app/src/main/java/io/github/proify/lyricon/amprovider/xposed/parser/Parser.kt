/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember

/** Runtime-member access for the native lyric model parser. */
internal class AppleLyricsParserAccess private constructor(
    private val target: AppleMusicHookTarget,
) {
    fun call(any: Any, member: AppleMusicRuntimeMember, vararg args: Any?): Any? =
        runCatching {
            AppleReflection.call(any, target.runtimeMemberName(member), *args)
        }.getOrNull()

    companion object {
        fun from(resolver: AppleMusicHookResolver): AppleLyricsParserAccess =
            AppleLyricsParserAccess(
                resolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).target,
            )
    }
}
