/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandMusicWaveMethodProfileTest {
    @Test
    fun `recognizes the OS4 bitmap color descriptor`() {
        assertTrue(
            IslandMusicWaveMethodProfile.isOs4ColorMethod(
                name = IslandMusicWaveMethodProfile.OS4_COLOR_METHOD,
                returnTypeName = IslandMusicWaveMethodProfile.OS4_COLOR_RETURN_TYPE,
                parameterTypeNames = listOf(Bitmap::class.java.name),
            )
        )
    }

    @Test
    fun `rejects decompiler aliases and wrong OS4 descriptors`() {
        assertFalse(
            IslandMusicWaveMethodProfile.isOs4ColorMethod(
                name = IslandMusicWaveMethodProfile.OS4_COLOR_METHOD,
                returnTypeName = "kotlin.Pair",
                parameterTypeNames = listOf(Bitmap::class.java.name),
            )
        )
        assertFalse(
            IslandMusicWaveMethodProfile.isOs4ColorMethod(
                name = IslandMusicWaveMethodProfile.OS4_COLOR_METHOD,
                returnTypeName = IslandMusicWaveMethodProfile.OS4_COLOR_RETURN_TYPE,
                parameterTypeNames = listOf("android.graphics.drawable.Drawable"),
            )
        )
        assertFalse(
            IslandMusicWaveMethodProfile.isOs4ColorMethod(
                name = IslandMusicWaveMethodProfile.OS4_COLOR_METHOD,
                returnTypeName = IslandMusicWaveMethodProfile.OS4_COLOR_RETURN_TYPE,
                parameterTypeNames = listOf(Bitmap::class.java.name),
                isStatic = true,
            )
        )
    }
}
