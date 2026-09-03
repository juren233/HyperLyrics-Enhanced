/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.graphics.Bitmap
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Runtime descriptors for the Dynamic Island music-wave color input path. */
internal object IslandMusicWaveMethodProfile {
    const val LEGACY_COLOR_METHOD = "setLottieColor"
    const val OS4_COLOR_METHOD = "getLottieColor"
    const val OS4_COLOR_RETURN_TYPE = "H0.f"

    fun isLegacyColorMethod(method: Method): Boolean {
        return method.name == LEGACY_COLOR_METHOD &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(Bitmap::class.java))
    }

    fun isOs4ColorMethod(method: Method): Boolean {
        return method.name == OS4_COLOR_METHOD &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(Bitmap::class.java)) &&
            method.returnType.name == OS4_COLOR_RETURN_TYPE
    }

    internal fun isOs4ColorMethod(
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean = false,
    ): Boolean {
        return name == OS4_COLOR_METHOD &&
            !isStatic &&
            returnTypeName == OS4_COLOR_RETURN_TYPE &&
            parameterTypeNames == listOf(Bitmap::class.java.name)
    }
}
