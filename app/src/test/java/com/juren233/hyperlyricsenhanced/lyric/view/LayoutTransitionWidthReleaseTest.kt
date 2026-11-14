/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * 布局转场改变布局期间外层加宽：歌词视图应立即按新宽度布局，而不是等转场结束才跳宽。
 *
 * Robolectric 不绘制，变化动画不会真正启动；这里用可控的转场替身代替「变化动画进行中」，
 * 布局被跳过的行为仍走真实的 `ViewGroup.layout()`。替身被摘下（框架对其 `cancel()`）即视为
 * 变化动画结束。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LayoutTransitionWidthReleaseTest {
    private class ChangingTransition : LayoutTransition() {
        var changing = false
        override fun isChangingLayout(): Boolean = changing
    }

    private class LyricLike(context: Context, release: Boolean) : FrameLayout(context) {
        private val widthRelease = LayoutTransitionWidthRelease(this).takeIf { release }

        override fun setLayoutTransition(transition: LayoutTransition?) {
            // 框架在替换转场时对旧转场 cancel()，结束其变化动画。
            (layoutTransition as? ChangingTransition)?.changing = false
            super.setLayoutTransition(transition)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            widthRelease?.onMeasured()
        }
    }

    @Before
    fun deviceLikeFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private class Scene(val slot: FrameLayout, val lyric: LyricLike, val transition: ChangingTransition)

    /** 歌词视图宽 20；变化动画开始改变布局，外层随即加宽到 [slotWidth]。 */
    private fun scene(release: Boolean, slotWidth: Int): Scene {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val slot = FrameLayout(activity)
        val lyric = LyricLike(activity, release)
        lyric.addView(View(activity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40))
        slot.addView(lyric, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 104))
        activity.setContentView(slot, ViewGroup.LayoutParams(20, 104))
        val transition = ChangingTransition()
        lyric.layoutTransition = transition
        advance(100)
        assertEquals(20, lyric.width)
        transition.changing = true
        slot.layoutParams = slot.layoutParams.apply { width = slotWidth }
        return Scene(slot, lyric, transition)
    }

    @Test
    fun `without release the lyric keeps the old width while the transition changes layout`() {
        val scene = scene(release = false, slotWidth = 200)
        advance(64)
        assertEquals(200, scene.slot.width)
        assertEquals(20, scene.lyric.width)
    }

    @Test
    fun `release lays the lyric out at the new width within a frame or two`() {
        val scene = scene(release = true, slotWidth = 200)
        advance(48)
        assertEquals(200, scene.lyric.width)
        // 转场对象原样保留，之后副行出现/消失仍有动画。
        assertSame(scene.transition, scene.lyric.layoutTransition)
    }

    @Test
    fun `release leaves the transition running when the width does not change`() {
        val scene = scene(release = true, slotWidth = 20)
        scene.lyric.requestLayout()
        advance(48)
        assertTrue(scene.transition.changing)
        assertEquals(20, scene.lyric.width)
    }
}
