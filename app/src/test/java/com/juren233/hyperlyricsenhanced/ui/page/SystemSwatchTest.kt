package com.juren233.hyperlyricsenhanced.ui.page

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.juren233.hyperlyricsenhanced.ui.page.MonetThemeTestSupport.appThemeTokens
import com.juren233.hyperlyricsenhanced.ui.page.MonetThemeTestSupport.compose
import com.juren233.hyperlyricsenhanced.ui.utils.MonetPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * 「系统」色块不画四分格，改成壁纸主色底 + 半透明遮罩（深色压暗、浅色提亮）+ 中间色盘图标；
 * 选中时不显示对勾，只靠外圈表示选中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemSwatchTest {

    @Test
    fun baseIsThePrimaryTheAppUsesForSystem_maskDarkensInDarkAndLightensInLight() {
        for ((mode, dark) in listOf(5 to true, 2 to true, 4 to false, 1 to false, 3 to false)) {
            val colors = compose { systemSwatchColors(mode) }
            val monetMode = if (dark) 5 else 4
            assertEquals("themeMode=$mode 底色", appThemeTokens(monetMode, 0).getValue("Primary"), colors.base)
            assertEquals("themeMode=$mode 图标", colors.base, colors.icon)
            val expectedMask = (if (dark) Color.Black else Color.White).copy(alpha = SYSTEM_SWATCH_MASK_ALPHA)
            assertEquals("themeMode=$mode 遮罩", expectedMask, colors.mask)
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun followSystem_inDarkSystem_usesDarkMask() {
        assertEquals(Color.Black.copy(alpha = SYSTEM_SWATCH_MASK_ALPHA), compose { systemSwatchColors(3) }.mask)
    }

    /** 壁纸主色可能是任何颜色：用每种强调色的主色模拟，图标在遮罩后的底色上都要看得清（≥ 3:1）。 */
    @Test
    fun paletteIconStaysReadableOnMaskedBackground() {
        for ((mode, dark) in listOf(5 to true, 4 to false)) {
            MonetPalette.keyColors.indices.forEach { index ->
                val primary = appThemeTokens(mode, index).getValue("Primary")
                val colors = systemSwatchColors(primary, dark)
                val contrast = ColorUtils.calculateContrast(colors.icon.toArgb(), colors.background.toArgb())
                assertTrue("dark=$dark accent=$index 对比度 $contrast", contrast >= 3.0)
            }
        }
    }

    @Test
    fun systemSwatchShowsPaletteIconNeverCheck() {
        assertEquals(SwatchMark.PaletteIcon, swatchMark(isSystem = true, selected = true))
        assertEquals(SwatchMark.PaletteIcon, swatchMark(isSystem = true, selected = false))
        assertEquals(SwatchMark.Check, swatchMark(isSystem = false, selected = true))
        assertEquals(SwatchMark.None, swatchMark(isSystem = false, selected = false))
    }

    private class Rendered(val pixels: IntArray, val size: Int)

    /** 真画一个色块，动画走完后取中间圆形区域的像素。 */
    private fun render(color: Color, system: SystemSwatchColors?, selected: Boolean): Rendered {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val state = mutableStateOf(!selected)
        activity.setContent { AccentSwatch(color = color, system = system, name = "", selected = state.value, onClick = {}) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        state.value = selected
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val root = activity.window.decorView
        val composeView = generateSequence(listOf<View>(root)) { level ->
            level.flatMap { v -> if (v is android.view.ViewGroup) (0 until v.childCount).map { v.getChildAt(it) } else emptyList() }
                .ifEmpty { null }
        }.flatten().first { it.javaClass.name.contains("AndroidComposeView") }
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        // 色块 34dp 在左上角；只取半径 11dp 以内的像素：选中时内圆缩到 26dp（半径 13dp），避开外圈和内圆边缘。
        val density = activity.resources.displayMetrics.density
        val center = 17 * density
        val radius = 11 * density
        val out = ArrayList<Int>()
        for (y in 0 until (34 * density).toInt()) for (x in 0 until (34 * density).toInt()) {
            val dx = x + 0.5f - center
            val dy = y + 0.5f - center
            if (dx * dx + dy * dy <= radius * radius) out += bitmap.getPixel(x, y)
        }
        return Rendered(out.toIntArray(), out.size)
    }

    @Test
    fun selectedSystemSwatch_looksSameInsideAsUnselected_andDrawsTheIconOnTheMask() {
        val colors = systemSwatchColors(Color(0xFFB3C6F9), dark = true)
        val selected = render(colors.base, colors, selected = true)
        val unselected = render(colors.base, colors, selected = false)
        assertTrue("选中的「系统」色块中间不应多出对勾", selected.pixels.contentEquals(unselected.pixels))
        val background = colors.background.toArgb()
        val icon = colors.icon.toArgb()
        fun near(a: Int, b: Int) = listOf(16, 8, 0).all { kotlin.math.abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) <= 6 }
        assertTrue("遮罩后的底色应可见", selected.pixels.count { near(it, background) } > selected.pixels.size / 4)
        assertTrue("色盘图标应画出来", selected.pixels.count { near(it, icon) } > selected.pixels.size / 20)
    }

    /** 对照：普通色块选中后中间确实会出现对勾，说明上面的比较能发现对勾。 */
    @Test
    fun selectedFixedSwatch_drawsCheck() {
        val red = Color(0xFFF78E80)
        val selected = render(red, null, selected = true)
        val unselected = render(red, null, selected = false)
        assertNotEquals(selected.pixels.toList(), unselected.pixels.toList())
        assertTrue(selected.pixels.any { it == android.graphics.Color.WHITE })
    }
}
