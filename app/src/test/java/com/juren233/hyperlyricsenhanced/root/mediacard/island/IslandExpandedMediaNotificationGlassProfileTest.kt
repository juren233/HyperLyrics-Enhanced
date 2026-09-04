/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandExpandedMediaNotificationGlassProfileTest {
    @Test
    fun `keeps the original DEX binary class names`() {
        assertEquals(
            "com.miui.systemui.util.MiBlurCompat",
            IslandExpandedMediaNotificationGlassProfile.MI_BLUR_COMPAT_CLASS,
        )
        assertEquals(
            "com.miui.systemui.util.MiGlassCompat",
            IslandExpandedMediaNotificationGlassProfile.MI_GLASS_COMPAT_CLASS,
        )
        assertFalse(
            IslandExpandedMediaNotificationGlassProfile.MI_GLASS_COMPAT_CLASS ==
                "miui.systemui.util.MiGlassCompat",
        )
        assertFalse(
            IslandExpandedMediaNotificationGlassProfile.MI_BLUR_COMPAT_CLASS ==
                "miui.systemui.util.MiBlurCompat",
        )
    }

    @Test
    fun `recognizes the exact notification blend sequence descriptors`() {
        assertTrue(
            IslandExpandedMediaNotificationGlassProfile.isGetBackgroundBlurOpened(
                name = "getBackgroundBlurOpened",
                isStatic = true,
                returnTypeName = Boolean::class.javaPrimitiveType!!.name,
                parameterTypeNames = listOf("android.content.Context"),
            ),
        )
        // The same class also declares a Configuration overload; only (Context)Z matches.
        assertFalse(
            IslandExpandedMediaNotificationGlassProfile.isGetBackgroundBlurOpened(
                name = "getBackgroundBlurOpened",
                isStatic = true,
                returnTypeName = Boolean::class.javaPrimitiveType!!.name,
                parameterTypeNames = listOf("android.content.res.Configuration"),
            ),
        )
        assertTrue(
            IslandExpandedMediaNotificationGlassProfile.isSetMiViewBlurMode(
                name = "setMiViewBlurModeCompat",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("int", "android.view.View"),
            ),
        )
        // The plugin variant reverses the arguments; it must not match.
        assertFalse(
            IslandExpandedMediaNotificationGlassProfile.isSetMiViewBlurMode(
                name = "setMiViewBlurModeCompat",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("android.view.View", "int"),
            ),
        )
        assertTrue(
            IslandExpandedMediaNotificationGlassProfile.isClearMiBackgroundBlendColor(
                name = "clearMiBackgroundBlendColorCompat",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("android.view.View"),
            ),
        )
        assertTrue(
            IslandExpandedMediaNotificationGlassProfile.isSetMiBackgroundBlendColors(
                name = "setMiBackgroundBlendColors\$default",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("android.view.View", IntArray::class.java.name),
            ),
        )
        // Sibling bridges with the same prefix must not match.
        assertFalse(
            IslandExpandedMediaNotificationGlassProfile.isSetMiBackgroundBlendColors(
                name = "setMiBackgroundBlendColorsNew\$default",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("android.view.View", IntArray::class.java.name),
            ),
        )
        assertFalse(
            IslandExpandedMediaNotificationGlassProfile.isSetMiBackgroundBlendColors(
                name = "setMiBackgroundBlendColors",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf(
                    "android.view.View",
                    IntArray::class.java.name,
                    "float",
                ),
            ),
        )
    }

    @Test
    fun `recognizes the exact material and glass descriptors`() {
        assertTrue(
            IslandExpandedMediaNotificationGlassProfile.isSetMiViewMaterialType(
                name = "setMiViewMaterialTypeCompat",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("int", "android.view.View"),
            ),
        )
        assertTrue(
            IslandExpandedMediaNotificationGlassProfile.isSetMiGlass(
                name = "setMiGlassCompat",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("android.view.View", FloatArray::class.java.name),
            ),
        )
        assertFalse(
            IslandExpandedMediaNotificationGlassProfile.isSetMiViewMaterialType(
                name = "setMiViewMaterialTypeCompat",
                isStatic = false,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("android.view.View", "int"),
            ),
        )
        assertFalse(
            IslandExpandedMediaNotificationGlassProfile.isSetMiGlass(
                name = "setMiGlass",
                isStatic = true,
                returnTypeName = Void.TYPE.name,
                parameterTypeNames = listOf("android.view.View", FloatArray::class.java.name),
            ),
        )
    }

    @Test
    fun `keeps the complete MediaViewGlassEffect parameter array`() {
        val expected = floatArrayOf(
            0.67f, 0.16f, 0.09f, 0.0f, 0.24f, 1.4f, -0.02f,
            0.3f, 0.6f, 1.0f, 0.03f, 1.0f, 1.0f, 1.0f,
            0.1f, 0.2f, 0.3f, 1.0f, 1.0f, 72.0f, 3.8f,
            80.0f, 800.0f, 1.2f, 1.0f, -0.4f, 0.6f, -0.8f,
            1.4f, 0.7f, 0.8f, 1.15f, 4.0f, 2.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        )
        val actual = IslandExpandedMediaNotificationGlassProfile.defaultGlassParams()

        assertEquals(42, actual.size)
        assertArrayEquals(expected, actual, 0.0f)
    }
}
