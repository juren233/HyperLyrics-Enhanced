/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.island.IslandDynamicLimitPolicy.Geometry
import com.juren233.hyperlyricsenhanced.root.island.IslandDynamicLimitPolicy.Span
import org.junit.Assert.*
import org.junit.Test

class IslandDynamicLimitPolicyTest {
    private val phone = Geometry(700, 300, 300, 150)

    @Test fun `phone width is anchored only by time`() {
        val result = IslandDynamicLimitPolicy.limit(phone, 1000,
            listOf(Span(20, 100), Span(130, 250, text = true), Span(800, 980)), 10)
        assertEquals(Geometry(480, 190, 190, 260), result)
        assertTrue(result.x >= 260)
    }

    @Test fun `phone icons on either side never become anchors`() {
        val result = IslandDynamicLimitPolicy.limit(phone, 1000,
            listOf(Span(320, 490), Span(800, 980)), 10)
        assertEquals(phone, result)
    }

    @Test fun `freed space restores original user bounded geometry`() {
        assertEquals(phone, IslandDynamicLimitPolicy.limit(phone, 1000,
            listOf(Span(10, 100, text = true), Span(900, 980)), 10))
        val custom = Geometry(260, 80, 80, 370)
        assertEquals(custom, IslandDynamicLimitPolicy.limit(custom, 1000, emptyList(), 10))
    }

    @Test fun `longer clock shrinks immediately and right icons are irrelevant`() {
        val before = IslandDynamicLimitPolicy.limit(phone, 1000, listOf(Span(0, 100, text = true)), 10)
        val after = IslandDynamicLimitPolicy.limit(phone, 1000,
            listOf(Span(0, 300, text = true), Span(510, 520), Span(700, 990)), 10)
        assertTrue(after.width < before.width)
        assertEquals(380, after.width)
    }

    @Test fun `tablet keeps left anchor and spends spare budget on longer side`() {
        val tablet = Geometry(800, 150, 630, 100)
        val result = IslandDynamicLimitPolicy.limit(tablet, 1000,
            listOf(Span(0, 120), Span(700, 950)), 10, leftAnchor = 160)
        assertEquals(Geometry(530, 150, 360, 235), result)
        assertTrue(160 + result.width <= 690)
    }

    @Test fun `tablet date and time block does not collapse the island`() {
        val tablet = Geometry(800, 150, 630, 100)
        // 平板日期+时间文本块（含分开的日期、时间两个视图）是被岛替代的原生内容，
        // 不参与让位：只有时间块时保持原生几何，长度只由右侧图标封顶。
        val dateOnly = IslandDynamicLimitPolicy.limit(tablet, 1000,
            listOf(Span(160, 360, text = true), Span(380, 520, text = true)), 10, leftAnchor = 160)
        assertEquals(tablet, dateOnly)
        val result = IslandDynamicLimitPolicy.limit(tablet, 1000,
            listOf(Span(160, 360, text = true), Span(380, 520, text = true), Span(700, 950)), 10, leftAnchor = 160)
        assertEquals(Geometry(530, 150, 360, 235), result)
    }

    @Test fun `tablet content after first icon still caps the island`() {
        val tablet = Geometry(800, 150, 630, 100)
        // 第一个非文本图标之后的文本（如通知文字）是真实内容，仍参与右侧封顶。
        val result = IslandDynamicLimitPolicy.limit(tablet, 1000,
            listOf(Span(160, 360, text = true), Span(400, 430), Span(500, 600, text = true)), 10, leftAnchor = 160)
        assertEquals(Geometry(230, 105, 105, 385), result)
    }

    @Test fun `coexisting island uses its own native center`() {
        val paired = Geometry(400, 150, 150, 200)
        assertEquals(Geometry(260, 80, 80, 270),
            IslandDynamicLimitPolicy.limit(paired, 1000, listOf(Span(0, 260, text = true)), 10))
    }

    @Test fun `phone refresh observation excludes every right side item`() {
        val obstacles = listOf(
            Span(20, 100),
            Span(130, 250, text = true),
            Span(700, 760, text = true),
            Span(800, 980),
        )
        assertEquals(
            listOf(Span(130, 250, text = true)),
            IslandDynamicLimitPolicy.relevantObservation(obstacles, 1000),
        )
    }

    @Test fun `small phone time layout jitter is equivalent`() {
        val baseline = listOf(Span(66, 246, text = true))
        assertTrue(IslandDynamicLimitPolicy.observationsEquivalent(
            baseline,
            listOf(Span(70, 251, text = true)),
            coordinateTolerancePx = 8,
        ))
        assertFalse(IslandDynamicLimitPolicy.observationsEquivalent(
            baseline,
            listOf(Span(83, 268, text = true)),
            coordinateTolerancePx = 8,
        ))
    }

    @Test fun `content straddling the anchor is covered natively and never collapses width`() {
        assertEquals(phone, IslandDynamicLimitPolicy.limit(phone, 1000, listOf(Span(490, 510)), 10))
        assertEquals(Geometry(0, 0, 0, 0), IslandDynamicLimitPolicy.limit(
            Geometry(0, 0, 0, 0), 1000, emptyList(), 10))
    }

    @Test fun `tight status bar keeps pill floor instead of shrinking to zero`() {
        val result = IslandDynamicLimitPolicy.limit(phone, 1000, listOf(Span(0, 480, text = true), Span(520, 990)), 10)
        assertEquals(Geometry(100, 0, 0, 450), result)
        val floor = IslandDynamicLimitPolicy.limit(phone, 1000,
            listOf(Span(0, 480, text = true), Span(520, 990)), 10, minWidth = 300)
        assertEquals(Geometry(300, 100, 100, 350), floor)
    }

    @Test fun `phone clock right-edge drift is island-induced and stays equivalent when right drift ignored`() {
        val compressed = listOf(Span(66, 242, text = true))
        val free = listOf(Span(66, 275, text = true))
        assertTrue(IslandDynamicLimitPolicy.observationsEquivalent(compressed, free, 10, ignoreRightDrift = true))
        // 默认（平板路径）右缘仍然参与比较
        assertFalse(IslandDynamicLimitPolicy.observationsEquivalent(compressed, free, 10))
    }

    @Test fun `phone left-edge shift means new status bar content and refreshes even when right drift ignored`() {
        val before = listOf(Span(66, 260, text = true))
        val afterNotification = listOf(Span(120, 300, text = true))
        assertFalse(
            IslandDynamicLimitPolicy.observationsEquivalent(
                before, afterNotification, 10, ignoreRightDrift = true,
            )
        )
    }

    @Test fun `span count change never equivalent regardless of right drift policy`() {
        val one = listOf(Span(66, 260, text = true))
        val two = listOf(Span(66, 260, text = true), Span(300, 360))
        assertFalse(IslandDynamicLimitPolicy.observationsEquivalent(one, two, 10, ignoreRightDrift = true))
    }

    @Test fun `refresh decision keeps width stable through clock compression when right drift ignored`() {
        val baseline = listOf(Span(66, 275, text = true))
        val drifted = listOf(Span(66, 242, text = true))
        assertEquals(
            IslandDynamicLimitRefreshPolicy.Decision.CANCEL,
            IslandDynamicLimitRefreshPolicy.decide(
                enabled = true, current = drifted, baseline = baseline,
                islandWidthSettling = false, coordinateTolerancePx = 10, ignoreRightDrift = true,
            )
        )
        assertFalse(
            IslandDynamicLimitRefreshPolicy.shouldRefresh(
                settled = drifted, baseline = baseline,
                islandWidthSettling = false, coordinateTolerancePx = 10, ignoreRightDrift = true,
            )
        )
    }

    @Test fun `binary profile uses exact descriptors and result constructor order`() {
        assertEquals("com.android.systemui.statusbar.phone.PhoneStatusBarView", IslandDynamicLimitProfile.STATUS_BAR_CLASS)
        assertEquals("onAttachedToWindow", IslandDynamicLimitProfile.ATTACH_METHOD)
        assertEquals("getStatusBarDatePosX", IslandDynamicLimitProfile.DATE_POSITION_GETTER)
        assertEquals("getScreenWidth", IslandDynamicLimitProfile.SCREEN_WIDTH_GETTER)
        assertEquals(listOf("getBigIslandViewWidth", "getBigIslandLeftWidth", "getBigIslandRightWidth",
            "getBigIslandX", "getBigIslandMarginWidth", "getBigIslandViewWidthHasSmallIsland",
            "getBigIslandLeftWidthHasSmallIsland", "getBigIslandRightWidthHasSmallIsland",
            "getBigIslandXHasSmallIsland"), IslandDynamicLimitProfile.RESULT_GETTERS)
        assertFalse(IslandDynamicLimitProfile.STATUS_BAR_CLASS.startsWith("p000"))
    }

    // 2026-11-11 真机日志：网速文本右缘 599 落到中线 600 左侧，被几何选法当成时钟。
    private fun ballObstacles(clockMarked: Boolean) = listOf(
        Span(83, 266, text = true, clock = clockMarked), Span(536, 599, text = true),
        Span(538, 597, text = true), Span(566, 634), Span(599, 667), Span(667, 722),
        Span(722, 773), Span(729, 749), Span(742, 807), Span(807, 869), Span(869, 902),
        Span(880, 945), Span(945, 1010), Span(945, 965), Span(986, 1010, text = true),
        Span(1101, 1127), Span(1137, 1158),
    )
    private val ballOriginal = Geometry(752, 336, 336, 224)

    @Test fun `marked clock anchors phone limit instead of overflowed network speed text`() {
        val result = IslandDynamicLimitPolicy.limit(
            ballOriginal, 1200, ballObstacles(clockMarked = true), gap = 16, minWidth = 110)
        assertEquals(636, result.width)
    }

    @Test fun `without clock marks the geometric fallback is unchanged`() {
        val result = IslandDynamicLimitPolicy.limit(
            ballOriginal, 1200, ballObstacles(clockMarked = false), gap = 16, minWidth = 110)
        assertEquals(110, result.width)
    }

    @Test fun `relevant observation returns the clock span not network speed text`() {
        assertEquals(
            listOf(Span(83, 266, text = true, clock = true)),
            IslandDynamicLimitPolicy.relevantObservation(ballObstacles(clockMarked = true), 1200),
        )
    }

    @Test fun `observations with different clock marks are not equivalent`() {
        assertFalse(IslandDynamicLimitPolicy.observationsEquivalent(
            listOf(Span(83, 266, text = true, clock = true)),
            listOf(Span(83, 266, text = true)),
            coordinateTolerancePx = 8,
            ignoreRightDrift = true,
        ))
    }
}
