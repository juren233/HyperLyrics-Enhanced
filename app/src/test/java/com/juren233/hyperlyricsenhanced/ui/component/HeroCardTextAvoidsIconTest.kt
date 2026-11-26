package com.juren233.hyperlyricsenhanced.ui.component

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Duration
import kotlin.math.abs

/**
 * 头卡（HeroCard）的文字不能压到右下角的大装饰图标上：用 Apple Music 头卡里最长的真实副标题，
 * 六种装饰图标各画一张卡，量文字墨迹的右缘，必须在图标墨迹的左缘左边。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w393dp-h1600dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroCardTextAvoidsIconTest {
    init {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
    }

    private class Case(val name: String, val icon: ImageVector, val tone: HeroTone)

    private val cases = listOf(
        Case("警告", StatusGlyphs.Warning, HeroTone.Warning),
        Case("报错", StatusGlyphs.Error, HeroTone.Error),
        Case("音符", MiuixIcons.Music, HeroTone.Success),
        Case("通过", MiuixIcons.Ok, HeroTone.Success),
        Case("检查中", MiuixIcons.Search, HeroTone.Accent),
        Case("首页", MiuixIcons.Notes, HeroTone.Accent),
    )

    @Test
    fun longSubtitle_textStaysLeftOfDecoration() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val boxes = mutableMapOf<String, Rect>()
        val container = mutableMapOf<String, Color>()
        val decoration = mutableMapOf<String, Color>()
        activity.setContent {
            MiuixTheme(controller = ThemeUtils.themeController(1, 0)) {
                Column(
                    // 和设置页一样放在可滚动列表里：高度不受限，头卡按内容高度排。
                    Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    cases.forEach { case ->
                        val palette = heroPalette(case.tone)
                        container[case.name] = palette.container
                        decoration[case.name] = palette.decoration.compositeOver(palette.container)
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).onGloballyPositioned { boxes[case.name] = it.boundsInWindow() }) {
                            HeroCard(
                                overline = "Apple Music",
                                title = "暂不可用",
                                subtitle = "Apple Music 插件已添加但未开启，点击此处去开启",
                                notes = listOf("插件版本 1.0.39"),
                                tone = case.tone,
                                decorationIcon = case.icon,
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }
        // 没有无限动画：推进固定时长让配色过渡走完；截图回调只跑有限个任务。
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofSeconds(2))
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        var guard = 0
        while (result == -1 && guard++ < 200) looper.runOneTask()
        check(result == PixelCopy.SUCCESS) { "截图失败：$result" }
        // 不在这里销毁 Activity：清空内容再销毁会留下 Compose 主线程调度任务，被下一个测试清掉后，
        // 后面强调色色块测试（SystemSwatchTest）改状态就不再重组，截到旧画面。

        val density = activity.resources.displayMetrics.density
        val problems = mutableListOf<String>()
        cases.forEach { case ->
            val box = boxes.getValue(case.name)
            val deco = decoration.getValue(case.name).toArgb()
            fun diff(a: Int, b: Int) =
                abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
            // 跳过第一行（小字和右上角箭头那一行），从大标题开始量。
            val textTop = (box.top + 38 * density).toInt()
            var textRight = -1
            var decoLeft = Int.MAX_VALUE
            for (y in box.top.toInt() until box.bottom.toInt()) {
                for (x in box.left.toInt() until box.right.toInt()) {
                    val p = bitmap.getPixel(x, y)
                    if (y >= textTop && Color(p).luminance() < 0.3f) textRight = maxOf(textRight, x)
                    if (diff(p, deco) <= 18) decoLeft = minOf(decoLeft, x)
                }
            }
            println("${case.name}：文字右缘 $textRight，图标左缘 $decoLeft（相差 ${"%.1f".format((decoLeft - textRight) / density)}dp）")
            if (textRight < 0 || decoLeft == Int.MAX_VALUE) problems += "${case.name}：没量到文字或图标"
            else if (textRight >= decoLeft) problems += "${case.name}：文字右缘 $textRight 压到图标左缘 $decoLeft"
        }
        assertTrue(problems.joinToString("；"), problems.isEmpty())
    }
}
