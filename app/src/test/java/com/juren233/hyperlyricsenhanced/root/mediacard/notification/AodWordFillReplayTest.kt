/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * 复现「息屏逐字填充在词尾/句尾突然变成纯白」并验证按时间补亮之后不再跳。
 *
 * 歌词时间轴取自 Cavan 11-25 09:20 的日志（息屏逐字绑定，规整后的逐字）；帧间隔取日志里
 * 息屏逐字进度帧统计的实测值（5 秒约 450~590 帧，约 9~11ms 一帧），这里按 10ms 一帧回放。
 * 句中停顿的那句日志里没有（那首歌的词都是首尾相接的），用一句合成的中文停顿句补上。
 *
 * [legacyBrightness] 是旧版 AodWordSyncTextView.drawProgress 在等宽字符坐标下的同一套算式
 * （渐变 x-edge → x 由亮到透明，edge = 0.6 字号；整行唱完或已过的行整行纯白），用来证明
 * 旧画法在这些场景下确实跳；视图本身要真机 Canvas 才能画，测试里没法直接跑。
 */
class AodWordFillReplayTest {

    private val frameMs = 10L

    /** 等宽：每个字符宽 1，柔边 0.6 个字（中文字宽约等于字号，即旧画法的 0.6 字号）。 */
    private val edge = 0.6

    private fun words(vararg spec: Triple<Long, Long, String>) =
        spec.map { (begin, end, text) -> LyricWord(begin = begin, end = end, text = text) }

    /** 09:20:42 绑定：line=38778-41728，最后一个词 40888 唱完后这句还要显示 840ms。 */
    private val married = words(
        Triple(38778, 39088, "yeah "), Triple(39088, 39148, "I "), Triple(39148, 39488, "wanna "),
        Triple(39488, 39718, "get "), Triple(39718, 40888, "married"),
    )
    private val marriedText = "yeah I wanna get married"

    /** line=61768-67028，46 个字符，息屏上会折成两行。 */
    private val somebody = words(
        Triple(61768, 62168, "do "), Triple(62168, 62558, "you "), Triple(62558, 63168, "really "),
        Triple(63168, 64078, "think "), Triple(64078, 64408, "I'll "), Triple(64408, 64768, "fall "),
        Triple(64768, 65088, "for "), Triple(65088, 66118, "somebody "), Triple(66118, 66758, "new"),
    )
    private val somebodyText = "do you really think I'll fall for somebody new"

    /** 折行位置：第一行 "do you really think I'll fall "（含行尾空格，与 StaticLayout 一致）。 */
    private val somebodyLines = listOf(0 until 30, 30 until 46)

    /** 合成：「想」唱完后停顿 800ms 再唱「要你」。 */
    private val pause = words(
        Triple(1000, 1300, "我"), Triple(1300, 1600, "想"), Triple(2400, 2700, "要"), Triple(2700, 3000, "你"),
    )
    private val pauseText = "我想要你"

    // ---- 旧画法（逐行复刻 drawProgress 的算式） ----

    private fun legacyBrightness(
        text: String,
        words: List<LyricWord>,
        lines: List<IntRange>,
        coord: Double,
        positionMs: Long,
    ): Double {
        val chars = AodWordProgress.progressChars(text, words, positionMs)!!.toDouble()
        val length = text.length
        val charIndex = floor(chars).toInt().coerceIn(0, length)
        val fraction = chars - charIndex
        val probe = charIndex.coerceAtMost(length - 1)
        val activeLine = lines.indexOfFirst { probe in it }
        val allSung = chars >= length
        val line = lines.indexOfFirst { coord.toInt().coerceAtMost(length - 1) in it }
        return when {
            allSung || line < activeLine -> 1.0
            line == activeLine -> {
                val range = lines[line]
                // progressX：字符边界之间按小数线性插值，夹在行左右边界内（等宽时即字符坐标）。
                val x = (charIndex + fraction).coerceIn(range.first.toDouble(), range.last + 1.0)
                ((x - coord) / edge).coerceIn(0.0, 1.0)
            }
            else -> 0.0
        }
    }

    // ---- 新画法 ----

    private fun fill(text: String, words: List<LyricWord>): AodWordFill =
        AodWordFill.build(text, words, { 1.0 }, edge).also { assertNotNull(it) }!!

    /** 每个字符内取 0.1..0.9 的采样点（避开字符边界上前后两字的分界）。 */
    private fun samplePoints(length: Int) =
        (0 until length).flatMap { i -> (1..9).map { i + it / 10.0 } }

    private data class Jump(val coord: Double, val atMs: Long, val from: Double, val to: Double)

    /** 回放 [startMs]..[endMs]，返回每个采样点相邻两帧之间最大的一次变化。 */
    private fun maxStep(
        points: List<Double>,
        startMs: Long,
        endMs: Long,
        brightness: (Double, Long) -> Double,
    ): Jump {
        var worst = Jump(0.0, 0, 0.0, 0.0)
        for (point in points) {
            var previous = brightness(point, startMs)
            var t = startMs + frameMs
            while (t <= endMs) {
                val current = brightness(point, t)
                if (kotlin.math.abs(current - previous) > kotlin.math.abs(worst.to - worst.from)) {
                    worst = Jump(point, t, previous, current)
                }
                previous = current
                t += frameMs
            }
        }
        return worst
    }

    /**
     * 新画法每帧最多变化：柔边随进度点推进的量（这句里最快的词，一帧走过的字形宽度 / 柔边宽度）
     * 加上补满项一帧的量（frameMs / SETTLE_MS）。旧画法正常唱时的变化量也是前一项，
     * 超出这个上限就是「跳」。
     */
    private fun allowedStep(words: List<LyricWord>): Double {
        val fastest = words.maxOf { word ->
            val glyphs = word.text.orEmpty().count { !it.isWhitespace() }
            val duration = (word.end - word.begin).coerceAtLeast(1)
            glyphs.toDouble() / duration
        }
        return frameMs * fastest / edge + frameMs / AodWordFill.SETTLE_MS + 1e-9
    }

    private fun assertSmoothAndMonotonic(fill: AodWordFill, words: List<LyricWord>, startMs: Long, endMs: Long) {
        val allowedStep = allowedStep(words)
        for (point in samplePoints(fill.length)) {
            var previous = fill.brightnessAt(point, startMs.toDouble())
            var t = startMs + frameMs
            while (t <= endMs) {
                val current = fill.brightnessAt(point, t.toDouble())
                assertTrue("point=$point t=$t 变暗了 $previous -> $current", current >= previous - 1e-12)
                assertTrue(
                    "point=$point t=$t 一帧跳了 $previous -> $current",
                    current - previous <= allowedStep,
                )
                previous = current
                t += frameMs
            }
        }
    }

    @Test
    fun legacyFillSnapsToWhiteAtLineEnd() {
        // 只看最后一个字母 d（index 23），其他位置在跨空格时也会跳，单独有用例。
        val points = (1..9).map { 23 + it / 10.0 }
        val jump = maxStep(points, 38778, 41728) { coord, t ->
            legacyBrightness(marriedText, married, listOf(marriedText.indices), coord, t)
        }
        // 句尾 40888 那一帧，「married」最后 0.6 个字从几乎全暗直接变成全白。
        assertEquals(40888L, jump.atMs)
        assertTrue("旧画法句尾跳变 $jump", jump.to - jump.from > 0.8)
        assertTrue(jump.to - jump.from > allowedStep(married))
    }

    @Test
    fun legacyFillJumpsOverTheSpaceBetweenEnglishWords() {
        // 只看「yeah」的最后一个字母：39088 唱完时进度点跨过空格，它的亮度一帧之内跳一截。
        val points = (1..9).map { 3 + it / 10.0 }
        val jump = maxStep(points, 38778, 39400) { coord, t ->
            legacyBrightness(marriedText, married, listOf(marriedText.indices), coord, t)
        }
        assertEquals(39088L, jump.atMs)
        assertTrue("旧画法跨空格跳变 $jump", jump.to - jump.from > 0.5)
        assertTrue(jump.to - jump.from > allowedStep(married))
    }

    @Test
    fun legacyFillSnapsPreviousLineWhenWrapping() {
        // 第一行最后一个字符（行尾空格前的 l）在折到第二行的那一帧整行变白。
        val points = (1..9).map { 28 + it / 10.0 }
        val jump = maxStep(points, 61768, 67028) { coord, t ->
            legacyBrightness(somebodyText, somebody, somebodyLines, coord, t)
        }
        assertEquals(64768L, jump.atMs)
        assertTrue("旧画法折行跳变 $jump", jump.to - jump.from > 0.8)
        assertTrue(jump.to - jump.from > allowedStep(somebody))
    }

    @Test
    fun legacyFillLeavesTheWordTailHalfLitDuringAPause() {
        // 停顿中（1600~2400），「想」的后半一直半亮，直到「要」开唱才补亮。
        val mid = legacyBrightness(pauseText, pause, listOf(pauseText.indices), 1.8, 2300)
        assertTrue("旧画法停顿时词尾亮度 $mid", mid < 0.5)
    }

    @Test
    fun timeFillIsSmoothAtLineEndAndAcrossSpaces() {
        val fill = fill(marriedText, married)
        assertSmoothAndMonotonic(fill, married, 38778, 41728)
        // 句尾在这句显示结束（41728）之前就补满，之后与整行纯白一致。
        assertTrue(fill.settledAtMs <= 41728)
        samplePoints(marriedText.length).forEach {
            assertEquals(1.0, fill.brightnessAt(it, fill.settledAtMs), 1e-9)
        }
    }

    @Test
    fun timeFillIsSmoothWhenWrapping() {
        // 新画法按字符坐标算亮度，与折行无关；同一套采样也覆盖了折行处。
        val fill = fill(somebodyText, somebody)
        assertSmoothAndMonotonic(fill, somebody, 61768, 67028)
        // 这句最后一个词 66758 唱完、67028 换下一句，只有 270ms，补满要 300ms：换句那一刻尾巴已补到 0.9。
        assertEquals(66758 + AodWordFill.SETTLE_MS, fill.settledAtMs, 1e-9)
        samplePoints(somebodyText.length).forEach {
            assertTrue(fill.brightnessAt(it, 67028.0) >= 0.9 - 1e-9)
        }
    }

    @Test
    fun timeFillFinishesTheWordTailDuringAPause() {
        val fill = fill(pauseText, pause)
        assertSmoothAndMonotonic(fill, pause, 1000, 3600)
        // 「想」在 1600 唱完，SETTLE_MS 后就全亮，停顿中途已补满；「要」还没开唱，保持暗。
        assertEquals(1.0, fill.brightnessAt(1.8, 1600 + AodWordFill.SETTLE_MS), 1e-9)
        assertEquals(0.0, fill.brightnessAt(2.5, 2300.0), 1e-9)
    }

    @Test
    fun timeFillMatchesLegacyEdgeWhileSingingSteadily() {
        // 正常唱的时候（中文一字一拍）柔边与旧画法逐点一致。
        val steady = words(Triple(0, 300, "一"), Triple(300, 600, "二"), Triple(600, 900, "三"), Triple(900, 1200, "四"))
        val fill = fill("一二三四", steady)
        for (coord in listOf(1.1, 1.5, 1.9)) {
            val legacy = legacyBrightness("一二三四", steady, listOf(0 until 4), coord, 600)
            assertEquals(legacy, fill.brightnessAt(coord, 600.0), 1e-9)
        }
    }

    /** [positionMs] 时柔边的宽度：亮度从 1 降到 0 的那段横向距离（字符坐标）。 */
    private fun edgeWidth(fill: AodWordFill, positionMs: Double): Double {
        val step = 0.001
        var firstNotFull = Double.NaN
        var firstDark = Double.NaN
        var coord = step / 2
        while (coord < fill.length) {
            val value = fill.brightnessAt(coord, positionMs)
            if (firstNotFull.isNaN() && value < 1.0 - 1e-9) firstNotFull = coord
            if (!firstNotFull.isNaN() && value <= 1e-9) { firstDark = coord; break }
            coord += step
        }
        return firstDark - firstNotFull
    }

    @Test
    fun edgeKeepsTheSameWidthOnLongNotesAndFastWords() {
        // 「啊」拖 2 秒长音：唱到一半时柔边仍是 0.6 个字（旧画法的宽度），不能被压窄。
        val longNote = words(Triple(0, 300, "我"), Triple(300, 600, "爱"), Triple(600, 900, "你"), Triple(900, 2900, "啊"))
        assertEquals(edge, edgeWidth(fill("我爱你啊", longNote), 1900.0), 0.01)
        // 一个字 0.1 秒的快词：柔边也是 0.6 个字，不能被放宽。
        val fast = words(Triple(0, 100, "一"), Triple(100, 200, "二"), Triple(200, 300, "三"), Triple(300, 400, "四"))
        assertEquals(edge, edgeWidth(fill("一二三四", fast), 350.0), 0.01)
    }

    @Test
    fun profileReproducesBrightnessAtEverySample() {
        // 视图用 profile 的折线做渐变，折线在采样点上必须与逐点亮度一致。
        val fill = fill(somebodyText, somebody)
        val out = DoubleArray(somebodyText.length * 12)
        var t = 61768L
        while (t <= 67028) {
            for (range in somebodyLines) {
                val count = fill.profile(range.first, range.last + 1, t.toDouble(), out)
                for (point in samplePoints(somebodyText.length).filter { it.toInt() in range }) {
                    assertEquals("t=$t point=$point", fill.brightnessAt(point, t.toDouble()), interpolate(out, count, point), 1e-6)
                }
            }
            t += 37
        }
    }

    @Test
    fun buildRejectsMismatchedText() {
        assertEquals(null, AodWordFill.build("别的歌词", pause, { 1.0 }, edge))
    }

    /** 1257 日志：「我看不到边界」，末字「界」拖了 2.5 秒，唱完后下一句隔了很久才来。 */
    private val boundary = words(
        Triple(210932, 211202, "我"), Triple(211202, 211427, "看"), Triple(211427, 211909, "不"),
        Triple(211909, 212765, "到边"), Triple(212765, 215283, "界"),
    )
    private val boundaryText = "我看不到边界"

    @Test
    fun tailSlidesOutToTheRightAfterTheLongNote() {
        // 长音唱完后的收尾：柔边保持原来的形状整体往右滑出，越靠右越暗；
        // 不能变成右侧一整片同样亮度从未唱完的位置往上补。
        val fill = fill(boundaryText, boundary)
        val t = 215283.0 + AodWordFill.SETTLE_MS / 2
        val a = fill.brightnessAt(5.5, t)
        val b = fill.brightnessAt(5.8, t)
        val c = fill.brightnessAt(5.95, t)
        assertTrue("越靠右应越暗: 5.5=$a 5.8=$b 5.95=$c", a > b && b > c)
        // 柔边在 5.9 处原本是 (6-5.9)/0.6，收尾进行到一半时整体加上 0.5。
        assertEquals((6 - 5.9) / edge + 0.5, fill.brightnessAt(5.9, t), 1e-6)
        // 收尾期间任何一点都不会一帧跳亮或变暗。
        assertSmoothAndMonotonic(fill, boundary, 215283 - 300, fill.settledAtMs.toLong() + 50)
        assertEquals(1.0, fill.brightnessAt(5.99, fill.settledAtMs), 1e-9)
    }

    @Test
    fun fullyLitGradientMatchesSolidWhite() {
        // 渐变里已经全亮的部分与整行纯白必须同一个透明度。渐变上色时画笔本身的透明度会再乘一次，
        // 文字颜色不是完全不透明时（例如息屏把字色调成半透明）两者就有色差。
        for (textColor in intArrayOf(0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x99FFFFFF.toInt())) {
            val solidAlpha = textColor ushr 24
            val paintAlpha = AodWordFill.gradientPaintColor(textColor) ushr 24
            val stopAlpha = AodWordFill.stopColor(textColor, 1.0) ushr 24
            val gradientAlpha = paintAlpha * stopAlpha / 255
            assertEquals("textColor=${Integer.toHexString(textColor)}", solidAlpha, gradientAlpha)
        }
    }

    /**
     * 1257 日志：「看看从前」唱到 192996，下一句 193006 就开始（中间 10 ms）。不开提前换句时这一行在 193006
     * 被换走；日志里提前换句设为 100 ms（key_hook_early_next_line_preview = 2），实际在 192896 换走，
     * 那时「前」还没唱完。
     */
    private val formerly = words(
        Triple(189444, 189912, "看"), Triple(189912, 190637, "看"), Triple(190637, 191294, "从"),
        Triple(191294, 192996, "前"),
    )
    private val formerlyText = "看看从前"
    private val formerlyLeaves = listOf(193006.0, 192896.0)

    @Test
    fun tailIsFullBeforeTheLineIsReplaced() {
        for (leaveMs in formerlyLeaves) {
            // 换句那一刻整行必须已经全亮，否则带走的这一行尾巴是半亮的。
            val fill = AodWordFill.build(formerlyText, formerly, { 1.0 }, edge, leaveMs = leaveMs)
                .also { assertNotNull(it) }!!
            for (point in samplePoints(formerlyText.length) + 3.99) {
                assertEquals("leave=$leaveMs point=$point", 1.0, fill.brightnessAt(point, leaveMs), 1e-9)
            }
            assertTrue(fill.settledAtMs <= leaveMs + 1e-9)
            // 提前补满的过程照样平滑、只增不减。
            assertSmoothAndMonotonic(fill, formerly, 189444, leaveMs.toLong() + 100)
            // 离换句还远的时候不受影响：唱到一半时与不知道换句时刻的画法一致。
            val plain = fill(formerlyText, formerly)
            for (point in samplePoints(formerlyText.length)) {
                assertEquals(plain.brightnessAt(point, 192000.0), fill.brightnessAt(point, 192000.0), 1e-9)
            }
        }
    }

    @Test
    fun profileReproducesBrightnessWhileFinishingEarly() {
        for (leaveMs in formerlyLeaves) {
            val fill = AodWordFill.build(formerlyText, formerly, { 1.0 }, edge, leaveMs = leaveMs)!!
            val out = DoubleArray(formerlyText.length * 12)
            var t = 192300L
            while (t <= leaveMs) {
                val count = fill.profile(0, formerlyText.length, t.toDouble(), out)
                for (point in samplePoints(formerlyText.length)) {
                    assertEquals(
                        "leave=$leaveMs t=$t point=$point",
                        fill.brightnessAt(point, t.toDouble()), interpolate(out, count, point), 1e-6,
                    )
                }
                t += 13
            }
        }
    }

    private fun interpolate(points: DoubleArray, count: Int, coord: Double): Double {
        var i = 0
        while (i + 2 < count) {
            val c0 = points[i]
            val c1 = points[i + 2]
            if (coord >= c0 && coord <= c1 && c1 > c0) {
                return points[i + 1] + (points[i + 3] - points[i + 1]) * (coord - c0) / (c1 - c0)
            }
            i += 2
        }
        error("coord $coord not covered")
    }
}
