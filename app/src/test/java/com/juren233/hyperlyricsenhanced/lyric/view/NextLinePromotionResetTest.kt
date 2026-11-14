/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * 「下一句提升」动画进行中写入新行（换来源、换内容）时，主行必须回到可见：
 * 真机 240135 上主行停在提升动画终点（alpha 0、上移 0.65 行高），岛上只剩翻译（见 DEBUGGING_MISTAKES
 * ISLAND-MAIN-LINE-STUCK-HIDDEN）。这里用真实的 Android 动画代码逐帧推进复现，两种歌词行视图都覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class NextLinePromotionResetTest {

    /** 两种歌词行视图的共同部分：写入行，读主行与副行。 */
    private class LineView(val root: View, val main: View, val secondary: View, val write: (IRichLyricLine) -> Unit)

    private fun spaceGate(context: Context) = SpaceGateRichLyricLineView(context).let {
        LineView(it, it.main, it.secondary) { line -> it.line = line }
    }

    private fun classic(context: Context) = RichLyricLineView(context).let {
        LineView(it, it.main, it.secondary) { line -> it.line = line }
    }

    @Before
    fun deviceLikeFrames() {
        // 默认的 Choreographer 会自动推进时钟、一次跑完整段动画；暂停后按真机 16ms 一帧推进。
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    private fun previewLine(begin: Long, end: Long, text: String, next: String) = RichLyricLine(
        begin = begin,
        end = end,
        text = text,
        secondary = next,
        metadata = lyricMetadataOf(METADATA_NEXT_LINE_PREVIEW to "true"),
    )

    private fun attached(create: (Context) -> LineView): LineView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = create(activity)
        activity.setContentView(FrameLayout(activity).apply {
            addView(view.root, FrameLayout.LayoutParams(1_000, FrameLayout.LayoutParams.WRAP_CONTENT))
        })
        idle(Duration.ofMillis(50))
        return view
    }

    private fun idle(duration: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(duration)
    }

    private fun assertMainVisible(view: LineView) {
        assertEquals("主行透明度", 1f, view.main.alpha, 0f)
        assertEquals("主行纵向位移", 0f, view.main.translationY, 0f)
    }

    private fun newContentDuringPromotion(create: (Context) -> LineView) {
        val view = attached(create)
        view.write(previewLine(0L, 1_000L, "第一句", "第二句"))
        idle(Duration.ofMillis(50))
        assertTrue("主行与预览行必须已布局", view.main.height > 0 && view.secondary.height > 0)

        // 预览「第二句」成为主行：开始提升动画。
        view.write(previewLine(1_000L, 2_000L, "第二句", "第三句"))
        idle(Duration.ofMillis(60))
        assertTrue("提升动画必须已开始（主行正在淡出）", view.main.alpha < 1f)

        // 动画进行中写入另一份内容（如换了歌词来源，时间轴不同）。
        view.write(previewLine(1_050L, 2_100L, "第二句", "第三句"))
        idle(Duration.ofSeconds(2))

        assertMainVisible(view)
    }

    private fun promotionRunsToCompletion(create: (Context) -> LineView) {
        val view = attached(create)
        view.write(previewLine(0L, 1_000L, "第一句", "第二句"))
        idle(Duration.ofMillis(50))
        view.write(previewLine(1_000L, 2_000L, "第二句", "第三句"))
        idle(Duration.ofMillis(60))
        assertTrue("提升动画必须已开始（主行正在淡出）", view.main.alpha < 1f)
        idle(Duration.ofSeconds(2))

        assertMainVisible(view)
    }

    @Test
    fun `space gate view - new content during a promotion leaves the main line visible`() =
        newContentDuringPromotion(::spaceGate)

    @Test
    fun `space gate view - a promotion that runs to completion leaves the main line visible`() =
        promotionRunsToCompletion(::spaceGate)

    @Test
    fun `classic view - new content during a promotion leaves the main line visible`() =
        newContentDuringPromotion(::classic)

    @Test
    fun `classic view - a promotion that runs to completion leaves the main line visible`() =
        promotionRunsToCompletion(::classic)
}
