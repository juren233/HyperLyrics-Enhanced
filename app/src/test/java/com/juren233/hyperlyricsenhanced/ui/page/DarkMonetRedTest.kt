package com.juren233.hyperlyricsenhanced.ui.page

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.juren233.hyperlyricsenhanced.ui.page.MonetThemeTestSupport.appThemeTokens
import com.juren233.hyperlyricsenhanced.ui.page.MonetThemeTestSupport.miuixTokens
import com.juren233.hyperlyricsenhanced.ui.utils.MonetAccentTuning
import com.juren233.hyperlyricsenhanced.ui.utils.MonetPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真机上深色 + 莫奈 + 红色时主色是 #FFB4A9（TonalSpot 色调 80，彩度只剩约 29），看着像粉色。
 * 只在这一种组合下换主色，其余强调色、浅色、莫奈关闭都保持 miuix 原值。
 * 试过 #FF7262，真机上太艳；最终取 #F78E80，介于原值和 #FF7262 之间。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DarkMonetRedTest {
    private val red = MonetAccentTuning.RED_INDEX
    private val expected = Color(0xFFF78E80)

    @Test
    fun darkMonetRed_appThemePrimaryIsF78E80() {
        // 修改前 miuix 原值是 #FFB4A9，确认场景与真机一致。
        assertEquals(Color(0xFFFFB4A9), miuixTokens(5, red).getValue("Primary"))
        assertEquals(expected, appThemeTokens(5, red).getValue("Primary"))
    }

    @Test
    fun darkMonetRed_onlyPrimaryAndSliderKeyPointChange() {
        val raw = miuixTokens(5, red)
        val app = appThemeTokens(5, red)
        val changed = raw.keys.filter { raw[it] != app[it] }.toSet()
        assertEquals(setOf("Primary", "SliderKeyPoint"), changed)
        assertEquals(expected, app.getValue("SliderKeyPoint"))
    }

    @Test
    fun otherAccentsLightModeAndMonetOff_keepMiuixColors() {
        for (mode in listOf(1, 2, 4, 5)) {
            for (index in MonetPalette.keyColors.indices) {
                if (mode == 5 && index == red) continue
                assertEquals("themeMode=$mode accent=$index", miuixTokens(mode, index), appThemeTokens(mode, index))
            }
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun followSystemMonet_inDarkSystem_usesNewRed() {
        assertEquals(expected, appThemeTokens(3, red).getValue("Primary"))
    }

    @Test
    fun followSystemMonet_inLightSystem_keepsMiuixRed() {
        assertEquals(miuixTokens(3, red), appThemeTokens(3, red))
    }

    @Test
    fun newRed_contrastIsEnough() {
        val app = appThemeTokens(5, red)
        fun contrast(a: String, b: String) = ColorUtils.calculateContrast(app.getValue(a).toArgb(), app.getValue(b).toArgb())
        // 主色上的文字（按钮文字、选中对勾等）
        assertTrue("onPrimary/primary ${contrast("OnPrimary", "Primary")}", contrast("OnPrimary", "Primary") >= 4.5)
        // 主色作为开关、强调元素放在页面和卡片上
        assertTrue("primary/surface ${contrast("Primary", "Surface")}", contrast("Primary", "Surface") >= 3.0)
        assertTrue("primary/surfaceContainer ${contrast("Primary", "SurfaceContainer")}", contrast("Primary", "SurfaceContainer") >= 3.0)
        // 主色容器与上面的文字没动，对比度本身达标
        assertTrue(contrast("OnPrimaryContainer", "PrimaryContainer") >= 4.5)
    }

    private val hct = Class.forName("com.materialkolor.hct.Hct")
    private val hctCompanion = hct.getField("Companion").get(null)
    /** (色相, 彩度, 色调) */
    private fun hctOf(c: Color): Triple<Double, Double, Double> {
        val o = hctCompanion.javaClass.getMethod("fromInt", Int::class.javaPrimitiveType).invoke(hctCompanion, c.toArgb())
        return Triple(hct.getMethod("getHue").invoke(o) as Double, hct.getMethod("getChroma").invoke(o) as Double, hct.getMethod("getTone").invoke(o) as Double)
    }

    /** #FF7262 太艳：新红色要比它素、比原来的 #FFB4A9 鲜，同时和深色莫奈粉、橙明显分开。 */
    @Test
    fun newRed_isBetweenOldPinkishRedAndTooVividRed_andDistinctFromPinkAndOrange() {
        val (hue, chroma, tone) = hctOf(appThemeTokens(5, red).getValue("Primary"))
        val (_, tooVividChroma, tooVividTone) = hctOf(Color(0xFFFF7262))
        val (_, oldChroma, oldTone) = hctOf(miuixTokens(5, red).getValue("Primary"))
        assertTrue("彩度 $chroma 应低于 #FF7262 的 $tooVividChroma", chroma < tooVividChroma - 10)
        assertTrue("彩度 $chroma 应高于原值 $oldChroma", chroma > oldChroma + 10)
        assertTrue("色调 $tone 应在 $tooVividTone 和 $oldTone 之间", tone > tooVividTone && tone < oldTone)
        val pink = hctOf(miuixTokens(5, 7).getValue("Primary"))
        val orange = hctOf(miuixTokens(5, 5).getValue("Primary"))
        assertTrue("与粉色色相差 ${hue - pink.first}", hue - pink.first >= 15)
        assertTrue("与粉色彩度差 ${chroma - pink.second}", chroma - pink.second >= 10)
        assertTrue("与橙色色相差 ${orange.first - hue}", orange.first - hue >= 30)
    }
}
