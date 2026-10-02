package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandSystemFontWeightTest {
    @Test
    fun `runtime targets retain the verified DEX names without decompiler prefixes`() {
        val targets = IslandSystemFontWeightTargets
        assertEquals("miui.util.font.FontSettings", targets.SETTINGS)
        assertEquals("miui.util.font.FontType", targets.TYPE)
        assertEquals("miui.util.font.FontWght", targets.WEIGHT)
        assertEquals("miui.util.TypefaceHelper", targets.HELPER)
        assertEquals("MIUI", targets.MIUI)
        assertEquals("loadFontSetting", targets.LOAD)
        assertEquals("getWeightIdx", targets.INDEX)
        assertEquals("getScaleWght", targets.SCALE)
        assertEquals("getFontPath", targets.PATH)
        assertEquals("sFontScale", targets.SCALE_FIELD)
        assertEquals("android.content.res.MiuiConfiguration", targets.MIUI_CONFIGURATION)
        assertEquals("extraConfig", targets.EXTRA_CONFIG)
        assertEquals("extraData", targets.EXTRA_DATA)
        assertEquals("key_var_font_scale", targets.CONFIG_SCALE)
    }

    @Test
    fun `custom font axis follows system scale without copying OEM MiSans coordinates`() {
        assertEquals(450, IslandSystemFontWeight.State().semanticWeight)
        assertEquals(350, IslandSystemFontWeight.State(scale = 0).standardWeight)
        assertEquals(450, IslandSystemFontWeight.State(scale = 50).standardWeight)
        assertEquals(502, IslandSystemFontWeight.State(scale = 76).standardWeight)
        assertEquals(550, IslandSystemFontWeight.State(scale = 100).standardWeight)
        assertEquals(750, IslandSystemFontWeight.State(adjustment = 300).standardWeight)
    }

    @Test
    fun `configuration update wins over older database values including zero`() {
        for (scale in listOf(0, 100, 76)) {
            assertEquals(scale, IslandSystemFontWeight.resolveScale(scale, 74, 74))
        }
    }

    @Test
    fun `observer reads the namespace that changed instead of a stale mirror`() {
        assertEquals(100, IslandSystemFontWeight.resolveScale(null, 74, 100, preferGlobal = true))
        assertEquals(0, IslandSystemFontWeight.resolveScale(null, 0, 100))
        assertEquals(50, IslandSystemFontWeight.resolveScale(null, null, null))
        assertEquals(50, IslandSystemFontWeight.resolveScale(null, null, null, preferGlobal = true))
        assertEquals(76, IslandSystemFontWeight.resolveScale(null, null, 76))
    }

    @Test
    fun `new scale maps immediately even when font service load still returns old settings`() {
        var nativeScale = 74
        val results = listOf(0, 100, 76).map { next ->
            val mapped = IslandSystemFontWeight.withScaleSnapshot(
                next, { nativeScale }, { nativeScale = it }, prepare = { nativeScale = 50 },
            ) { nativeScale }
            assertEquals(74, nativeScale)
            mapped
        }
        assertEquals(listOf(0, 100, 76), results)
    }

    @Test
    fun `failed font mapping restores native state`() {
        var nativeScale = 74
        runCatching {
            IslandSystemFontWeight.withScaleSnapshot(100, { nativeScale }, { nativeScale = it }) {
                error("font unavailable")
            }
        }
        assertEquals(74, nativeScale)
    }
}
