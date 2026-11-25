/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.view.NextLinePromotionMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodLyricTransitionPolicyTest {

    private fun rows(
        main: String = "",
        translation: String = "",
        backing: String = "",
        next: String = "",
    ): Map<AodLyricRow, String> = AodLyricRow.entries.associateWith { "" } + mapOf(
        AodLyricRow.MAIN to main,
        AodLyricRow.TRANSLATION to translation,
        AodLyricRow.BACKING to backing,
        AodLyricRow.NEXT to next,
    )

    private fun decide(
        old: Map<AodLyricRow, String> = rows(main = "line one", next = "line two"),
        new: Map<AodLyricRow, String> = rows(main = "line two", next = "line three"),
        canAnimate: Boolean = true,
        lyricScroll: Boolean = true,
        interludeDots: Boolean = true,
        wasInterlude: Boolean = false,
        nowInterlude: Boolean = false,
        lineAdvanced: Boolean = true,
    ) = AodLyricTransitionPolicy.decide(
        canAnimate, lyricScroll, interludeDots, wasInterlude, nowInterlude, lineAdvanced, old, new,
    )

    @Test
    fun `shown next line becoming the main line promotes`() {
        assertEquals(AodTransitionKind.PROMOTE, decide())
    }

    @Test
    fun `translation or pronunciation rows no longer block the promotion`() {
        assertEquals(
            AodTransitionKind.PROMOTE,
            decide(
                old = rows(main = "line one", translation = "第一句", next = "line two"),
                new = rows(main = "line two", translation = "第二句", next = "line three"),
            ),
        )
        assertEquals(
            AodTransitionKind.PROMOTE,
            decide(
                old = rows(main = "line one", backing = "ooh", next = "line two"),
                new = rows(main = "line two", next = "line three"),
            ),
        )
    }

    @Test
    fun `line change without a matching preview floats the whole block`() {
        // 关了「显示下一句」
        assertEquals(
            AodTransitionKind.BLOCK,
            decide(old = rows(main = "line one", translation = "一"), new = rows(main = "line two")),
        )
        // 切歌、拖进度：预览对不上
        assertEquals(
            AodTransitionKind.BLOCK,
            decide(new = rows(main = "another song", next = "x")),
        )
    }

    @Test
    fun `lyric scroll off never animates line changes`() {
        assertEquals(AodTransitionKind.NONE, decide(lyricScroll = false))
        assertEquals(
            AodTransitionKind.NONE,
            decide(
                old = rows(main = "a", next = "b"),
                new = rows(main = "a", translation = "甲", next = "b"),
                lineAdvanced = false,
                lyricScroll = false,
            ),
        )
    }

    @Test
    fun `entering an interlude fades the dots in while the old block floats out`() {
        assertEquals(
            AodTransitionKind.INTERLUDE_ENTER,
            decide(
                old = rows(main = "line one", translation = "一", next = "line two"),
                new = rows(main = "•••", next = "line two"),
                nowInterlude = true,
            ),
        )
        // 灵动圆点开着时与「歌词滚动」无关，和原来一致
        assertEquals(
            AodTransitionKind.INTERLUDE_ENTER,
            decide(new = rows(main = "•••", next = "line two"), nowInterlude = true, lyricScroll = false),
        )
    }

    @Test
    fun `interlude without dots falls back to the block float and does not repeat`() {
        val old = rows(main = "line one", next = "line two")
        val interlude = rows(main = "•••", next = "line two")
        assertEquals(
            AodTransitionKind.BLOCK,
            decide(old = old, new = interlude, nowInterlude = true, interludeDots = false),
        )
        // 之后每次刷新内容不变，不能再触发
        assertEquals(
            AodTransitionKind.NONE,
            decide(old = interlude, new = interlude, nowInterlude = true, interludeDots = false, lineAdvanced = false),
        )
    }

    @Test
    fun `leaving an interlude promotes the preview together with its translation`() {
        assertEquals(
            AodTransitionKind.PROMOTE,
            decide(
                old = rows(main = "•••", next = "line two"),
                new = rows(main = "line two", translation = "第二句", next = "line three"),
                wasInterlude = true,
            ),
        )
    }

    @Test
    fun `row changes without a line change cross fade only that row`() {
        assertEquals(
            AodTransitionKind.ROW_FADE,
            decide(
                old = rows(main = "a", next = "b"),
                new = rows(main = "a", translation = "甲", next = "b"),
                lineAdvanced = false,
            ),
        )
        assertEquals(
            AodTransitionKind.ROW_FADE,
            decide(
                old = rows(main = "last line", next = "b"),
                new = rows(main = "last line", next = "下一首：Song - Artist"),
                lineAdvanced = false,
            ),
        )
        assertEquals(
            AodTransitionKind.NONE,
            decide(old = rows(main = "a", next = "b"), new = rows(main = "a", next = "b"), lineAdvanced = false),
        )
    }

    @Test
    fun `nothing shown yet or not animatable applies directly`() {
        assertEquals(AodTransitionKind.NONE, decide(old = rows()))
        assertEquals(AodTransitionKind.NONE, decide(canAnimate = false))
    }

    @Test
    fun `promotion plan moves the line block together and floats the line after next in`() {
        fun plan(row: AodLyricRow, old: String, new: String) =
            AodLyricTransitionPolicy.plan(AodTransitionKind.PROMOTE, row, old, new)
        assertEquals(AodRowPlan(AodRowEnter.PROMOTED, AodRowExit.EXIT_UP), plan(AodLyricRow.MAIN, "a", "b"))
        assertEquals(AodRowPlan(AodRowEnter.RIDE, AodRowExit.EXIT_UP), plan(AodLyricRow.TRANSLATION, "甲", "乙"))
        assertEquals(AodRowPlan(AodRowEnter.RIDE, AodRowExit.NONE), plan(AodLyricRow.TRANSLATION, "", "乙"))
        assertEquals(AodRowPlan(AodRowEnter.NONE, AodRowExit.EXIT_UP), plan(AodLyricRow.TRANSLATION, "甲", ""))
        assertEquals(AodRowPlan(AodRowEnter.ENTER, AodRowExit.PROMOTE_SOURCE), plan(AodLyricRow.NEXT, "b", "c"))
    }

    @Test
    fun `interlude plan fades the dots and keeps an unchanged next line moving`() {
        fun plan(row: AodLyricRow, old: String, new: String) =
            AodLyricTransitionPolicy.plan(AodTransitionKind.INTERLUDE_ENTER, row, old, new)
        assertEquals(AodRowPlan(AodRowEnter.FADE_IN, AodRowExit.EXIT_UP), plan(AodLyricRow.MAIN, "a", "•••"))
        assertEquals(AodRowPlan(AodRowEnter.NONE, AodRowExit.EXIT_UP), plan(AodLyricRow.TRANSLATION, "甲", ""))
        assertEquals(AodRowPlan(AodRowEnter.MOVE, AodRowExit.NONE), plan(AodLyricRow.NEXT, "b", "b"))
        assertEquals(AodRowPlan(AodRowEnter.FADE_IN, AodRowExit.FADE_OUT), plan(AodLyricRow.NEXT, "b", "c"))
    }

    @Test
    fun `block and row fade plans`() {
        assertEquals(
            AodRowPlan(AodRowEnter.ENTER, AodRowExit.EXIT_UP),
            AodLyricTransitionPolicy.plan(AodTransitionKind.BLOCK, AodLyricRow.NEXT, "b", "c"),
        )
        assertEquals(
            AodRowPlan(AodRowEnter.MOVE, AodRowExit.NONE),
            AodLyricTransitionPolicy.plan(AodTransitionKind.ROW_FADE, AodLyricRow.MAIN, "a", "a"),
        )
        assertEquals(
            AodRowPlan(AodRowEnter.FADE_IN, AodRowExit.NONE),
            AodLyricTransitionPolicy.plan(AodTransitionKind.ROW_FADE, AodLyricRow.TRANSLATION, "", "甲"),
        )
        assertEquals(
            AodRowPlan(AodRowEnter.NONE, AodRowExit.NONE),
            AodLyricTransitionPolicy.plan(AodTransitionKind.NONE, AodLyricRow.MAIN, "a", "b"),
        )
    }

    @Test
    fun `timings and scale share the island promotion motion`() {
        assertEquals(NextLinePromotionMotion.DURATION_MS, AodLyricTransitionPolicy.MOVE_DURATION_MS)
        assertEquals(NextLinePromotionMotion.PREVIEW_FADE_DURATION_MS, AodLyricTransitionPolicy.FADE_DURATION_MS)
        assertEquals(1f / 1.5f, AodLyricTransitionPolicy.promotedStartScale(30f, 20f), 0.001f)
        assertEquals(0.5f, AodLyricTransitionPolicy.promotedStartScale(100f, 10f), 0.001f)
        assertEquals(
            NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_HEIGHT_ANIMATION_MS,
            AodLyricTransitionPolicy.MOVE_DURATION_MS,
        )
    }

    @Test
    fun `easing matches accelerate decelerate and fades finish early`() {
        assertEquals(0f, AodLyricTransitionPolicy.ease(0f), 0.0001f)
        assertEquals(0.5f, AodLyricTransitionPolicy.ease(0.5f), 0.0001f)
        assertEquals(1f, AodLyricTransitionPolicy.ease(1f), 0.0001f)
        val fadeEnd = AodLyricTransitionPolicy.FADE_DURATION_MS.toFloat() /
            AodLyricTransitionPolicy.MOVE_DURATION_MS
        assertEquals(1f, AodLyricTransitionPolicy.fadeProgress(fadeEnd), 0.0001f)
        assertTrue(AodLyricTransitionPolicy.moveProgress(fadeEnd) < 1f)
    }

    @Test
    fun `cross fade between preview and promoted line never dips`() {
        for (step in 0..20) {
            val move = step / 20f
            val inAlpha = AodLyricTransitionPolicy.crossfadeIn(move)
            val outAlpha = AodLyricTransitionPolicy.crossfadeOut(move)
            assertTrue(maxOf(inAlpha, outAlpha) >= 0.999f)
        }
        assertEquals(0f, AodLyricTransitionPolicy.crossfadeIn(0f), 0f)
        assertEquals(0f, AodLyricTransitionPolicy.crossfadeOut(1f), 0f)
        assertFalse(AodLyricTransitionPolicy.rowsDiffer(rows(main = "a"), rows(main = "a")))
    }
}
