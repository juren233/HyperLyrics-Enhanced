/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ISLAND-GATE-OCCLUSION-001 第 6 轮（2026-10-01 用户拍板）：接缝几何与
 * 绘制方案的纯 JVM 回归。
 *
 * 关键固化语义：
 * - 单元定义（CJK 按字、拉丁逐字符、标点禁则成组）；
 * - 至多一个跨缝单元，且恰好是 advance 区间横跨接缝者，边界/空白不参与；
 * - 静止＝绕孔分段：锚定边段带零位移（行头真贴左缘/行尾真贴右缘），
 *   跨缝段整段平移过缝，落定后必无跨缝；整行位移（nudge 全家族）已被
 *   真机三败否决，不得回归；
 * - 滚动＝边缘滑过＋渐隐：跨缝单元只画多数侧、alpha=2|f−0.5| 连续渐入
 *   渐出，无整字消失瞬间；
 * - 静止↔滚动过渡＝间隙随滚动线性合拢/张开：任一单元的绘制位置随
 *   scrollOffset 连续变化（≤2×步进），全程零跳变。
 */
class SeamOcclusionLayoutTest {

    private fun uniform(text: String, unit: Float): SeamOcclusionLayout =
        requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { unit }))

    private fun widths(text: String, widths: FloatArray): SeamOcclusionLayout =
        requireNotNull(SeamOcclusionLayout.build(text, widths))

    // ---- 单元表 ----

    @Test
    fun `cjk characters are single units`() {
        val layout = uniform("你好世界", 20f)
        assertEquals(0, layout.straddlingUnit(0f, 5f)!!.charStart)
        assertEquals(1, layout.straddlingUnit(0f, 5f)!!.charEnd)
        assertEquals(1, layout.straddlingUnit(0f, 25f)!!.charStart)
        assertEquals(3, layout.straddlingUnit(0f, 75f)!!.charStart)
        assertNull(layout.straddlingUnit(0f, 90f))
    }

    @Test
    fun `seam exactly on a unit boundary straddles nothing`() {
        val layout = uniform("你好世界", 20f)
        assertNull(layout.straddlingUnit(0f, 0f))
        assertNull(layout.straddlingUnit(0f, 20f))
        assertNull(layout.straddlingUnit(0f, 40f))
        assertNull(layout.straddlingUnit(-7.5f, 12.5f))
    }

    @Test
    fun `latin characters are single units inside words`() {
        val layout = uniform("hello", 8f)
        val hidden = layout.straddlingUnit(0f, 20f)!!
        assertEquals(2, hidden.charStart)
        assertEquals(3, hidden.charEnd)
        assertEquals(1, layout.straddlingUnit(0f, 4f)!!.charEnd - layout.straddlingUnit(0f, 4f)!!.charStart)
    }

    @Test
    fun `trailing punctuation joins the previous unit`() {
        val layout = uniform("好，你", 20f)
        val straddlingHead = layout.straddlingUnit(0f, 10f)!!
        assertEquals(0, straddlingHead.charStart)
        assertEquals(2, straddlingHead.charEnd)
        assertEquals(2, layout.straddlingUnit(0f, 30f)!!.charEnd)
        assertEquals(2, layout.straddlingUnit(0f, 50f)!!.charStart)
    }

    @Test
    fun `opening punctuation joins the following unit`() {
        val layout = uniform("「我」你", 20f)
        assertEquals(0, layout.straddlingUnit(0f, 10f)!!.charStart)
        assertEquals(3, layout.straddlingUnit(0f, 10f)!!.charEnd)
        assertEquals(3, layout.straddlingUnit(0f, 50f)!!.charEnd)
        assertEquals(3, layout.straddlingUnit(0f, 70f)!!.charStart)
        assertEquals(4, layout.straddlingUnit(0f, 70f)!!.charEnd)
    }

    @Test
    fun `leading punctuation at line start joins forward`() {
        val layout = uniform("。，你", 20f)
        val hidden = layout.straddlingUnit(0f, 30f)!!
        assertEquals(0, hidden.charStart)
        assertEquals(3, hidden.charEnd)
    }

    @Test
    fun `blank segments never straddle`() {
        val layout = widths("a  b", floatArrayOf(8f, 4f, 4f, 8f))
        assertNull(layout.straddlingUnit(0f, 12f))
        assertEquals(0f, layout.rightShiftToClear(0f, 12f), 0f)
        assertEquals(0, layout.straddlingUnit(0f, 4f)!!.charStart)
        assertEquals(3, layout.straddlingUnit(0f, 20f)!!.charStart)
    }

    @Test
    fun `straddling unit is the one strictly containing the seam`() {
        val layout = uniform("你好世界", 20f)
        var offset = -100f
        while (offset <= 100f) {
            var seam = 0.5f
            while (seam < 80f) {
                val hidden = layout.straddlingUnit(offset, seam)
                if (hidden != null) {
                    assertTrue(hidden.start + offset < seam)
                    assertTrue(seam < hidden.end + offset)
                }
                seam += 7.3f
            }
            offset += 0.5f
        }
    }

    @Test
    fun `crossing window equals unit width - no park and no early release`() {
        val layout = uniform("ABCDEFGHIJ", 10f)
        val seam = 45f
        for (charIndex in 0 until 10) {
            val hiddenOffsets = ArrayList<Float>()
            var offset = 60f
            while (offset >= -200f) {
                if (layout.straddlingUnit(offset, seam)?.charStart == charIndex) {
                    hiddenOffsets.add(offset)
                }
                offset -= 0.5f
            }
            assertTrue(hiddenOffsets.isNotEmpty())
            // 跨缝窗口连续：不存在「跨缝→不跨→再跨」的驻留/回插形态。
            for (i in 1 until hiddenOffsets.size) {
                assertEquals(hiddenOffsets[i - 1] - 0.5f, hiddenOffsets[i], 1e-6f)
            }
            val span = hiddenOffsets.first() - hiddenOffsets.last()
            assertTrue(span >= 8.5f && span <= 10f)
        }
    }

    @Test
    fun `single latin char straddles alone inside a word`() {
        val layout = widths("word", floatArrayOf(5f, 8f, 8f, 4f))
        val hidden = layout.straddlingUnit(0f, 12f)!!
        assertEquals(1, hidden.charStart)
        assertEquals(2, hidden.charEnd)
        assertEquals(5f, hidden.start, 0f)
        assertEquals(13f, hidden.end, 0f)
        assertNotNull(layout.straddlingUnit(0f, 3f))
        assertEquals(2, layout.straddlingUnit(0f, 20f)!!.charStart)
    }

    @Test
    fun `degenerate inputs return null layout`() {
        assertNull(SeamOcclusionLayout.build("", FloatArray(0)))
        assertNull(SeamOcclusionLayout.build("ab", floatArrayOf(1f)))
    }

    // ---- 静止绕孔分段 ----

    @Test
    fun `static head anchor keeps head flush and shifts straddling segment right`() {
        val layout = uniform("你好世界", 20f)
        val plan = SeamStripPlan.staticClear(layout, 0f, 30f, SeamStripPlan.Anchor.HEAD, 200f)
        assertEquals(
            listOf(
                SeamStripPlan.Band(0, 1, 0f, 0f),
                SeamStripPlan.Band(1, 4, 10f, 20f),
            ),
            plan.bands,
        )
        // 避让落定后必无跨缝（无吃字、无切半）。
        assertNull(plan.straddler)
    }

    @Test
    fun `static tail anchor keeps tail flush and shifts straddling segment left`() {
        val layout = uniform("你好世界", 20f)
        val plan = SeamStripPlan.staticClear(layout, 0f, 30f, SeamStripPlan.Anchor.TAIL, 200f)
        assertEquals(
            listOf(
                SeamStripPlan.Band(0, 2, -10f, 0f),
                SeamStripPlan.Band(2, 4, 0f, 40f),
            ),
            plan.bands,
        )
        assertNull(plan.straddler)
    }

    @Test
    fun `free anchor picks the smaller displacement when both sides fit`() {
        val layout = uniform("你好世界", 20f)
        // f=0.25：右移 5 ＜ 左移 15 → 取 HEAD 侧。
        assertEquals(
            SeamStripPlan.staticClear(layout, 20f, 45f, SeamStripPlan.Anchor.HEAD, 200f).bands,
            SeamStripPlan.staticClear(layout, 20f, 45f, SeamStripPlan.Anchor.FREE, 200f).bands,
        )
        // f=0.75：左移 5 ＜ 右移 15 → 取 TAIL 侧。
        assertEquals(
            SeamStripPlan.staticClear(layout, 20f, 55f, SeamStripPlan.Anchor.TAIL, 200f).bands,
            SeamStripPlan.staticClear(layout, 20f, 55f, SeamStripPlan.Anchor.FREE, 200f).bands,
        )
    }

    @Test
    fun `static avoidance clears the seam for any seam position`() {
        val layout = uniform("你好世界，", 20f)
        for (anchor in SeamStripPlan.Anchor.values()) {
            var seam = 0f
            while (seam <= 120f) {
                val plan = SeamStripPlan.staticClear(layout, 0f, seam, anchor, 200f)
                assertNull("seam=$seam anchor=$anchor", plan.straddler)
                // 段带平移有界：≤ 最大单元宽（成组「界，」40px）。
                plan.bands.forEach { assertTrue(abs(it.delta) <= 40f + 0.01f) }
                seam += 0.25f
            }
        }
    }

    @Test
    fun `no straddling unit yields identity plan`() {
        val layout = uniform("你好世界", 20f)
        val plan = SeamStripPlan.staticClear(layout, 0f, 10f, SeamStripPlan.Anchor.HEAD, 200f)
        // 缝在首字内部 → 整行成一段右移（缺口守恒下唯一去向）。
        assertEquals(listOf(SeamStripPlan.Band(0, 4, 10f, 0f)), plan.bands)
        assertNull(plan.straddler)
        // 缝在文本之外 → 全零位移。
        val outside = SeamStripPlan.staticClear(layout, 0f, 95f, SeamStripPlan.Anchor.HEAD, 200f)
        assertEquals(listOf(SeamStripPlan.Band(0, 4, 0f, 0f)), outside.bands)
    }

    @Test
    fun `short English line keeps got together even at an exact letter boundary`() {
        val text = "I want it, I got it"
        val layout = uniform(text, 10f)
        val wordStart = text.indexOf("got")
        for (inWord in listOf(5f, 10f, 15f, 20f, 25f)) {
            val seam = wordStart * 10f + inWord
            val viewport = layout.totalAdvance + 30f
            val plan = SeamStripPlan.staticClear(layout, 0f, seam, SeamStripPlan.Anchor.HEAD, viewport)
            assertEquals(0f, plan.shiftAt(0f), 0f)
            for (i in wordStart until text.length) {
                assertEquals(inWord, plan.shiftAt(i * 10f), 0f)
            }
            assertEquals(seam, wordStart * 10f + plan.shiftAt(wordStart * 10f), 0f)
            assertTrue(layout.totalAdvance + plan.bands.last().delta <= viewport)
            assertNull(plan.straddler)
        }
    }

    @Test
    fun `static word grouping keeps connectors and attached punctuation together`() {
        for (word in listOf("I'm,", "can’t!", "well-known", "“hello!”", "café")) {
            val text = "a $word z"
            val layout = uniform(text, 10f)
            for (i in 1 until word.length) {
                val seam = (2 + i) * 10f
                val plan = SeamStripPlan.staticClear(layout, 0f, seam, SeamStripPlan.Anchor.HEAD, 400f)
                assertEquals("$word at $i", 2, plan.bands.last().charStart)
                assertEquals(seam - 20f, plan.shiftAt(20f), 0f)
                assertEquals(plan.shiftAt(20f), plan.shiftAt((1 + word.length) * 10f), 0f)
                assertNull(plan.straddler)
            }
        }
    }

    @Test
    fun `whole word capacity failure requests scrolling instead of splitting letters`() {
        val layout = uniform("I got it", 10f)
        val plan = SeamStripPlan.staticClear(layout, 0f, 35f, SeamStripPlan.Anchor.HEAD, 90f)
        // 整词需 15px，而尾部只有 10px；不能退回 5px 字符避让假装放得下。
        assertEquals(15f, plan.shiftAt(20f), 0f)
        assertEquals(15f, plan.shiftAt(30f), 0f)
        assertTrue(SpaceGateLineLayout(80f, 90f, layout, 35f).isOverflow)
        assertNull(plan.straddler)
    }

    @Test
    fun `tail anchor keeps the word on the left and the line tail fixed`() {
        val layout = uniform("I got it", 10f)
        val plan = SeamStripPlan.staticClear(layout, 40f, 70f, SeamStripPlan.Anchor.TAIL, 120f)
        assertEquals(-20f, plan.shiftAt(20f), 0f)
        assertEquals(-20f, plan.shiftAt(40f), 0f)
        assertEquals(0f, plan.shiftAt(70f), 0f)
        assertTrue(40f + plan.bands.first().delta >= 0f)
        assertNull(plan.straddler)
    }

    @Test
    fun `center anchor uses the side with room even when the other shift is smaller`() {
        val layout = uniform("I got it", 10f)
        // 左余量 5，右余量 35；左移 10 会裁头，须选右移 20。
        val plan = SeamStripPlan.staticClear(layout, 5f, 45f, SeamStripPlan.Anchor.FREE, 120f)
        assertEquals(0f, plan.shiftAt(0f), 0f)
        assertEquals(20f, plan.shiftAt(20f), 0f)
        assertNull(plan.straddler)
    }

    @Test
    fun `static grouping preserves CJK boundaries and whitespace`() {
        val layout = uniform("你好hello 世界", 10f)
        val plan = SeamStripPlan.staticClear(layout, 0f, 35f, SeamStripPlan.Anchor.HEAD, 200f)
        assertEquals(2, plan.bands.last().charStart)
        assertEquals(15f, plan.shiftAt(20f), 0f)
        assertEquals(1, layout.straddlingStaticUnit(0f, 15f)!!.charStart)
        assertNull(layout.straddlingStaticUnit(0f, 75f))
        // 单词开头/末尾无需插入额外空隙。
        assertTrue(SeamStripPlan.staticClear(layout, 0f, 20f, SeamStripPlan.Anchor.HEAD, 200f).bands.all { it.delta == 0f })
        assertTrue(SeamStripPlan.staticClear(layout, 0f, 70f, SeamStripPlan.Anchor.HEAD, 200f).bands.all { it.delta == 0f })
    }

    @Test
    fun `word avoidance uses measured advances and accepts an exact fit`() {
        val layout = widths("a got z", floatArrayOf(7f, 3f, 9f, 8f, 4f, 3f, 6f))
        val plan = SeamStripPlan.staticClear(layout, 0f, 19f, SeamStripPlan.Anchor.HEAD, 49f)
        assertEquals(9f, plan.shiftAt(10f), 0f)
        assertEquals(49f, layout.totalAdvance + plan.bands.last().delta, 0f)
        assertNull(plan.straddler)
        // 滚动仍为单字符渐隐，不能整词遮挡或等待整词通过。
        assertEquals(3, SeamStripPlan.transit(layout, 0f, 23f).straddler!!.unit.charStart)
        assertEquals(4, SeamStripPlan.transit(layout, 0f, 23f).straddler!!.unit.charEnd)
    }

    @Test
    fun `one timing group containing several words is drawn with each band shift`() {
        val text = "I want it, I got it"
        val layout = uniform(text, 10f)
        val wordStart = text.indexOf("got")
        val plan = SeamStripPlan.staticClear(layout, 0f, wordStart * 10f + 10f, SeamStripPlan.Anchor.HEAD, 240f)
        val runs = mutableListOf<Triple<Int, Int, Float>>()
        plan.forEachWordRun(
            FloatArray(text.length) { it * 10f }, FloatArray(text.length) { (it + 1) * 10f },
        ) { start, end, shift, fading ->
            assertEquals(false, fading)
            runs.add(Triple(start, end, shift))
        }
        assertEquals(listOf(Triple(0, wordStart, 0f), Triple(wordStart, text.length, 10f)), runs)
        val drawnShifts = FloatArray(text.length)
        for ((start, end, shift) in runs) for (i in start until end) drawnShifts[i] = shift
        for (i in text.indices) assertEquals(plan.shiftAt(i * 10f), drawnShifts[i], 0f)
    }

    // ---- 溢出行：两端避让＋间隙随滚动线性合拢 ----

    /** 「你好世界啊」100px 宽，视口 50px：s0=0、sEnd=−50；缝 45 → 驻留跨缝=字2（δR0=5）、停驻跨缝=字4（δL=−5）。 */
    private fun overflowPlan(origin: Float): SeamStripPlan =
        SeamStripPlan.scrollWithEndpoints(uniform("你好世界啊", 20f), origin, 45f, 0f, -50f)

    @Test
    fun `scrolling secondary line keeps whole English words at both endpoints`() {
        val text = "aa hello world zz"
        for (unit in listOf(6f, 10f, 13.5f)) {
            val layout = uniform(text, unit)
            val travel = 7f * unit
            // 含精确字母边界及字母内部，不能只检查跨缝字符。
            for (seamUnits in listOf(4f, 4.5f, 5f, 6f, 6.5f)) {
                val seam = seamUnits * unit
                val head = SeamStripPlan.scrollWithEndpoints(layout, 0f, seam, 0f, -travel)
                val tail = SeamStripPlan.scrollWithEndpoints(layout, -travel, seam, 0f, -travel)
                assertEquals(0f, head.shiftAt(0f), 0f)
                assertEquals(0f, tail.shiftAt((text.length - 1) * unit), 0f)
                val headShift = head.shiftAt(3f * unit)
                val tailShift = tail.shiftAt(9f * unit)
                for (i in 3..7) assertEquals(headShift, head.shiftAt(i * unit), 0f)
                for (i in 9..13) assertEquals(tailShift, tail.shiftAt(i * unit), 0f)
                assertEquals(seam, 3f * unit + headShift, 1e-4f)
                assertEquals(seam, -travel + 14f * unit + tailShift, 1e-4f)
                assertNull(head.straddler)
                assertNull(tail.straddler)
            }
        }
    }

    @Test
    fun `same word across both endpoints does not cancel the endpoint avoidance`() {
        val layout = uniform("aa hello bb", 10f)
        for (travel in listOf(0.01f, 1f, 10f, 20f)) {
            val head = SeamStripPlan.scrollWithEndpoints(layout, 0f, 45f, 0f, -travel)
            val tail = SeamStripPlan.scrollWithEndpoints(layout, -travel, 45f, 0f, -travel)
            assertEquals(0f, head.shiftAt(0f), 1e-4f)
            assertEquals(0f, tail.shiftAt(100f), 1e-4f)
            assertEquals(45f, 30f + head.shiftAt(30f), 1e-4f)
            assertEquals(45f, -travel + 80f + tail.shiftAt(70f), 1e-4f)
            for (i in 3..7) {
                assertEquals(head.shiftAt(30f), head.shiftAt(i * 10f), 1e-4f)
                assertEquals(tail.shiftAt(30f), tail.shiftAt(i * 10f), 1e-4f)
            }
            assertNull(head.straddler)
            assertNull(tail.straddler)
        }
    }

    @Test
    fun `short endpoint windows remain continuous without overlapping text`() {
        val layout = uniform("aa hello bb", 10f)
        val travel = 10f
        val step = 0.01f
        var previous: FloatArray? = null
        for (frame in 0..1000) {
            val origin = -frame * step
            val plan = SeamStripPlan.scrollWithEndpoints(layout, origin, 45f, 0f, -travel)
            for (i in 1 until plan.bands.size) {
                assertTrue(plan.bands[i].delta >= plan.bands[i - 1].delta)
            }
            val drawn = FloatArray(11) { origin + it * 10f + plan.shiftAt(it * 10f) }
            previous?.let { before ->
                // 总避让 15+25=40，行程 10；连续斜率上界 1+40/10=5。
                for (i in drawn.indices) assertTrue(abs(drawn[i] - before[i]) <= 5f * step + 1e-4f)
            }
            for (i in 1 until drawn.size) assertTrue(drawn[i] >= drawn[i - 1] + 10f - 1e-4f)
            plan.straddler?.let { assertEquals(1, it.unit.charEnd - it.unit.charStart) }
            previous = drawn
        }
    }

    @Test
    fun `endpoint word boundaries include connectors and punctuation`() {
        for (word in listOf("I'm,", "can’t!", "well-known", "“hello!”")) {
            val text = "aa $word bb"
            val layout = uniform(text, 10f)
            val seam = 45f
            val travel = 5f
            val head = SeamStripPlan.scrollWithEndpoints(layout, 0f, seam, 0f, -travel)
            val tail = SeamStripPlan.scrollWithEndpoints(layout, -travel, seam, 0f, -travel)
            assertEquals(seam, 30f + head.shiftAt(30f), 1e-4f)
            assertEquals(seam, -travel + (3 + word.length) * 10f + tail.shiftAt(30f), 1e-4f)
            for (i in 3 until 3 + word.length) {
                assertEquals(head.shiftAt(30f), head.shiftAt(i * 10f), 1e-4f)
                assertEquals(tail.shiftAt(30f), tail.shiftAt(i * 10f), 1e-4f)
            }
            assertNull(head.straddler)
            assertNull(tail.straddler)
        }
    }

    @Test
    fun `blank endpoint needs no displacement while the other still clears a word`() {
        val layout = uniform("aa hello world zz", 10f)
        val head = SeamStripPlan.scrollWithEndpoints(layout, 0f, 25f, 0f, -80f)
        val tail = SeamStripPlan.scrollWithEndpoints(layout, -80f, 25f, 0f, -80f)
        assertTrue(head.bands.all { it.delta == 0f })
        assertEquals(25f, -80f + 140f + tail.shiftAt(90f), 1e-4f)
        assertEquals(0f, tail.shiftAt(160f), 0f)
        assertNull(tail.straddler)
    }

    @Test
    fun `English scroll middle still fades one character and is seek deterministic`() {
        val layout = uniform("aa hello world zz", 10f)
        val first = SeamStripPlan.scrollWithEndpoints(layout, -35f, 60f, 0f, -70f)
        assertTrue(first.bands.all { it.delta == 0f })
        assertEquals(9, first.straddler!!.unit.charStart)
        assertEquals(10, first.straddler.unit.charEnd)
        SeamStripPlan.scrollWithEndpoints(layout, -70f, 60f, 0f, -70f)
        assertEquals(first, SeamStripPlan.scrollWithEndpoints(layout, -35f, 60f, 0f, -70f))
    }

    @Test
    fun `multiword timing groups keep band positions when the transition starts fading`() {
        val layout = uniform("aa hello bb", 10f)
        val starts = FloatArray(11) { it * 10f }
        val ends = FloatArray(11) { (it + 1) * 10f }
        for (origin in listOf(-2.5f, -7.5f)) {
            val plan = SeamStripPlan.scrollWithEndpoints(layout, origin, 45f, 0f, -10f)
            val ranges = mutableListOf<Pair<Int, Int>>()
            val shifts = mutableListOf<Float>()
            val fades = mutableListOf<Boolean>()
            plan.forEachWordRun(starts, ends) { start, end, shift, fading ->
                ranges.add(start to end)
                shifts.add(shift)
                fades.add(fading)
                if (fading) {
                    // fadeAt 在已平移的局部坐标中；画布必须先位移，再应用裁剪。
                    assertEquals(45f, origin + shift + plan.fadeAt(origin, shift)!!.seamLocalX, 1e-4f)
                }
            }
            if (origin == -2.5f) {
                assertEquals(listOf(0 to 3, 3 to 4, 4 to 5, 5 to 11), ranges)
                assertEquals(listOf(0f, 5f, 5f, 5f), shifts.map { kotlin.math.round(it) })
                assertEquals(listOf(false, false, true, false), fades)
            } else {
                assertEquals(listOf(0 to 6, 6 to 7, 7 to 8, 8 to 11), ranges)
                assertEquals(listOf(-15f, -15f, -15f, 0f), shifts.map { kotlin.math.round(it) })
                assertEquals(listOf(false, true, false, false), fades)
            }
        }
    }

    @Test
    fun `overflow residence equals static head avoidance`() {
        val layout = uniform("你好世界啊", 20f)
        assertEquals(
            SeamStripPlan.staticClear(layout, 0f, 45f, SeamStripPlan.Anchor.HEAD, 200f).bands,
            overflowPlan(0f).bands,
        )
        assertNull(overflowPlan(0f).straddler)
    }

    @Test
    fun `overflow stop equals static tail avoidance`() {
        val layout = uniform("你好世界啊", 20f)
        assertEquals(
            SeamStripPlan.staticClear(layout, -50f, 45f, SeamStripPlan.Anchor.TAIL, 200f).bands,
            overflowPlan(-50f).bands,
        )
        assertNull(overflowPlan(-50f).straddler)
    }

    @Test
    fun `head gap stays linear while the tail starts earlier and settles gently`() {
        // 驻留端：extraR = δR0 + origin，origin∈[−5,0] 线性合拢。
        assertEquals(5f, overflowPlan(0f).bands.last().delta, 0f)
        assertEquals(2.5f, overflowPlan(-2.5f).bands.last().delta, 0f)
        assertEquals(0f, overflowPlan(-5f).bands.last().delta, 0f)
        // 中段（两窗均不活跃，−35<origin<−5）：纯过缝，无段带位移。
        assertTrue(overflowPlan(-20f).bands.all { it.delta == 0f })
        // 停驻端利用 3 倍距离缓入缓出，原来最后 5px 才突然开始的位移已提前。
        assertEquals(0f, overflowPlan(-35f).bands.first().delta, 0f)
        assertTrue(overflowPlan(-40f).bands.first().delta < 0f)
        assertEquals(-5f, overflowPlan(-50f).bands.first().delta, 0f)
        val entryStep = -overflowPlan(-35.25f).bands.first().delta
        val finalStep = overflowPlan(-49.75f).bands.first().delta + 5f
        assertTrue("entry must not kick", entryStep < 0.025f)
        assertTrue("tail must not slam to a stop", finalStep < 0.025f)
    }

    @Test
    fun `tail easing never increases the old peak displacement even with tiny travel`() {
        for (text in listOf("aa hello world zz", "a extraordinary b", "你好世界你好世界")) {
            val layout = uniform(text, 10f)
            for (travel in listOf(0.1f, 1f, 10f, 35f, 70f)) {
                val view = layout.totalAdvance - travel
                for (ratio in listOf(0.3f, 0.5f, 0.8f)) {
                    val seam = view * ratio
                    val head = layout.straddlingStaticUnit(0f, seam)
                    val tail = layout.straddlingStaticUnit(-travel, seam) ?: continue
                    val headDistance = head?.let { seam - it.start } ?: 0f
                    val tailDistance = tail.end - travel - seam
                    // The old non-overlapping linear windows shared this peak slope.
                    val oldPeak = maxOf(1f, (headDistance + tailDistance) / travel)
                    val delta = travel / 500f
                    var previous = FloatArray(text.length) { i ->
                        SeamStripPlan.scrollWithEndpoints(layout, 0f, seam, 0f, -travel).shiftAt(i * 10f)
                    }
                    for (frame in 1..500) {
                        val origin = -travel * frame / 500f
                        val plan = SeamStripPlan.scrollWithEndpoints(layout, origin, seam, 0f, -travel)
                        for (i in text.indices) {
                            val shift = plan.shiftAt(i * 10f)
                            val moved = previous[i] - shift
                            assertTrue("reverse $text travel=$travel seam=$seam", moved >= -0.0001f)
                            assertTrue("stronger kick $text travel=$travel seam=$seam: $moved", moved <= oldPeak * delta + 0.0002f)
                            previous[i] = shift
                        }
                    }
                    val stopped = SeamStripPlan.scrollWithEndpoints(layout, -travel, seam, 0f, -travel)
                    assertNull(stopped.straddler)
                    assertEquals(seam, -travel + tail.end + stopped.shiftAt(tail.end - 0.01f), 0.001f)
                }
            }
        }
    }

    @Test
    fun `drawn positions are continuous and non-overlapping across the whole scroll`() {
        val layout = uniform("你好世界啊", 20f)
        val step = 0.25f
        var origin = 0f
        var prev: FloatArray? = null
        while (origin >= -50f) {
            val plan = SeamStripPlan.scrollWithEndpoints(layout, origin, 45f, 0f, -50f)
            // 段带平移沿文本方向单调不减（段间只开间隙、永不重叠）。
            for (i in 1 until plan.bands.size) {
                assertTrue(plan.bands[i].delta >= plan.bands[i - 1].delta)
            }
            val drawn = FloatArray(5) { k -> origin + plan.shiftAt(k * 20f) + k * 20f }
            for (k in 0 until 4) {
                assertTrue("origin=$origin", drawn[k + 1] >= drawn[k])
            }
            prev?.let { before ->
                // 零跳变：任一单元的绘制位置随 offset 连续变化（≤2×步进＝滚动＋间隙合拢速率之和）。
                for (k in 0 until 5) {
                    assertTrue(
                        "origin=$origin k=$k moved=${abs(drawn[k] - before[k])}",
                        abs(drawn[k] - before[k]) <= 2f * step + 1e-4f,
                    )
                }
            }
            prev = drawn
            origin -= step
        }
    }

    @Test
    fun `plan is deterministic for identical inputs`() {
        // 主从两槽共享同一布局实例（视图分发），同输入必得同方案。
        val layout = uniform("你好世界啊", 20f)
        assertEquals(
            SeamStripPlan.scrollWithEndpoints(layout, -12.5f, 45f, 0f, -50f),
            SeamStripPlan.scrollWithEndpoints(layout, -12.5f, 45f, 0f, -50f),
        )
    }

    // ---- 滚动过缝：边缘滑过＋渐隐 ----

    @Test
    fun `transit fades the straddling unit by coverage depth`() {
        val layout = uniform("AAAA", 10f)
        // 缝在字2 [20,30) 内：f=0.1 → alpha=0.8、多数侧=右；f=0.9 → alpha=0.8、多数侧=左。
        with(SeamStripPlan.transit(layout, 0f, 21f).straddler!!) {
            assertEquals(2, unit.charStart)
            assertEquals(0.8f, alpha, 1e-4f)
            assertEquals(false, majorityLeft)
        }
        with(SeamStripPlan.transit(layout, 0f, 29f).straddler!!) {
            assertEquals(0.8f, alpha, 1e-4f)
            assertEquals(true, majorityLeft)
        }
        // 正中：alpha=0（渐入渐出连续，无整字消失瞬间）；边界：不跨缝。
        assertEquals(0f, SeamStripPlan.transit(layout, 0f, 25f).straddler!!.alpha, 1e-4f)
        assertNull(SeamStripPlan.transit(layout, 0f, 20f).straddler)
        assertNull(SeamStripPlan.transit(layout, 0f, 30f).straddler)
    }

    @Test
    fun `fade alpha is continuous while a unit crosses the seam`() {
        val layout = uniform("AAAA", 10f)
        var prevAlpha = 1f
        var seam = 20.5f
        while (seam < 30f) {
            val alpha = SeamStripPlan.transit(layout, 0f, seam).straddler?.alpha
            if (alpha != null) {
                assertTrue(abs(alpha - prevAlpha) <= 2f * 0.25f + 1e-4f)
                prevAlpha = alpha
            }
            seam += 0.25f
        }
    }

    // ---- 避让位移查询（视图 isOverflow 用） ----

    @Test
    fun `right shift to clear is positive only while straddling`() {
        val layout = uniform("你好世界", 20f)
        var seam = 0f
        while (seam <= 90f) {
            val straddling = layout.straddlingUnit(0f, seam)
            val shift = layout.rightShiftToClear(0f, seam)
            val left = layout.leftShiftToClear(0f, seam)
            if (straddling == null) {
                assertEquals("seam=$seam", 0f, shift, 0f)
                assertEquals("seam=$seam", 0f, left, 0f)
            } else {
                assertTrue("seam=$seam", shift > 0f)
                assertTrue("seam=$seam", left < 0f)
                // 右移量 − 左移量 ＝ 跨缝单元宽（uniform＝20px）。
                assertEquals("seam=$seam", 20f, shift - left, 1e-4f)
            }
            seam += 0.25f
        }
    }
}
