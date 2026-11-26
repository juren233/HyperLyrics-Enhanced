package com.juren233.hyperlyricsenhanced.ui.component

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Duration
import kotlin.math.abs

/**
 * 提示横幅（NoticeCard：日志页页底反馈、引导页免责声明、升级迁移弹窗）改用大磁贴同一套主题色：
 * 底色、图标、文字都取 [heroPalette]（[HeroTone.Accent]），不再用 tertiaryContainer（深色下是紫色）。
 * 浅色深色各测一遍：取到的颜色要等于 Accent 调色板，且真画出来的卡片底色像素也是这个颜色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticeCardColorsTest {
    init {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
    }

    private class Captured(
        val colors: NoticeCardColors,
        val accentContainer: Color,
        val accentContent: Color,
        val tertiaryContainer: Color,
        val cardPixel: Int,
    )

    private fun capture(themeMode: Int): Captured {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var colors: NoticeCardColors? = null
        var accentContainer = Color.Unspecified
        var accentContent = Color.Unspecified
        var tertiary = Color.Unspecified
        var card = Rect.Zero
        activity.setContent {
            MiuixTheme(controller = ThemeUtils.themeController(themeMode, 0)) {
                colors = noticeCardColors()
                heroPalette(HeroTone.Accent).let { accentContainer = it.container; accentContent = it.content }
                tertiary = MiuixTheme.colorScheme.tertiaryContainer
                Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(top = 12.dp)) {
                    NoticeCard(
                        text = "如果遇到的问题这里没有检测出来，请进行导出完整调试日志后，在 GitHub 仓库发起 issue 并附带日志进行反馈",
                        icon = MiuixIcons.Info,
                        emphasized = true,
                        modifier = Modifier.settingsCard().onGloballyPositioned { card = it.boundsInWindow() },
                    )
                }
            }
        }
        // 卡片没有无限动画，推进固定时长；截图回调只跑有限个任务等它。
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofSeconds(1))
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        var guard = 0
        while (result == -1 && guard++ < 200) looper.runOneTask()
        check(result == PixelCopy.SUCCESS) { "截图失败：$result" }
        val density = activity.resources.displayMetrics.density
        // 卡片左侧内边距（图标之前）里取一个点，避开圆角。
        val pixel = bitmap.getPixel((card.left + 8 * density).toInt(), card.center.y.toInt())
        return Captured(colors!!, accentContainer, accentContent, tertiary, pixel)
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= 3 &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= 3 &&
            abs((a and 0xFF) - (b and 0xFF)) <= 3

    private fun assertAccent(label: String, themeMode: Int) {
        val c = capture(themeMode)
        assertEquals("$label 底色应取 Accent 调色板", c.accentContainer, c.colors.container)
        assertEquals("$label 图标色应取 Accent 调色板文字色", c.accentContent, c.colors.icon)
        assertEquals("$label 文字色应取 Accent 调色板文字色", c.accentContent, c.colors.text)
        assertNotEquals("$label 底色不应再是 tertiaryContainer", c.tertiaryContainer, c.colors.container)
        val expected = c.accentContainer.toArgb()
        assertTrue(
            "$label 画出来的卡片底色 #${Integer.toHexString(c.cardPixel)} 应接近 Accent 底色 #${Integer.toHexString(expected)}",
            near(c.cardPixel, expected),
        )
        assertTrue("$label 画出来的卡片底色不应是 tertiaryContainer", !near(c.cardPixel, c.tertiaryContainer.toArgb()))
    }

    @Test
    fun noticeCard_usesAccentPalette_light() = assertAccent("浅色", themeMode = 1)

    @Test
    fun noticeCard_usesAccentPalette_dark() = assertAccent("深色", themeMode = 2)
}
