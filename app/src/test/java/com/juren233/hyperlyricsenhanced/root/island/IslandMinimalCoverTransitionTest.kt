/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.lyric.view.InterludeMotion
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoAnimation
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoPresets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** 「音频封面」关闭时极简显示带出的封面进出场：进度、淡出占位与反向衔接（真实属性动画逐帧推进）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class IslandMinimalCoverTransitionTest {
    private val root by lazy { FrameLayout(RuntimeEnvironment.getApplication()) }

    @Before
    fun deviceLikeFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After
    fun reset() {
        IslandMinimalCoverTransition.cancel()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `cover appears transparent then fades in to full`() {
        IslandMinimalCoverTransition.prepareShow(root)
        assertEquals(0f, IslandMinimalCoverTransition.presence)

        IslandMinimalCoverTransition.startShow()
        advance(InterludeMotion.TRANSITION_DURATION_MS / 2)
        val mid = IslandMinimalCoverTransition.presence
        assertTrue("淡入途中进度应在 0 与 1 之间: $mid", mid > 0f && mid < 1f)

        advance(InterludeMotion.TRANSITION_DURATION_MS)
        assertEquals(1f, IslandMinimalCoverTransition.presence)
    }

    @Test
    fun `cover style is withdrawn only after the fade-out ends`() {
        var hiddenCalls = 0
        IslandMinimalCoverTransition.startHide(root) {
            // 回调时已不在淡出，样式撤回据此生效。
            assertFalse(IslandMinimalCoverTransition.hiding)
            hiddenCalls++
        }
        assertTrue(IslandMinimalCoverTransition.hiding)

        advance(InterludeMotion.TRANSITION_DURATION_MS / 2)
        assertTrue(IslandMinimalCoverTransition.hiding)
        assertEquals(0, hiddenCalls)

        advance(InterludeMotion.TRANSITION_DURATION_MS)
        assertEquals(1, hiddenCalls)
        assertFalse(IslandMinimalCoverTransition.hiding)
        // 收起后复位常态，「音频封面」开启时的封面不受影响。
        assertEquals(1f, IslandMinimalCoverTransition.presence)
    }

    @Test
    fun `gradient cover stays hidden after fade-out until it is withdrawn or shown again`() {
        // 淡出前挂上的渐变封面背景。
        val attached = IslandMinimalCoverTransition.coverGeneration()
        IslandMinimalCoverTransition.startHide(root) {
            // 撤回被暂存到宿主形变结束时，回调里背景仍挂着，此刻已不能再画。
            assertEquals(0f, IslandMinimalCoverTransition.presenceFor(attached))
        }
        advance(InterludeMotion.TRANSITION_DURATION_MS * 2)

        // 进度已复位为常态，但等待撤回的背景不能以完整封面闪现。
        assertEquals(1f, IslandMinimalCoverTransition.presence)
        assertEquals(0f, IslandMinimalCoverTransition.presenceFor(attached))
        // 样式重新套用（「音频封面」重新打开等）后的背景照常显示。
        assertEquals(1f, IslandMinimalCoverTransition.presenceFor(IslandMinimalCoverTransition.coverGeneration()))

        // 再次进场：等待撤回的背景交回进场进度。
        IslandMinimalCoverTransition.prepareShow(root)
        assertEquals(0f, IslandMinimalCoverTransition.presenceFor(attached))
        IslandMinimalCoverTransition.startShow()
        advance(InterludeMotion.TRANSITION_DURATION_MS * 2)
        assertEquals(1f, IslandMinimalCoverTransition.presenceFor(attached))
    }

    @Test
    fun `cancel releases a gradient cover still awaiting withdrawal`() {
        val attached = IslandMinimalCoverTransition.coverGeneration()
        IslandMinimalCoverTransition.startHide(root) {}
        advance(InterludeMotion.TRANSITION_DURATION_MS * 2)
        assertEquals(0f, IslandMinimalCoverTransition.presenceFor(attached))

        IslandMinimalCoverTransition.cancel()
        assertEquals(1f, IslandMinimalCoverTransition.presenceFor(attached))
    }

    @Test
    fun `cover entry waits until the left lyric has faded out in place`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        val left = View(activity).apply { tag = IslandProbeUtils.LEFT_TEST_VIEW_TAG }
        host.addView(left, FrameLayout.LayoutParams(100, 40))
        activity.setContentView(host, ViewGroup.LayoutParams(200, 104))
        advance(32)

        var entered = 0
        IslandMinimalCoverTransition.deferEntry(host) { entered++ }
        // 同一次内容更新随后启动左槽的切换动画（淡出 → 写入空内容 → 淡入）。
        val (outConfig, inConfig) = YoYoPresets.Default
        YoYoAnimation.switchContent(left, outConfig, inConfig) {}
        assertTrue(IslandMinimalCoverTransition.entryPending)
        assertEquals(0f, IslandMinimalCoverTransition.presence)

        advance(outConfig.duration / 2)
        assertTrue("淡出途中不放出封面占位", IslandMinimalCoverTransition.entryPending)
        assertEquals(0, entered)

        advance(outConfig.duration)
        assertFalse(YoYoAnimation.isAwaitingContent(left))
        assertFalse(IslandMinimalCoverTransition.entryPending)
        assertEquals(1, entered)
    }

    @Test
    fun `cover entry proceeds on the next frame when no lyric is fading out`() {
        var entered = 0
        IslandMinimalCoverTransition.deferEntry(root) { entered++ }
        assertTrue(IslandMinimalCoverTransition.entryPending)
        advance(32)
        assertEquals(1, entered)
        assertFalse(IslandMinimalCoverTransition.entryPending)
    }

    @Test
    fun `leaving minimal display before the cover entered cancels the entry and withdraws the style`() {
        val attached = IslandMinimalCoverTransition.coverGeneration()
        var entered = 0
        IslandMinimalCoverTransition.deferEntry(root) { entered++ }
        var hidden = 0
        IslandMinimalCoverTransition.startHide(root) { hidden++ }
        // 挂起期间可能已套上的样式立即撤回，已挂上的渐变背景不以完整封面出现。
        assertEquals(1, hidden)
        assertEquals(0f, IslandMinimalCoverTransition.presenceFor(attached))
        advance(InterludeMotion.TRANSITION_DURATION_MS * 2)
        assertEquals(0, entered)
        assertEquals(1, hidden)
        assertFalse(IslandMinimalCoverTransition.entryPending)
        assertFalse(IslandMinimalCoverTransition.hiding)
        assertEquals(1f, IslandMinimalCoverTransition.presence)
    }

    @Test
    fun `reappearing during fade-out continues from the visible progress`() {
        var hiddenCalls = 0
        IslandMinimalCoverTransition.startHide(root) { hiddenCalls++ }
        advance(InterludeMotion.TRANSITION_DURATION_MS / 2)
        val faded = IslandMinimalCoverTransition.presence
        assertTrue(faded > 0f && faded < 1f)

        IslandMinimalCoverTransition.prepareShow(root)
        assertFalse(IslandMinimalCoverTransition.hiding)
        assertEquals(faded, IslandMinimalCoverTransition.presence)

        IslandMinimalCoverTransition.startShow()
        advance(InterludeMotion.TRANSITION_DURATION_MS * 2)
        assertEquals(1f, IslandMinimalCoverTransition.presence)
        assertEquals(0, hiddenCalls)
    }
}
