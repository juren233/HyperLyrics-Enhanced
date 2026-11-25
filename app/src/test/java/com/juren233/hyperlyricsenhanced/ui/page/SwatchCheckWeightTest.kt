package com.juren233.hyperlyricsenhanced.ui.page

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.juren233.hyperlyricsenhanced.ui.page.MonetThemeTestSupport.compose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import java.time.Duration

/**
 * Cavan 要色块选中对勾粗一点，用 miuix 官方的粗细档，不自己改路径。
 * Medium 在真机上看不出区别，所以改用最粗的 Demibold。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwatchCheckWeightTest {

    @Test
    fun checkIsMiuixDemiboldWeight_andDefaultOkIsRegular() {
        assertEquals(MiuixIcons.Demibold.Ok, SwatchCheckIcon)
        // 证明之前用的 MiuixIcons.Ok 就是 Regular 档
        assertEquals(MiuixIcons.Regular.Ok, MiuixIcons.Ok)
    }

    /** 白色笔画的像素量（按亮度加权），图标画成 [px]×[px]。 */
    private fun ink(icon: ImageVector, px: Int): Double {
        val painter = compose { rememberVectorPainter(icon) }
        val bitmap = ImageBitmap(px, px)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
            drawRect(Color.Black)
            with(painter) { draw(Size(px.toFloat(), px.toFloat()), colorFilter = ColorFilter.tint(Color.White)) }
        }
        val a = bitmap.asAndroidBitmap()
        var sum = 0L
        for (y in 0 until px) for (x in 0 until px) sum += (a.getPixel(x, y) shr 8) and 0xFF
        return sum / 255.0
    }

    /** 真画一个选中的色块（深色红底、白对勾），数中间对勾的白色像素量。 */
    private fun swatchCheckInk(): Double {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { AccentSwatch(color = Color(0xFFF78E80), system = null, name = "", selected = true, onClick = {}) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val composeView = generateSequence(listOf<View>(activity.window.decorView)) { level ->
            level.flatMap { v -> if (v is android.view.ViewGroup) (0 until v.childCount).map { v.getChildAt(it) } else emptyList() }
                .ifEmpty { null }
        }.flatten().first { it.javaClass.name.contains("AndroidComposeView") }
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        val density = activity.resources.displayMetrics.density
        val center = 17 * density
        val half = 9 * density // 对勾 16dp，取 18dp 见方
        var sum = 0.0
        for (y in (center - half).toInt() until (center + half).toInt()) for (x in (center - half).toInt() until (center + half).toInt()) {
            val p = bitmap.getPixel(x, y)
            // 底色 #F78E80 绿通道 0x8E，白对勾为 0xFF：按绿通道超出底色的部分计墨量
            val g = (p shr 8) and 0xFF
            sum += ((g - 0x8E).coerceAtLeast(0)) / (0xFF - 0x8E).toDouble()
        }
        return sum
    }

    @Test
    fun drawnCheckIsThickerThanRegularOk() {
        val px = (16 * 3f).toInt() // xxhdpi 下 16dp
        val regular = ink(MiuixIcons.Regular.Ok, px)
        val medium = ink(MiuixIcons.Medium.Ok, px)
        val demibold = ink(MiuixIcons.Demibold.Ok, px)
        assertTrue("Demibold $demibold 应比 Medium $medium 粗", demibold > medium * 1.08)
        val drawn = swatchCheckInk()
        // Medium 只比 Regular 多约 16%，真机看不出；要求比 Regular 粗 25% 以上，Medium 过不了。
        assertTrue("色块上的对勾 $drawn 应比 Regular $regular 粗 25% 以上", drawn > regular * 1.25)
        assertTrue("色块上的对勾 $drawn 应接近 Demibold $demibold", kotlin.math.abs(drawn - demibold) < demibold * 0.08)
    }
}
