package com.juren233.hyperlyricsenhanced.ui.page.log

import android.graphics.Bitmap
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Duration
import kotlin.math.abs

/**
 * 日志分析的状态图标：警告（问题卡片左上角、检查项行）底形是圆润三角形，报错仍是圆形。
 * 先锁定形状选择，再把两个图标真画出来，在「圆里有、三角形外」和「三角形里有、圆外」两个点取像素确认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusGlyphShapeTest {
    init {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
    }

    @Test
    fun shape_warningIsRoundedTriangle_othersAreCircles() {
        assertEquals(StatusGlyphShape.ROUNDED_TRIANGLE, statusGlyphShape(LogCheckStatus.WARNING))
        assertEquals(StatusGlyphShape.CIRCLE, statusGlyphShape(LogCheckStatus.ERROR))
        assertEquals(StatusGlyphShape.CIRCLE, statusGlyphShape(LogCheckStatus.PASS))
        assertEquals(StatusGlyphShape.CIRCLE, statusGlyphShape(LogCheckStatus.SKIPPED))
    }

    private fun triangleRegion(): Region =
        Region().apply { setPath(roundedTrianglePath(240f).asAndroidPath(), Region(-60, -60, 300, 300)) }

    @Test
    fun trianglePath_cornersAreRounded() {
        // 放大到 240 像素栅格化，量真正覆盖的范围：尖角顶点在 (120,-26)、(-24,226)、(264,226)，
        // 圆角后顶部要低一截，左右两端也收进去。
        val b = triangleRegion().bounds
        assertTrue("顶部 ${b.top} 应明显低于尖角顶点 -26", b.top >= -12)
        assertTrue("左端 ${b.left} 应明显在尖角 -24 里面", b.left >= -14)
        assertTrue("右端 ${b.right} 应明显在尖角 264 里面", b.right <= 254)
        assertTrue("底边 ${b.bottom} 仍贴着 226", abs(b.bottom - 226) <= 1)
    }

    @Test
    fun triangle_inkAreaCloseToCircle() {
        // 三角形比同格子的圆显小：墨迹面积要到圆（直径 240）的 70% 以上，宽度不窄于圆。
        val region = triangleRegion()
        var inside = 0
        for (y in -60 until 300) for (x in -60 until 300) if (region.contains(x, y)) inside++
        val circle = Math.PI * 120 * 120
        val ratio = inside / circle
        assertTrue("三角形面积只有圆的 ${"%.0f".format(ratio * 100)}%", ratio >= 0.7)
        val b = region.bounds
        assertTrue("三角形宽 ${b.width()} 应不窄于圆的 240", b.width() >= 240)
    }

    @Test
    fun drawn_warningIsTriangle_errorIsCircle() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var warning = Rect.Zero
        var error = Rect.Zero
        activity.setContent {
            MiuixTheme(controller = ThemeUtils.themeController(1, 0)) {
                Row(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
                    Box(Modifier.onGloballyPositioned { warning = it.boundsInWindow() }) { StatusGlyph(LogCheckStatus.WARNING, 24.dp) }
                    Box(Modifier.padding(start = 24.dp).onGloballyPositioned { error = it.boundsInWindow() }) {
                        StatusGlyph(LogCheckStatus.ERROR, 24.dp)
                    }
                }
            }
        }
        // 没有动画，推进固定时长；截图回调只跑有限个任务等它。
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofSeconds(1))
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        var guard = 0
        while (result == -1 && guard++ < 200) looper.runOneTask()
        check(result == PixelCopy.SUCCESS) { "截图失败：$result" }
        activity.setContent { }
        repeat(20) { looper.runOneTask() }
        controller.pause().stop().destroy()

        fun at(box: Rect, ux: Float, uy: Float): Int =
            bitmap.getPixel((box.left + box.width * ux / 24f).toInt(), (box.top + box.height * uy / 24f).toInt())
        fun isBackground(p: Int) = abs(((p shr 16) and 0xFF) - 255) <= 3 && abs(((p shr 8) and 0xFF) - 255) <= 3 && abs((p and 0xFF) - 255) <= 3
        // A 点 (5,6)：圆里有，三角形左边外面。B 点 (1.5,21.5)：圆外面，三角形左下角里面。
        val warnA = at(warning, 5f, 6f)
        val warnB = at(warning, 1.5f, 21.5f)
        val errA = at(error, 5f, 6f)
        val errB = at(error, 1.5f, 21.5f)
        assertTrue("警告图标顶部 A 点应是背景（三角形外）：#${Integer.toHexString(warnA)}", isBackground(warnA))
        assertTrue("警告图标左下 B 点应有底色（三角形里）：#${Integer.toHexString(warnB)}", !isBackground(warnB))
        assertTrue("报错图标顶部 A 点应有底色（圆里）：#${Integer.toHexString(errA)}", !isBackground(errA))
        assertTrue("报错图标左下 B 点应是背景（圆外）：#${Integer.toHexString(errB)}", isBackground(errB))
    }

    private class Shot(val bitmap: Bitmap, val boxes: Map<LogCheckStatus, Rect>, val content: Map<LogCheckStatus, Color>, val glyphInk: Map<LogCheckStatus, Color>) {
        fun at(status: LogCheckStatus, ux: Float, uy: Float): Color {
            val box = boxes.getValue(status)
            return Color(bitmap.getPixel((box.left + box.width * ux / 24f).toInt(), (box.top + box.height * uy / 24f).toInt()))
        }
    }

    /** 把报错、警告、通过三个图标按 [themeMode]（1 浅色、2 深色）画在卡片底色上截图，并记下各自文字色和符号色。 */
    private fun shoot(themeMode: Int): Shot {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val statuses = listOf(LogCheckStatus.ERROR, LogCheckStatus.WARNING, LogCheckStatus.PASS)
        val boxes = mutableMapOf<LogCheckStatus, Rect>()
        val content = mutableMapOf<LogCheckStatus, Color>()
        val ink = mutableMapOf<LogCheckStatus, Color>()
        activity.setContent {
            MiuixTheme(controller = ThemeUtils.themeController(themeMode, 0)) {
                statuses.forEach {
                    content[it] = toneOf(it)
                    ink[it] = glyphInkOf(it)
                }
                Row(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surfaceContainer).padding(24.dp)) {
                    statuses.forEach { status ->
                        Box(Modifier.padding(end = 24.dp).onGloballyPositioned { boxes[status] = it.boundsInWindow() }) {
                            StatusGlyph(status, 48.dp)
                        }
                    }
                }
            }
        }
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofSeconds(1))
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        var guard = 0
        while (result == -1 && guard++ < 200) looper.runOneTask()
        check(result == PixelCopy.SUCCESS) { "截图失败：$result" }
        // 收尾：清空内容、销毁 Activity，不让窗口留到下一个测试的截图里。
        activity.setContent { }
        repeat(20) { looper.runOneTask() }
        controller.pause().stop().destroy()
        return Shot(bitmap, boxes, content, ink)
    }

    // 符号笔画中间、底色上远离符号的取样点（24 格坐标）。
    private val inkPoint = mapOf(
        LogCheckStatus.ERROR to (12f to 10f),
        LogCheckStatus.WARNING to (12f to 12f),
        LogCheckStatus.PASS to (8.6f to 14.2f),
    )
    private val fillPoint = mapOf(
        LogCheckStatus.ERROR to (6f to 18f),
        LogCheckStatus.WARNING to (5f to 19f),
        LogCheckStatus.PASS to (17f to 18f),
    )

    private fun near(a: Color, b: Color, tol: Float = 4f / 255f) =
        abs(a.red - b.red) <= tol && abs(a.green - b.green) <= tol && abs(a.blue - b.blue) <= tol

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    @Test
    fun lightMode_glyphInkIsLighterThanText_andStillReadable() {
        val shot = shoot(1)
        inkPoint.forEach { (status, p) ->
            val ink = shot.at(status, p.first, p.second)
            val fill = shot.at(status, fillPoint.getValue(status).first, fillPoint.getValue(status).second)
            val text = shot.content.getValue(status)
            val c = contrast(ink, fill)
            println("浅色 $status 符号 ${hex(ink)} 底 ${hex(fill)} 对比度 ${"%.2f".format(c)}（原文字色 ${hex(text)} 对比度 ${"%.2f".format(contrast(text, fill))}）")
            assertTrue("$status 符号 ${hex(ink)} 应是调浅后的符号色 ${hex(shot.glyphInk.getValue(status))}", near(ink, shot.glyphInk.getValue(status)))
            assertTrue("$status 符号 ${hex(ink)} 应比文字色 ${hex(text)} 浅", ink.luminance() > text.luminance() * 1.3f)
            assertTrue("$status 符号与底色对比度 ${"%.2f".format(c)} 应不低于 3:1", c >= 3f)
        }
    }

    @Test
    fun darkMode_glyphInkUnchanged() {
        val shot = shoot(2)
        inkPoint.forEach { (status, p) ->
            val ink = shot.at(status, p.first, p.second)
            val fill = shot.at(status, fillPoint.getValue(status).first, fillPoint.getValue(status).second)
            println("深色 $status 符号 ${hex(ink)} 底 ${hex(fill)} 对比度 ${"%.2f".format(contrast(ink, fill))}")
            assertTrue("深色 $status 符号 ${hex(ink)} 应仍是文字色 ${hex(shot.content.getValue(status))}", near(ink, shot.content.getValue(status)))
        }
    }

    @Test
    fun drawn_warningExclamationCenteredInTriangle() {
        // 画一个 96dp 的警告图标，沿中线量：圆点底部到三角底边、竖线顶端到两条斜边（垂直距离），两者应基本相等。
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var box = Rect.Zero
        var ink = Color.Unspecified
        activity.setContent {
            MiuixTheme(controller = ThemeUtils.themeController(1, 0)) {
                ink = glyphInkOf(LogCheckStatus.WARNING)
                Box(Modifier.fillMaxSize().background(Color.White).padding(48.dp)) {
                    Box(Modifier.onGloballyPositioned { box = it.boundsInWindow() }) { StatusGlyph(LogCheckStatus.WARNING, 96.dp) }
                }
            }
        }
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofSeconds(1))
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        var guard = 0
        while (result == -1 && guard++ < 200) looper.runOneTask()
        check(result == PixelCopy.SUCCESS) { "截图失败：$result" }
        activity.setContent { }
        repeat(20) { looper.runOneTask() }
        controller.pause().stop().destroy()

        val cx = box.center.x.toInt()
        val inkArgb = ink.toArgb()
        fun channelDiff(a: Int, b: Int) =
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
        fun isInk(p: Int) = channelDiff(p, inkArgb) < 60
        fun isBackground(p: Int) = channelDiff(p, 0xFFFFFFFF.toInt()) <= 6
        val top = (box.top - 20).toInt()
        val bottom = (box.bottom + 20).toInt()
        val column = (top until bottom).map { bitmap.getPixel(cx, it) }
        val stemTop = top + column.indexOfFirst(::isInk)
        val dotBottom = top + column.indexOfLast(::isInk)
        val base = top + column.indexOfLast { !isBackground(it) }
        var left = cx
        while (left > 0 && !isBackground(bitmap.getPixel(left - 1, stemTop))) left--
        val slant = 25.2f / kotlin.math.hypot(14.4f, 25.2f)
        val topGap = (cx - left) * slant
        val bottomGap = (base - dotBottom).toFloat()
        println("警告「!」：竖线顶端到斜边 ${"%.1f".format(topGap)}px，圆点底部到底边 ${"%.1f".format(bottomGap)}px")
        val ratio = topGap / bottomGap
        assertTrue("「!」不居中：竖线顶端到斜边 ${"%.1f".format(topGap)}px，圆点底部到底边 ${"%.1f".format(bottomGap)}px", ratio in 0.85f..1.18f)
    }
}
