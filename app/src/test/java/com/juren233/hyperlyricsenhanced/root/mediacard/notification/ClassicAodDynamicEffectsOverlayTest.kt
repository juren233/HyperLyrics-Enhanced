/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 自定义 AOD（时钟下方的歌词层）接入锁屏 AOD 的「动态效果」：用真实控件树走自定义 AOD 的写入路径
 * [applyClassicLyricContent]，开关读自定义 AOD 自己的设置。
 * 改动前自定义 AOD 的歌词行是普通文字控件、写入即替换：切句没有转场、没有逐字、间奏是静态「•••」。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ClassicAodDynamicEffectsOverlayTest {

    private lateinit var activity: Activity
    private lateinit var prefs: SharedPreferences
    private lateinit var aodRoot: FrameLayout
    private lateinit var parent: FrameLayout
    private lateinit var overlay: AodPluginOverlay
    private lateinit var wakeLock: PowerManager.WakeLock

    @Before
    fun setUp() {
        // 逐字与圆点每帧自重绘；默认的 Choreographer 会自动推进时钟、让「空闲」永远等不到。按真机 16ms 一帧手动推进。
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        prefs = activity.getSharedPreferences("classic_aod_overlay_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        aodRoot = FrameLayout(activity)
        parent = FrameLayout(activity)
        val anchor = View(activity)
        aodRoot.addView(anchor, FrameLayout.LayoutParams(200, 40))
        aodRoot.addView(
            parent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        activity.setContentView(aodRoot)
        wakeLock = activity.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:classic_aod")
            .apply { setReferenceCounted(false) }
        overlay = buildAodPluginOverlay(
            context = activity,
            parent = parent,
            aodRoot = aodRoot,
            anchor = anchor,
            anchorIsFallback = false,
            drawWakeLock = wakeLock,
        )
        overlay.root.layoutParams = (overlay.root.layoutParams as FrameLayout.LayoutParams).apply {
            width = 1000
            topMargin = 60
        }
    }

    @After
    fun tearDown() {
        AodLyricTransition.finish(overlay)
        if (wakeLock.isHeld) wakeLock.release()
    }

    @Test
    fun lyricScrollOnPromotesNextLineLikeLockScreen() {
        setSwitches(lyricScroll = true)
        apply(content(main = "first line", next = "second line"), LINE_1)
        layout()

        val pending = apply(content(main = "second line", next = "third line"), LINE_2)

        assertNotNull("开了歌词滚动，换句必须有转场", pending)
        assertEquals(AodTransitionKind.PROMOTE, pending!!.kind)
        AodLyricTransition.begin(overlay, pending, AodLyricAlignment.CENTER)
        assertNotNull(overlay.transition)
        assertFalse("转场期间放开时钟容器裁切，从下方进入的行不被切掉", parent.clipChildren)
        assertEquals(0f, overlay.next.alpha, 0f)

        runFrames(AodLyricTransitionPolicy.MOVE_DURATION_MS + 100)

        assertNull("转场按时落地", overlay.transition)
        assertTrue("落地后还原时钟容器裁切", parent.clipChildren)
        listOf(overlay.main, overlay.next).forEach { row ->
            assertEquals(1f, row.alpha, 0f)
            assertEquals(0f, row.translationY, 0f)
            assertEquals(1f, row.scaleX, 0f)
        }
        assertEquals("second line", overlay.main.text.toString())
        assertEquals("third line", overlay.next.text.toString())
    }

    @Test
    fun lyricScrollOffReplacesDirectly() {
        setSwitches(lyricScroll = false)
        apply(content(main = "first line", next = "second line"), LINE_1)
        layout()

        val pending = apply(content(main = "second line", next = "third line"), LINE_2)

        assertNull("关了歌词滚动，自定义 AOD 照旧直接替换", pending)
        assertNull(overlay.transition)
        assertEquals("second line", overlay.main.text.toString())
    }

    @Test
    fun lockScreenLyricScrollDoesNotAnimateClassicAod() {
        prefs.edit().putBoolean(RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_LYRIC_SCROLL, true).commit()
        apply(content(main = "first line", next = "second line"), LINE_1)
        layout()

        val pending = apply(content(main = "second line", next = "third line"), LINE_2)

        assertNull("锁屏 AOD 的开关不影响自定义 AOD", pending)
    }

    @Test
    fun songInfoRowStaysAboveExitingLine() {
        setSwitches(lyricScroll = true)
        overlay.songInfo.text = "Song - Artist"
        overlay.songInfoRow.visibility = View.VISIBLE
        apply(content(main = "first line", next = "second line"), LINE_1)
        layout()

        assertTrue(overlay.songInfoRow.bottom > 0)
        assertEquals(
            "旧句上浮只到歌曲信息下沿，不压住歌曲信息",
            overlay.songInfoRow.bottom,
            overlay.ghostClipTopInRoot(),
        )
        overlay.songInfoRow.visibility = View.GONE
        assertEquals(0, overlay.ghostClipTopInRoot())
    }

    @Test
    fun wordSyncOnBindsWordsToClassicMainRow() {
        setSwitches(wordSync = true)
        val words = listOf(
            LyricWord(begin = 1_000, end = 1_400, text = "first "),
            LyricWord(begin = 1_400, end = 2_000, text = "line"),
        )

        apply(content(main = "first line", next = ""), LINE_1, AodRowWords(main = words))

        assertTrue("开了逐字，自定义 AOD 主句绑定逐字数据", overlay.main.hasWordSync)
    }

    @Test
    fun wordSyncOffKeepsWholeLine() {
        setSwitches(wordSync = false)
        val style = classicAodTextStyle(prefs)

        val words = AodLyricRowsApply.currentWords(style, "first line")
        apply(content(main = "first line", next = ""), LINE_1, words)

        assertNull(words.main)
        assertFalse("关了逐字，主句整行显示", overlay.main.hasWordSync)
    }

    @Test
    fun interludeDotsOnDrawsDotsAndFadesThemIn() {
        setSwitches(interludeDots = true)
        apply(content(main = "first line", next = "after the break"), LINE_1)
        layout()

        val pending = apply(
            content(main = "•••", next = "after the break"),
            INTERLUDE,
            AodRowWords(interlude = INTERLUDE.begin..INTERLUDE.end),
            nowInterlude = true,
        )

        assertTrue("开了灵动圆点，间奏改画动态圆点", overlay.main.isInterludeIndicator)
        assertNotNull(pending)
        assertEquals(AodTransitionKind.INTERLUDE_ENTER, pending!!.kind)
    }

    @Test
    fun interludeDotsOffKeepsStaticText() {
        setSwitches(interludeDots = false)
        val style = classicAodTextStyle(prefs)
        apply(content(main = "first line", next = "after the break"), LINE_1)
        layout()

        val pending = apply(
            content(main = "•••", next = "after the break"),
            INTERLUDE,
            AodLyricRowsApply.currentWords(style, "•••"),
            nowInterlude = true,
        )

        assertFalse(overlay.main.isInterludeIndicator)
        assertNull(pending)
    }

    @Test
    fun hiddenOverlayNeverStartsTransition() {
        setSwitches(lyricScroll = true)
        apply(content(main = "first line", next = "second line"), LINE_1)
        layout()
        overlay.detached = true

        val pending = apply(content(main = "second line", next = "third line"), LINE_2)

        assertNull("拆除后的歌词层不再开始转场", pending)
    }

    private fun setSwitches(
        wordSync: Boolean = false,
        interludeDots: Boolean = false,
        lyricScroll: Boolean = false,
    ) {
        prefs.edit()
            .putBoolean(RootConstants.KEY_HOOK_CLASSIC_AOD_WORD_SYNC_EFFECT, wordSync)
            .putBoolean(RootConstants.KEY_HOOK_CLASSIC_AOD_INTERLUDE_DOTS, interludeDots)
            .putBoolean(RootConstants.KEY_HOOK_CLASSIC_AOD_LYRIC_SCROLL, lyricScroll)
            .commit()
    }

    private fun apply(
        content: AodLyricContent,
        line: RichLyricLine,
        words: AodRowWords = AodRowWords(),
        nowInterlude: Boolean = false,
    ): AodLyricTransition.Pending? = NotificationMediaAodLyricHooker.applyClassicLyricContent(
        overlay = overlay,
        content = content,
        textStyle = classicAodTextStyle(prefs),
        currentLine = line,
        nowInterlude = nowInterlude,
        words = words,
        playing = true,
    ).also { layout() }

    private fun layout() {
        aodRoot.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        aodRoot.layout(0, 0, 1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 逐帧推进：先排版并触发一次预绘制（转场在预绘制里开始），再按 16ms 推进时钟。 */
    private fun runFrames(totalMs: Long) {
        layout()
        aodRoot.viewTreeObserver.dispatchOnPreDraw()
        var elapsed = 0L
        while (elapsed < totalMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            elapsed += 16
        }
    }

    private fun content(main: String, next: String) = AodLyricContent(
        main = main,
        translation = "",
        backing = "",
        backingTranslation = "",
        overlappingMain = "",
        overlappingTranslation = "",
        overlappingBacking = "",
        overlappingBackingTranslation = "",
        next = next,
        mainAlignment = AodLyricAlignment.CENTER,
        backingAlignment = AodLyricAlignment.CENTER,
        overlappingAlignment = AodLyricAlignment.CENTER,
        overlappingBackingAlignment = AodLyricAlignment.CENTER,
        nextAlignment = AodLyricAlignment.CENTER,
    )

    private companion object {
        val LINE_1 = RichLyricLine(begin = 1_000, end = 2_000, text = "first line")
        val LINE_2 = RichLyricLine(begin = 2_000, end = 3_000, text = "second line")
        val INTERLUDE = RichLyricLine(begin = 2_000, end = 8_000, text = "•••")
    }
}
