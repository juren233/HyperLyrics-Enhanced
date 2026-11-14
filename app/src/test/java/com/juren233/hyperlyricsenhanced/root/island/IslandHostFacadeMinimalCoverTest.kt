/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.os.Looper
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.view.InterludeMotion
import com.juren233.hyperlyricsenhanced.lyric.view.InterludeTracker
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer

/**
 * 「音频封面」关闭时极简显示带出的封面进出场不能被先到的入口吞掉：整岛刷新、注入与轻量恢复都会直接
 * 应用宿主设置，此前它们按目标记下封面显隐，随后的换句刷新判断不出变化，占位直接收起而渐变封面背景
 * 留在完整显示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class IslandHostFacadeMinimalCoverTest {
    private val root by lazy { FrameLayout(RuntimeEnvironment.getApplication()) }

    private val prefs: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        val values = mapOf<String, Any>(
            RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM to false,
            RootConstants.KEY_HOOK_ISLAND_INTERLUDE_MINIMAL_DISPLAY to true,
        )
        when (method.name) {
            "getAll" -> values
            "contains" -> values.containsKey(args!![0])
            "getInt" -> (values[args!![0] as String] as? Int) ?: args[1]
            "getBoolean" -> (values[args!![0] as String] as? Boolean) ?: args[1]
            "getString", "getStringSet", "getFloat", "getLong" -> args!![1]
            else -> null
        }
    } as SharedPreferences

    @Before
    fun deviceLikeFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After
    fun reset() {
        interlude(null)
        IslandMinimalCoverTransition.cancel()
        IslandRuntimePreferenceOverrides.clear()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun config() = IslandSlotRuntimeConfig.from(prefs)

    /** 前奏/间奏状态由歌词时间线写入，这里直接设定。 */
    private fun interlude(type: InterludeTracker.Type?) {
        LyriconDataBridge::class.java.getDeclaredField("currentInterludeType").apply {
            isAccessible = true
            set(null, type)
        }
    }

    @Test
    fun `host settings applied first still fade the cover out when minimal display ends`() {
        interlude(InterludeTracker.Type.INTERLUDE)
        IslandHostFacade.applyHostSettings(root, prefs)
        advance(InterludeMotion.TRANSITION_DURATION_MS * 2)

        interlude(null)
        // 整岛刷新先于换句刷新到达，直接应用宿主设置。
        IslandHostFacade.applyHostSettings(root, prefs)
        assertTrue("封面应开始淡出并在结束后撤回样式", IslandMinimalCoverTransition.hiding)
        assertFalse(IslandHostFacade.iconVisibilityChange(root, config()).album)

        advance(InterludeMotion.TRANSITION_DURATION_MS * 2)
        assertFalse(IslandMinimalCoverTransition.hiding)
    }

    @Test
    fun `host settings applied first still defer the cover entry when minimal display starts`() {
        IslandHostFacade.applyHostSettings(root, prefs)

        interlude(InterludeTracker.Type.INTRO)
        IslandHostFacade.applyHostSettings(root, prefs)
        assertTrue("封面应等左槽歌词淡出落定再放出", IslandMinimalCoverTransition.entryPending)
        assertFalse(IslandSlotContentAssembler.isAlbumPlaced(config()))
        assertFalse(IslandHostFacade.iconVisibilityChange(root, config()).album)
    }

    @Test
    fun `cover style applies while the entry waits so a native rebind keeps the gradient`() {
        IslandHostFacade.applyHostSettings(root, prefs)

        interlude(InterludeTracker.Type.INTERLUDE)
        IslandHostFacade.applyHostSettings(root, prefs)
        assertTrue(IslandMinimalCoverTransition.entryPending)
        // 占位尚未放出，但切歌时原生在此间重绑封面，样式须已生效。
        assertFalse(IslandSlotContentAssembler.isAlbumPlaced(config()))
        assertTrue(IslandSlotContentAssembler.isAlbumStyled(config()))

        // 切歌时极简显示来回切换：封面还没放出就结束，样式随即撤回。
        interlude(null)
        IslandHostFacade.applyHostSettings(root, prefs)
        assertFalse(IslandMinimalCoverTransition.entryPending)
        assertFalse(IslandMinimalCoverTransition.hiding)
        assertFalse(IslandSlotContentAssembler.isAlbumStyled(config()))
    }
}
