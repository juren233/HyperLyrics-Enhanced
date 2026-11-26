/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.content.Context
import android.content.res.Configuration
import org.robolectric.RuntimeEnvironment
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 日志里的报错：
 * 重放媒体材质背景失败: view=...MiuiMediaHeaderView reason=Expected receiver of type
 * ...MediaViewGlassEffect, but got ...MiuiMediaHeaderView
 *
 * 场景：卡片已被系统 apply 过一次（被 Hook 记录），用户在设置里切换卡片主题，
 * HLE 调 refresh() 重放。apply 是效果对象（INSTANCE 单例）上的实例方法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NotificationMediaMaterialThemeHookerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SystemUiHookLifetime.retired = false
        NotificationMediaMaterialThemeHooker.releaseAll()
        MediaViewGlassEffect.calls.clear()
        MediaViewBlurEffect.calls.clear()
    }

    @After
    fun tearDown() {
        NotificationMediaMaterialThemeHooker.releaseAll()
    }

    @Test
    fun refreshAfterThemeSwitchReplaysApplyOnTheSameEffectObject() {
        val effect = MediaViewGlassEffect.INSTANCE
        val view = FakeMediaHeaderView()
        val hook = NotificationMediaMaterialThemeHooker.EffectApplyHook(
            applyMethod = MediaViewGlassEffect::class.java.getDeclaredMethod(
                "apply",
                Object::class.java,
                Context::class.java,
            ),
            themeProvider = { RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM },
            glassWriter = RecordingWriter(),
        )

        // 系统第一次 apply，被 Hook 记录下来。
        hook.intercept(FakeChain(receiver = effect, argumentList = listOf(view, context)))
        assertTrue("系统第一次 apply 不应直接调用假 effect", MediaViewGlassEffect.calls.isEmpty())

        // 切换主题后重放。
        NotificationMediaMaterialThemeHooker.refresh()

        assertEquals("切换后应重放一次 apply", 1, MediaViewGlassEffect.calls.size)
        val call = MediaViewGlassEffect.calls.single()
        assertSame("重放的接收者必须是效果对象", effect, call.receiver)
        assertSame("view 必须作为第一个参数", view, call.view)
        assertSame("重放用的是记录下的原始 context", context, call.context)
    }

    @Test
    fun alwaysDarkWritesDarkGlassParamsToMediaBgAfterSystemApply() {
        val events = mutableListOf<String>()
        val writer = RecordingWriter(events)
        val view = FakeMediaHeaderView()
        val hook = NotificationMediaMaterialThemeHooker.EffectApplyHook(
            applyMethod = MediaViewGlassEffect::class.java.getDeclaredMethod(
                "apply",
                Object::class.java,
                Context::class.java,
            ),
            themeProvider = { RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK },
            glassWriter = writer,
        )
        val chain = FakeChain(
            receiver = MediaViewGlassEffect.INSTANCE,
            argumentList = listOf(view, context),
            onProceed = { events += "system-apply" },
        )

        hook.intercept(chain)

        val passedContext = chain.lastProceedArgs!![1] as Context
        assertEquals(
            Configuration.UI_MODE_NIGHT_YES,
            passedContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )
        assertEquals("玻璃参数必须在系统 apply 之后写", listOf("system-apply", "glass-write"), events)
        assertSame(view.holder.mediaBg, writer.target)
        assertArrayEquals(NotificationMediaGlassTheme.DARK_GLASS_PARAMS, writer.params, 0f)
    }

    @Test
    fun alwaysLightWritesLightGlassParams() {
        val writer = RecordingWriter()
        val view = FakeMediaHeaderView()
        assertTrue(
            NotificationMediaGlassTheme.applyAfterSystem(
                MediaViewGlassEffect.INSTANCE,
                view,
                RootConstants.MEDIA_CARD_THEME_ALWAYS_LIGHT,
                writer,
            ),
        )
        assertSame(view.holder.mediaBg, writer.target)
        assertArrayEquals(NotificationMediaGlassTheme.LIGHT_GLASS_PARAMS, writer.params, 0f)
    }

    @Test
    fun blurMaterialAndFollowSystemAreLeftUntouched() {
        val writer = RecordingWriter()
        val view = FakeMediaHeaderView()
        val blurHook = NotificationMediaMaterialThemeHooker.EffectApplyHook(
            applyMethod = MediaViewBlurEffect::class.java.getDeclaredMethod(
                "apply",
                Object::class.java,
                Context::class.java,
            ),
            themeProvider = { RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK },
            glassWriter = writer,
        )
        val blurChain = FakeChain(receiver = MediaViewBlurEffect.INSTANCE, argumentList = listOf(view, context))
        blurHook.intercept(blurChain)
        // 轻透磨砂仍然换 context，但不补写玻璃参数。
        val passed = blurChain.lastProceedArgs!![1] as Context
        assertEquals(
            Configuration.UI_MODE_NIGHT_YES,
            passed.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )
        assertNull(writer.target)

        assertFalse(
            NotificationMediaGlassTheme.applyAfterSystem(
                MediaViewGlassEffect.INSTANCE,
                view,
                RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM,
                writer,
            ),
        )
        assertNull(writer.target)
    }

    @Test
    fun onlyNotificationCenterGlassEffectGetsParams() {
        val dark = RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK
        assertSame(
            NotificationMediaGlassTheme.DARK_GLASS_PARAMS,
            NotificationMediaGlassTheme.paramsFor("MediaViewGlassEffect", dark),
        )
        assertNull(NotificationMediaGlassTheme.paramsFor("MediaViewBlurEffect", dark))
        assertNull(NotificationMediaGlassTheme.paramsFor("MediaViewGlassOnKeyguardEffect", dark))
        assertNull(NotificationMediaGlassTheme.paramsFor("MediaViewGlassOnKeyguardLightWallPaperEffect", dark))
        assertNull(NotificationMediaGlassTheme.paramsFor("MediaViewGlassFullAodEffect", dark))
        assertEquals(42, NotificationMediaGlassTheme.DARK_GLASS_PARAMS.size)
        assertEquals(42, NotificationMediaGlassTheme.LIGHT_GLASS_PARAMS.size)
    }

    @Test
    fun writerGetsACopySoSystemCannotMutateOurParams() {
        val writer = RecordingWriter()
        NotificationMediaGlassTheme.applyAfterSystem(
            MediaViewGlassEffect.INSTANCE,
            FakeMediaHeaderView(),
            RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK,
            writer,
        )
        writer.params!![0] = 99f
        assertEquals(0.8f, NotificationMediaGlassTheme.DARK_GLASS_PARAMS[0], 0f)
    }

    class Call(val receiver: Any, val view: Any, val context: Context)

    /** 与系统同名，模拟单例效果对象上的实例 apply。 */
    class MediaViewGlassEffect private constructor() {
        fun apply(obj: Any, context: Context) {
            calls += Call(this, obj, context)
        }

        companion object {
            @JvmField val INSTANCE = MediaViewGlassEffect()
            internal val calls = mutableListOf<Call>()
        }
    }

    class MediaViewBlurEffect private constructor() {
        fun apply(obj: Any, context: Context) {
            calls += Call(this, obj, context)
        }

        companion object {
            @JvmField val INSTANCE = MediaViewBlurEffect()
            internal val calls = mutableListOf<Call>()
        }
    }

    class FakeViewHolder {
        @JvmField val mediaBg = Any()
    }

    class FakeMediaHeaderView {
        val holder = FakeViewHolder()

        fun getMediaViewHolder(): FakeViewHolder = holder
    }

    private class RecordingWriter(
        private val events: MutableList<String>? = null,
    ) : NotificationMediaGlassTheme.GlassWriter {
        var target: Any? = null
        var params: FloatArray? = null

        override fun write(effect: Any, target: Any, params: FloatArray) {
            events?.add("glass-write")
            this.target = target
            this.params = params
        }
    }

    private class FakeChain(
        private val receiver: Any?,
        private val argumentList: List<Any?>,
        private val onProceed: (() -> Unit)? = null,
    ) : XposedInterface.Chain {
        var lastProceedArgs: Array<out Any?>? = null
            private set

        override fun getExecutable(): Executable = Any::class.java.getMethod("toString")

        override fun getThisObject(): Any? = receiver

        override fun getArgs(): List<Any?> = argumentList

        override fun getArg(index: Int): Any? = argumentList[index]

        override fun proceed(): Any? {
            onProceed?.invoke()
            lastProceedArgs = argumentList.toTypedArray()
            return null
        }

        override fun proceed(args: Array<out Any?>): Any? {
            onProceed?.invoke()
            lastProceedArgs = args
            return null
        }

        override fun proceedWith(thisObject: Any): Any? = proceed()

        override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? = proceed(args)
    }
}
