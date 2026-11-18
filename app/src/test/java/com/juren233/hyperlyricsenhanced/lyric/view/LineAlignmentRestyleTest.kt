/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 槽位换样式而内容未变时，装配器不会重套对齐；调用方设置的靠右必须像居中一样在重设样式后保留
 * （241006 真机录屏：全岛模式「歌曲信息」右槽选靠右，左槽的短歌词测宽把右槽重配为歌词样式，
 * 靠右被样式的 false 冲掉，文字退回靠左）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class LineAlignmentRestyleTest {
    private val context = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `classic view keeps explicit right alignment across restyles`() {
        val view = RichLyricLineView(context)
        view.setLineCentering(false)
        view.setLineAlignmentRight(true)
        view.setStyle(LyricViewStyle(alignRight = false))
        assertTrue(view.main.alignRight)
        assertTrue(view.secondary.alignRight)
        view.setLineAlignmentRight(false)
        view.setStyle(LyricViewStyle(alignRight = false))
        assertFalse(view.main.alignRight)
    }

    @Test
    fun `space gate view keeps explicit right alignment across restyles`() {
        val view = SpaceGateRichLyricLineView(context)
        view.setLineCentering(false)
        view.setLineAlignmentRight(true)
        view.setStyle(LyricViewStyle(alignRight = false))
        assertTrue(view.main.alignRight)
        assertTrue(view.secondary.alignRight)
        view.setLineAlignmentRight(false)
        view.setStyle(LyricViewStyle(alignRight = false))
        assertFalse(view.main.alignRight)
    }

    @Test
    fun `style default applies until the caller sets an alignment`() {
        val view = RichLyricLineView(context)
        view.setStyle(LyricViewStyle(alignRight = true))
        assertTrue(view.main.alignRight)
    }
}
