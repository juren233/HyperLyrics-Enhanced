/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import android.app.Activity
import android.os.Looper
import android.widget.LinearLayout
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.root.island.IslandFullIslandWidthPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * 全岛连续歌词：单个 "You"（翻译「你～」）明明能把岛向外撑开完整显示，却被算成很窄的宽度、
 * 在夹缝里滚动（日志 hyperlyricsenhanced_all_logs_20261125_1024，10:24:00.694
 * `IslandFullWidth continuous demand=148/148 capacity=Geometry(width=640, left=280, right=280)`，
 * 随后 `IslandScroll sync right=true content=55.0 scrollW=126.01953 vw=72 seam=36.0`）。
 *
 * 由日志还原：单侧区域 148、左槽文字宽 seam=36 → 两侧封面/律动等固定占用各 148-36=112；
 * 两槽文字宽合计 72。这里用真实的两个 SpaceGate 行视图、真实字体度量走生产路径：
 * 动态长度用 measureIndependentContentWidth + staticSeamClearance 求单侧宽，
 * 再把两槽按该宽度排版，绘制端（isOverflow）必须判定能静止完整显示。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class FullIslandShortWordWidthTest {

    private val overhead = 112

    private class Slots(val left: SpaceGateRichLyricLineView, val right: SpaceGateRichLyricLineView, val row: LinearLayout)

    private fun pair(): Slots {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val left = SpaceGateRichLyricLineView(activity)
        val right = SpaceGateRichLyricLineView(activity)
        left.setSpaceGateConfig(false, right)
        right.setSpaceGateConfig(true, left)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(right, LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        activity.setContentView(row)
        idle()
        return Slots(left, right, row)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    /** 两槽文字宽各为 [textWidth]，与岛宽落地后一致。 */
    private fun Slots.layoutSlots(textWidth: Int) {
        listOf(left, right).forEach { it.layoutParams = it.layoutParams.apply { width = textWidth } }
        row.requestLayout()
        idle()
    }

    private fun Slots.bind(line: RichLyricLine) {
        left.line = line
        right.line = line
        idle()
    }

    /** 生产路径（IslandFullIslandDynamicWidth 连续文本带分支）求出的单侧区域宽。 */
    private fun Slots.demandSide(line: RichLyricLine): Int = IslandFullIslandWidthPolicy.continuousSide(
        content = right.measureIndependentContentWidth(line),
        leftOverhead = overhead,
        rightOverhead = overhead,
        clearance = right.staticSeamClearance(line),
    )

    private val you = RichLyricLine(begin = 134_820, end = 138_750, text = "You", secondary = "你～")

    @Test
    fun `lone You from the 1024 log expands the island and shows statically instead of scrolling`() {
        val views = pair()
        // 真机主行 "You" 宽 55px：按真实字体把字号调到同样宽度。
        views.layoutSlots(400)
        views.bind(you)
        var size = 10f
        while (views.right.main.textPaint.measureText("You") < 55f) {
            size += 0.25f
            listOf(views.left, views.right).forEach { it.main.setTextSize(size) }
        }
        listOf(views.left, views.right).forEach { it.secondary.setTextSize(size * 0.85f) }
        idle()

        // 日志里的落地几何（两槽各 36px）确实只能滚动：先复现真机现象。
        views.layoutSlots(36)
        assertTrue("真机 36+36 槽宽下 You 应判超宽滚动", views.right.main.isOverflow)

        val side = views.demandSide(you)
        views.layoutSlots(side - overhead)
        assertFalse(
            "单侧宽 $side（文字宽 ${side - overhead}）下 You 仍判超宽滚动：岛宽估算与绘制端判定不一致",
            views.right.main.isOverflow,
        )
        assertFalse("翻译「你～」也必须静止显示", views.right.secondary.isOverflow)
        assertTrue("日志容量单侧 280，估算结果须在容量内才能真正撑开", side <= 280)
    }

    @Test
    fun `width demand during a line switch uses the incoming line's seam avoidance`() {
        val views = pair()
        views.layoutSlots(400)
        views.bind(RichLyricLine(begin = 131_610, end = 134_820, text = "i'm in love with you", secondary = "我爱上了你"))
        listOf(views.left, views.right).forEach {
            it.main.setTextSize(42f)
            it.secondary.setTextSize(36f)
        }
        idle()
        // 换句淡出期间：岛宽按即将落地的行算（widthDemandLine），但视图上仍是旧行。
        views.right.pendingSwitchLine = you
        val side = views.demandSide(you)
        views.bind(you)
        views.layoutSlots(side - overhead)
        assertFalse("换句途中估算的宽度落地后 You 判超宽滚动", views.right.main.isOverflow)
    }
}
