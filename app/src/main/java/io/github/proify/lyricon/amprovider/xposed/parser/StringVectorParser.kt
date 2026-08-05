/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember

object StringVectorParser {

    internal fun parserStringVectorNative(
        any: Any,
        access: AppleLyricsParserAccess,
    ): MutableList<String> {
        val size = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD)
            as Long
        return (0 until size.toInt()).map { i ->
            access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD, i) as String
        }.toMutableList()
    }
}
