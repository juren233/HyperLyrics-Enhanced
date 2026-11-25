/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主题拆成「深浅模式 + 莫奈取色」后，存储值 0–5 的含义不变、来回换算不丢值。 */
class ThemeModeSelectionTest {

    @Test
    fun storedValuesSplitIntoBrightnessAndMonet() {
        val expected = listOf(0 to false, 1 to false, 2 to false, 0 to true, 1 to true, 2 to true)
        expected.forEachIndexed { stored, (brightness, monet) ->
            assertEquals("brightness of $stored", brightness, ThemeModeSelection.brightnessOf(stored))
            assertEquals("monet of $stored", monet, ThemeModeSelection.monetOf(stored))
        }
    }

    @Test
    fun splittingThenCombiningKeepsEveryStoredValue() {
        for (stored in 0..5) {
            val combined = ThemeModeSelection.combine(
                ThemeModeSelection.brightnessOf(stored),
                ThemeModeSelection.monetOf(stored),
            )
            assertEquals(stored, combined)
        }
    }

    @Test
    fun turningMonetOnOrOffKeepsBrightness() {
        // 深色 → 打开莫奈 → 深色（莫奈）；再关掉 → 深色。
        assertEquals(5, ThemeModeSelection.combine(ThemeModeSelection.brightnessOf(2), monet = true))
        assertEquals(2, ThemeModeSelection.combine(ThemeModeSelection.brightnessOf(5), monet = false))
        // 浅色（莫奈）→ 改成跟随系统，莫奈保持开启。
        assertEquals(3, ThemeModeSelection.combine(ThemeModeSelection.BRIGHTNESS_SYSTEM, ThemeModeSelection.monetOf(4)))
    }

    @Test
    fun outOfRangeStoredValueShowsAsFollowSystemWithoutMonet() {
        for (stored in listOf(-1, 6, 42)) {
            assertEquals(ThemeModeSelection.BRIGHTNESS_SYSTEM, ThemeModeSelection.brightnessOf(stored))
            assertFalse(ThemeModeSelection.monetOf(stored))
        }
        assertTrue(ThemeModeSelection.monetOf(3))
    }
}
