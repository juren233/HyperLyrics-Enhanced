package com.juren233.hyperlyricsenhanced.ui.page

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.juren233.hyperlyricsenhanced.ui.utils.MonetPalette
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页强调色色块 == 选中该强调色后 App 主题实际的主色（MiuixTheme.colorScheme.primary）。
 * 「实际主色」按 MiuixThemeWrapper 的做法得到：存储值 → ThemeUtils.themeController → ThemeUtils.AppMiuixTheme。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AccentSwatchColorsTest {

    private class Captured(val swatches: List<Color>, val themePrimaries: List<Color>)

    /** 在一个真实组合里同时取色块颜色和每个强调色下主题的 primary。 */
    private fun capture(themeMode: Int): Captured {
        val monetMode = ThemeModeSelection.combine(ThemeModeSelection.brightnessOf(themeMode), monet = true)
        var swatches: List<Color>? = null
        val primaries = arrayOfNulls<Color>(MonetPalette.keyColors.size)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            swatches = accentSwatchColors(themeMode)
            MonetPalette.keyColors.indices.forEach { index ->
                val controller = remember(monetMode, index) { ThemeUtils.themeController(monetMode, index) }
                ThemeUtils.AppMiuixTheme(controller, index) { primaries[index] = MiuixTheme.colorScheme.primary }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        return Captured(assertNotNull(swatches).let { swatches!! }, primaries.map { assertNotNull(it); it!! })
    }

    private fun assertSwatchesMatchTheme(themeMode: Int) {
        val captured = capture(themeMode)
        assertEquals(MonetPalette.keyColors.size, captured.swatches.size)
        captured.swatches.forEachIndexed { index, swatch ->
            assertEquals("themeMode $themeMode, accent $index", captured.themePrimaries[index], swatch)
        }
    }

    @Test
    fun lightMode_everySwatchEqualsThemePrimary() = assertSwatchesMatchTheme(themeMode = 4)

    @Test
    fun darkMode_everySwatchEqualsThemePrimary() = assertSwatchesMatchTheme(themeMode = 5)

    @Test
    fun followSystemMode_everySwatchEqualsThemePrimary() = assertSwatchesMatchTheme(themeMode = 3)

    @Test
    fun monetOffStoredValue_swatchesShowWhatMonetWouldUseInSameBrightness() {
        // 莫奈关闭时（1 浅色 / 2 深色）色块预览的是打开莫奈后同一深浅模式下的主色。
        assertEquals(capture(4).themePrimaries, capture(1).swatches)
        assertEquals(capture(5).themePrimaries, capture(2).swatches)
    }

    @Test
    fun fixedAccentPrimaryIsNotTheRawPaletteValue() {
        // 旧画法的色块直接画色板值（种子色），这里确认主题主色与种子色确实不同，旧画法必然对不上。
        listOf(4, 5).forEach { themeMode ->
            val primaries = capture(themeMode).themePrimaries
            (1 until MonetPalette.keyColors.size).forEach { index ->
                assertNotEquals("themeMode $themeMode, accent $index", MonetPalette.keyColorOf(index), primaries[index])
            }
        }
    }
}
