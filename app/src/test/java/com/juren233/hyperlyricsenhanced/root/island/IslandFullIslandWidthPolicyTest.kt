/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.island.IslandDynamicLimitPolicy.Geometry
import com.juren233.hyperlyricsenhanced.lyric.view.line.SeamOcclusionLayout
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGateLineLayout
import org.junit.Assert.*
import org.junit.Test

class IslandFullIslandWidthPolicyTest {
    private val phone = Geometry(720, 320, 320, 240)

    @Test fun `short independent content follows longer side including cover and rhythm`() {
        val left = demand(area = 500, text = 390, content = 72) // cover plus padding: 110
        val right = demand(area = 480, text = 450, content = 90) // rhythm plus padding: 30
        assertEquals(Geometry(444, 182, 182, 378), shrink(left, right))
    }

    @Test fun `long content cannot enlarge the already limited native result`() {
        assertEquals(phone, shrink(1000, 1500))
        assertEquals(Geometry(720, 320, 320, 240), shrink(100, 320))
    }

    @Test fun `empty content keeps cutout and pill floor`() {
        assertEquals(Geometry(110, 15, 15, 545), shrink(0, 0))
        assertEquals(Geometry(112, 16, 16, 544), shrink(0, 0, floor = 111))
    }

    @Test fun `native floor below requested minimum is never enlarged`() {
        val native = Geometry(100, 10, 10, 550)
        assertEquals(native, IslandFullIslandWidthPolicy.shrink(native, 0, 0, 1200, 110, false))
    }

    @Test fun `tablet keeps independent side widths and native centering formula`() {
        val original = Geometry(900, 380, 500, 150)
        assertEquals(Geometry(290, 90, 180, 455),
            IslandFullIslandWidthPolicy.shrink(original, 90, 180, 1200, 110, true))
        val empty = IslandFullIslandWidthPolicy.shrink(original, 0, 0, 1200, 110, true)
        assertEquals(110, empty.width)
        assertEquals(20, empty.width - empty.left - empty.right)
        assertEquals((1200 - empty.width) / 2, empty.x)
    }

    @Test fun `asymmetric phone alternative and invalid native geometry are retained`() {
        for (native in listOf(Geometry(344, 158, 106, 402), Geometry(80, 60, 60, 560))) {
            assertEquals(native, IslandFullIslandWidthPolicy.shrink(native, 20, 30, 1200, 110, false))
        }
    }

    @Test fun `capacity excludes nontext overhead and custom content cap`() {
        assertEquals(290, capacity(areaLimit = 320, area = 480, text = 450))
        assertEquals(100, capacity(areaLimit = 320, area = 130, text = 100))
        assertEquals(0, capacity(areaLimit = 20, area = 480, text = 450))
        assertEquals(130, demand(area = 130, text = 100, content = 800))
    }

    @Test fun `shrunk layout does not reject a longer line that fits original capacity`() {
        val nativeCapacity = capacity(320, 480, 450)
        val short = shrink(100, demand(480, 450, 90))
        val shortViewport = short.right - 30
        assertEquals(90, shortViewport)
        assertTrue(230 > shortViewport)
        assertTrue(isShort(230, nativeCapacity))
        val longer = shrink(100, demand(480, 450, 230))
        assertEquals(260, longer.right)
        assertEquals(600, longer.width)
        assertFalse(isShort(291, nativeCapacity))
        // Repeating a width refresh from the unshrunk native calculation is idempotent.
        repeat(5) { assertEquals(longer, shrink(100, demand(480, 450, 230))) }
    }

    @Test fun `native limit change reclassifies current line and recovers when space returns`() {
        assertTrue(isShort(230, capacity(320, 480, 450)))
        assertFalse(isShort(230, capacity(240, 480, 450)))
        assertTrue(isShort(230, capacity(320, 480, 450)))
    }

    @Test fun `all tested demands preserve bounds floor symmetry cutout and centered x`() {
        for (left in 0..400 step 13) for (right in 0..400 step 17) {
            val result = shrink(left, right)
            assertTrue(result.width in 110..phone.width)
            assertEquals(result.left, result.right)
            assertEquals(80, result.width - result.left - result.right)
            assertEquals((1200 - result.width) / 2, result.x)
            assertTrue(result.left >= minOf(320, maxOf(left, right)))
        }
    }

    @Test fun `continuous band is measured once and split symmetrically`() {
        // 300px of lyric, 110px cover/padding on the left, 30px rhythm/padding on the right.
        assertEquals(220, IslandFullIslandWidthPolicy.continuousSide(300, 110, 30) { 0 })
        // Side never drops below either overhead, even for an empty band.
        assertEquals(110, IslandFullIslandWidthPolicy.continuousSide(0, 110, 30) { 0 })
    }

    @Test fun `continuous band reserves the seam avoidance at the resulting seam`() {
        val seams = mutableListOf<Int>()
        val side = IslandFullIslandWidthPolicy.continuousSide(300, 110, 30) { seam ->
            seams += seam
            if (seam < 120) 24 else 0
        }
        assertEquals(110, seams.first()) // 220 - 110
        // 最小满足者：seam 120 起不再跨缝，2 * 230 - 140 = 320 >= 300。
        assertEquals(230, side)
    }

    @Test fun `lone You from the 1024 log gets a side wide enough for the renderer to stay static`() {
        // 日志 hyperlyricsenhanced_all_logs_20261125_1024，10:24:00.694：
        // IslandFullWidth continuous demand=148/148 capacity=Geometry(width=640, left=280, right=280)；
        // 随后 IslandScroll sync right=true content=55.0 scrollW=126.01953 vw=72 seam=36.0。
        // 单侧 148、左槽文字宽 36 → 两侧固定占用各 112；"You" 模型宽 55、单元 advance 合计 54.02。
        // 单字 advance 拆分是合成的（日志只有总宽）；真实字体下的 148 由 FullIslandShortWordWidthTest 复现。
        val layout = requireNotNull(SeamOcclusionLayout.build("You", floatArrayOf(19.6f, 17.8f, 16.62f)))
        val device = SpaceGateLineLayout(55f, 72f, layout, 36f)
        assertTrue("先复现真机：72px 带宽、缝 36 时绘制端判超宽", device.isOverflow)
        assertEquals(126.02f, device.scrollWidth, 0.01f)

        val side = IslandFullIslandWidthPolicy.continuousSide(55, 112, 112) { seam ->
            kotlin.math.ceil(layout.staticRightShiftToClear(0f, seam.toFloat())).toInt()
        }
        val geometry = SpaceGateLineLayout(55f, (2 * side - 224).toFloat(), layout, (side - 112).toFloat())
        assertFalse("单侧 $side 下 You 仍会在夹缝里滚动", geometry.isOverflow)
        // 日志容量单侧 280：岛确实能撑到这个宽度。
        assertEquals(side, IslandFullIslandWidthPolicy.shrink(Geometry(640, 280, 280, 280), side, side, 1200, 110, false).right)
    }

    @Test fun `gapless override turns the native cutout into symmetric side capacity`() {
        // 2026-11-19 landscape: limiter result with the 61px fallback cutout.
        val native = Geometry(1924, 931, 932, 373)
        val symmetric = Geometry(1923, 931, 931, 373)
        val result = IslandFullIslandWidthPolicy.shrink(symmetric, 400, 400, 2670, 110, false, fixedOverride = 0)
        assertEquals(Geometry(800, 400, 400, 935), result)
        assertEquals(native, IslandFullIslandWidthPolicy.shrink(native, 400, 400, 2670, 110, false, fixedOverride = 0))
        // Demand beyond the freed capacity is capped by it: each side gains half the cutout.
        assertEquals(Geometry(1922, 961, 961, 374),
            IslandFullIslandWidthPolicy.shrink(symmetric, 990, 990, 2670, 110, false, fixedOverride = 0))
    }

    private fun shrink(left: Int, right: Int, floor: Int = 110) =
        IslandFullIslandWidthPolicy.shrink(phone, left, right, 1200, floor, false)
    private fun demand(area: Int, text: Int, content: Int) =
        IslandFullIslandWidthPolicy.requiredArea(area, text, content)
    private fun capacity(areaLimit: Int, area: Int, text: Int) =
        IslandFullIslandWidthPolicy.contentCapacity(areaLimit, area, text)
    private fun isShort(content: Int, capacity: Int) = IslandShortLyricPolicy.usesSongInfo(
        RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND, true, content, capacity)
}
