package com.juren233.hyperlyricsenhanced.ui.component

import android.graphics.Bitmap
import android.os.Looper
import android.os.Handler
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.time.Duration
import kotlin.math.abs

/**
 * Cavan 觉得头卡右上角的「>」偏左（主页歌词设置头卡、Apple Music 体验优化页头卡、日志分析卡片都是这一个 HeroCard）。
 * 原因：ChevronForward 字形在自己的视口里左右各留约 27% 的空白，按 18dp 画时右边约 4.85dp 是空的，
 * 字形右缘离卡片右边约 22.85dp，而左边文字从 18dp 起。
 * 这里真画一张头卡，按像素量出字形右缘到卡片右边、文字左缘到卡片左边的距离，要求两边对齐；
 * 同时对照普通设置行（ArrowPreference）箭头的右边距。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroArrowAlignmentTest {

    init {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
    }

    private class Measured(val leftGapDp: Float, val rightGapDp: Float)

    /** 空图标：测量时不画右下角装饰，免得它的笔画混进箭头所在的横条。 */
    private val blankIcon = ImageVector.Builder("Blank", 24.dp, 24.dp, 24f, 24f).build()

    private fun render(themeMode: Int, content: @Composable () -> Unit): Pair<Bitmap, Float> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MiuixTheme(controller = ThemeUtils.themeController(themeMode, 0)) {
                // 与页面里的 LazyColumn 一样给不限高的纵向约束，头卡底部附加提示的 weight 才不会把卡片撑满屏幕。
                Column(
                    Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(top = 12.dp),
                ) {
                    content()
                }
            }
        }
        // 头卡只有 300ms 的配色/图标过渡，没有无限动画，推进固定时长即可。
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        // miuix 卡片的圆角用 RuntimeShader，软件画布画不了，走 Robolectric 的硬件渲染截取整个窗口。
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        check(result == PixelCopy.SUCCESS) { "截图失败：$result" }
        return bitmap to activity.resources.displayMetrics.density
    }

    private fun differs(a: Int, b: Int): Boolean {
        val d = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))
        return d > 60
    }

    /** 在卡片 [card] 的 [bandTopDp]–[bandBottomDp] 横条里，量最左墨迹到卡片左边、最右墨迹到卡片右边的距离。 */
    private fun measure(bitmap: Bitmap, density: Float, card: Rect, bandTopDp: Float, bandBottomDp: Float): Measured {
        val top = (card.top + bandTopDp * density).toInt()
        val bottom = (card.top + bandBottomDp * density).toInt()
        val left = card.left.toInt()
        val right = card.right.toInt() - 1
        val container = bitmap.getPixel(left + (4 * density).toInt(), card.center.y.toInt())
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        for (y in top until bottom) for (x in left + (4 * density).toInt()..right - (4 * density).toInt()) {
            if (differs(bitmap.getPixel(x, y), container)) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
            }
        }
        check(minX <= maxX) { "横条里没有找到墨迹" }
        return Measured(
            leftGapDp = (minX - card.left) / density,
            rightGapDp = (card.right - 1 - maxX) / density,
        )
    }

    private fun heroGaps(themeMode: Int, overline: String?): Pair<Measured, Measured> {
        var hero = Rect.Zero
        var row = Rect.Zero
        val (bitmap, density) = render(themeMode) {
            HeroCard(
                overline = overline,
                title = "1 个报错 · 2 个警告",
                decorationIcon = blankIcon,
                tone = HeroTone.Error,
                onClick = {},
                modifier = Modifier.settingsHeroCard().onGloballyPositioned { hero = it.boundsInWindow() },
            )
            Card(modifier = Modifier.settingsCard().onGloballyPositioned { row = it.boundsInWindow() }) {
                ArrowPreference(title = "查看日志", onClick = {})
            }
        }
        // 箭头所在的第一行：卡片内边距 16dp 起，行高不超过 32dp（标题 24sp 的行高）。
        val heroBandBottom = if (overline != null) 16f + 20f else 16f + 34f
        val heroMeasured = measure(bitmap, density, hero, 16f, heroBandBottom)
        // 设置行取卡片中间一条，避开圆角处透出的页面底色。
        val rowMid = row.height / density / 2f
        val rowMeasured = measure(bitmap, density, row, rowMid - 10f, rowMid + 10f)
        return heroMeasured to rowMeasured
    }

    private fun assertAligned(label: String, hero: Measured, row: Measured) {
        val msg = "$label：文字墨迹左距 ${"%.2f".format(hero.leftGapDp)}dp，箭头墨迹右距 ${"%.2f".format(hero.rightGapDp)}dp，" +
            "普通设置行箭头右距 ${"%.2f".format(row.rightGapDp)}dp"
        println(msg)
        // 文字的排版起点是 18dp 内边距线，墨迹还要再加上字形自己的左侧留白（「A」约 0、「日」约 2dp），因人而异；
        // 箭头字形右缘应落在右侧同一条 18dp 内边距线上。
        assertTrue("$msg；箭头字形右缘应落在 18dp 内边距线上（差 ≤0.75dp）", abs(hero.rightGapDp - HeroPaddingDp) <= 0.75f)
        assertTrue("$msg；箭头不应比文字更靠里", hero.rightGapDp <= hero.leftGapDp + 0.75f)
        assertTrue("$msg；头卡箭头右距应与普通设置行箭头右距对齐（差 ≤1.5dp）", abs(hero.rightGapDp - row.rightGapDp) <= 1.5f)
    }

    private companion object {
        const val HeroPaddingDp = 18f
    }

    @Test
    fun arrowWithOverline_alignsWithTextInset_light() {
        val (hero, row) = heroGaps(themeMode = 1, overline = "日志分析")
        assertAligned("浅色·有小标题", hero, row)
    }

    @Test
    fun arrowWithOverline_alignsWithTextInset_dark() {
        val (hero, row) = heroGaps(themeMode = 2, overline = "Apple Music")
        assertAligned("深色·有小标题", hero, row)
    }

    @Test
    fun arrowOnTitleLine_alignsWithTextInset() {
        val (hero, row) = heroGaps(themeMode = 1, overline = null)
        assertAligned("浅色·箭头在标题行", hero, row)
    }

    /** 渲染三处头卡加一张普通设置行作对照，供改前改后比对；只在设了 HLE_RENDER_DIR 时运行。 */
    @Test
    fun renderComparison() {
        val dir = System.getenv("HLE_RENDER_DIR") ?: return
        File(dir).mkdirs()
        for ((name, mode) in listOf("light" to 1, "dark" to 2)) {
            val (bitmap, _) = render(mode) {
                HeroCard(
                    overline = "歌词源",
                    title = "Lyricon",
                    subtitle = "版本 8.5.0 (250088)",
                    decorationIcon = MiuixIcons.Notes,
                    onClick = {},
                    modifier = Modifier.settingsHeroCard(),
                )
                HeroCard(
                    overline = "Apple Music",
                    title = "插件已就绪",
                    notes = listOf("版本 1.0.39"),
                    tone = HeroTone.Success,
                    decorationIcon = MiuixIcons.Music,
                    onClick = {},
                    modifier = Modifier.settingsHeroCard(),
                )
                HeroCard(
                    overline = "日志分析",
                    title = "1 个报错 · 2 个警告",
                    tone = HeroTone.Error,
                    decorationIcon = StatusGlyphs.Error,
                    onClick = {},
                    modifier = Modifier.settingsCard(),
                )
                Card(modifier = Modifier.settingsCard()) {
                    ArrowPreference(title = "查看日志", onClick = {})
                    ArrowPreference(title = "导出全部日志", onClick = {})
                }
            }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
