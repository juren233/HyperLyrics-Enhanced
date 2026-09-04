/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandExpandedMediaBionicsProfileTest {
    @Test
    fun `keeps the binary verified MiBackgroundStyle class name`() {
        assertEquals(
            "miui.systemui.util.MiBackgroundStyle",
            IslandExpandedMediaBionicsProfile.MI_BACKGROUND_STYLE_CLASS,
        )
    }

    @Test
    fun `recognizes the OS4 clear bionics material descriptor`() {
        assertTrue(
            IslandExpandedMediaBionicsProfile.isClearBionicsMaterial(
                name = IslandExpandedMediaBionicsProfile.CLEAR_BIONICS_MATERIAL_METHOD,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf(View::class.java.name),
            ),
        )
    }

    @Test
    fun `rejects decompiler-only aliases and wrong signatures`() {
        assertFalse(
            IslandExpandedMediaBionicsProfile.isClearBionicsMaterial(
                name = "clearBionicMaterial",
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf(View::class.java.name),
            ),
        )
        assertFalse(
            IslandExpandedMediaBionicsProfile.isClearBionicsMaterial(
                name = IslandExpandedMediaBionicsProfile.CLEAR_BIONICS_MATERIAL_METHOD,
                returnTypeName = Boolean::class.java.name,
                parameterTypeNames = listOf(View::class.java.name),
            ),
        )
        assertFalse(
            IslandExpandedMediaBionicsProfile.isClearBionicsMaterial(
                name = IslandExpandedMediaBionicsProfile.CLEAR_BIONICS_MATERIAL_METHOD,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf(
                    "com.miui.systemui.util.MiBackgroundStyle",
                    View::class.java.name,
                ),
            ),
        )
        assertFalse(
            IslandExpandedMediaBionicsProfile.isClearBionicsMaterial(
                name = IslandExpandedMediaBionicsProfile.CLEAR_BIONICS_MATERIAL_METHOD,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = emptyList(),
            ),
        )
    }
}
