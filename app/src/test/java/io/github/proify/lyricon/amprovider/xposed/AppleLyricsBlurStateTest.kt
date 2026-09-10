/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * R5 守卫：Blur 运行时状态由 AppleLyricsBlurState 自持，行为门面（Hooks）只保留
 * Hook 发现/安装/事件转交与一个状态组件引用；Engine/RecyclerUtils 不再越文件写门面字段。
 * 同时固定“七个重复常量只保留一份”的处置。
 */
class AppleLyricsBlurStateTest {

    private fun mainSourceDir(): File =
        listOf(
            File("app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics"),
            File("src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics"),
        ).first(File::isDirectory)

    private fun source(fileName: String): String = File(mainSourceDir(), fileName).readText()

    companion object {
        // Held strongly so the weak-key method cache cannot drop the key mid-test.
        private val CACHED_CLASS = AppleLyricsBlurStateTest::class.java
        private val OTHER_CLASS = String::class.java
    }

    @Test
    fun `hyper os methods are cached per view class`() {
        val state = AppleLyricsBlurState()
        var resolved = 0
        val first = AppleLyricsHyperOsMethods(setSelfBlur = null, setSelfBlurType = null)
        val value = state.hyperOsMethodsFor(CACHED_CLASS) {
            resolved += 1
            first
        }
        assertSame(first, value)
        // A second lookup for the same class must reuse the cached resolution.
        val again = state.hyperOsMethodsFor(CACHED_CLASS) {
            resolved += 1
            AppleLyricsHyperOsMethods(null, null)
        }
        assertSame(first, again)
        assertEquals(1, resolved)

        val other = AppleLyricsHyperOsMethods(setSelfBlur = null, setSelfBlurType = null)
        assertSame(other, state.hyperOsMethodsFor(OTHER_CLASS) { other })
    }

    @Test
    fun `child adapter position method cache stores and returns by class`() {
        val state = AppleLyricsBlurState()
        val method: Method = String::class.java.getDeclaredMethod("length")
        assertNull(state.childAdapterPositionMethod(String::class.java))
        state.rememberChildAdapterPositionMethod(String::class.java, method)
        assertSame(method, state.childAdapterPositionMethod(String::class.java))
        assertNull(state.childAdapterPositionMethod(Int::class.java))
    }

    @Test
    fun `blur facade no longer declares the runtime state collections`() {
        val hooks = source("AppleLyricsBlurHooks.kt")
        listOf(
            "appleLyricsBlurRuntimeStates",
            "appleLyricsBlurredViews",
            "appleLyricsRecyclerViewsByAdapter",
            "appleLyricsRecyclerViewClassifications",
            "appleLyricsChildAdapterPositionMethods",
            "appleLyricsHyperOsMethods",
        ).forEach { field ->
            assertFalse(
                "Blur 门面不应再声明 $field",
                Regex("(val|var)\\s+$field\\b").containsMatchIn(hooks),
            )
        }
        assertTrue(
            "Blur 门面必须持有状态组件",
            hooks.contains("internal val blurState = AppleLyricsBlurState()"),
        )
    }

    @Test
    fun `engine and recycler utils never touch blur facade state`() {
        val forbidden = listOf(
            "appleLyricsBlurRuntimeStates",
            "appleLyricsBlurredViews",
            "appleLyricsRecyclerViewsByAdapter",
            "appleLyricsRecyclerViewClassifications",
            "appleLyricsChildAdapterPositionMethods",
            "appleLyricsHyperOsMethods",
        ).joinToString("|")
        listOf("AppleLyricsBlurEngine.kt", "AppleLyricsRecyclerUtils.kt").forEach { name ->
            val body = source(name)
            assertFalse(
                "$name 不得直接触碰 Blur 状态集合",
                Regex("\\b(?:$forbidden)\\b").containsMatchIn(body),
            )
        }
    }

    @Test
    fun `duplicated blur constants live only in the blur facade`() {
        val hooks = source("AppleLyricsBlurHooks.kt")
        val supplement = source("AppleLyricsSupplementHooks.kt")
        listOf(
            "APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION",
            "APPLE_LYRICS_SCROLL_STATE_IDLE",
            "APPLE_LYRICS_IDLE_RECHECK_DELAY_MS",
            "APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS",
            "APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS",
            "APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE",
            "APPLE_MUSIC_PACKAGE",
        ).forEach { constant ->
            assertTrue(
                " blur 常量 $constant 必须声明在 Blur 门面",
                Regex("const val $constant\\b").containsMatchIn(hooks),
            )
            assertFalse(
                "SupplementHooks 不应再重复声明 $constant",
                Regex("const val $constant\\b").containsMatchIn(supplement),
            )
        }
    }
}
