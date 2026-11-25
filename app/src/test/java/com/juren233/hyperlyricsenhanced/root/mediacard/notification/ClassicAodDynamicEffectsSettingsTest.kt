/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 自定义 AOD 的「动态效果」三个开关（逐字效果、灵动圆点、歌词滚动）独立一套，与锁屏 AOD 互不影响。
 * 改动前自定义 AOD 根本不读这三个开关，打开后读到的仍是 false。
 * 键名写成字面量：设置页与读取端必须用同一组持久化键，改名会让用户已有的设置失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ClassicAodDynamicEffectsSettingsTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("classic_aod_dynamic_effects_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun classicAodReadsItsOwnDynamicEffectSwitches() {
        prefs.edit()
            .putBoolean(CLASSIC_WORD_SYNC, true)
            .putBoolean(CLASSIC_INTERLUDE_DOTS, true)
            .putBoolean(CLASSIC_LYRIC_SCROLL, true)
            .commit()

        val classic = classicAodTextStyle(prefs)
        assertEquals(true, classic.wordSyncEffect)
        assertEquals(true, classic.interludeDots)
        assertEquals(true, classic.lyricScroll)

        val lock = lockScreenAodTextStyle(prefs)
        assertFalse("自定义 AOD 的开关不能带动锁屏 AOD", lock.wordSyncEffect)
        assertFalse(lock.interludeDots)
        assertFalse(lock.lyricScroll)
    }

    @Test
    fun lockScreenSwitchesDoNotTurnOnClassicAod() {
        prefs.edit()
            .putBoolean(LOCK_WORD_SYNC, true)
            .putBoolean(LOCK_INTERLUDE_DOTS, true)
            .putBoolean(LOCK_LYRIC_SCROLL, true)
            .commit()

        val classic = classicAodTextStyle(prefs)
        assertFalse(classic.wordSyncEffect)
        assertFalse(classic.interludeDots)
        assertFalse(classic.lyricScroll)
        val lock = lockScreenAodTextStyle(prefs)
        assertEquals(true, lock.wordSyncEffect)
        assertEquals(true, lock.interludeDots)
        assertEquals(true, lock.lyricScroll)
    }

    @Test
    fun eachClassicSwitchIsIndependent() {
        prefs.edit().putBoolean(CLASSIC_LYRIC_SCROLL, true).commit()
        val classic = classicAodTextStyle(prefs)
        assertFalse(classic.wordSyncEffect)
        assertFalse(classic.interludeDots)
        assertEquals(true, classic.lyricScroll)
    }

    @Test
    fun classicSwitchesDefaultOff() {
        val classic = classicAodTextStyle(prefs)
        assertFalse(classic.wordSyncEffect)
        assertFalse(classic.interludeDots)
        assertFalse(classic.lyricScroll)
        val noPrefs = classicAodTextStyle(null)
        assertFalse(noPrefs.wordSyncEffect)
        assertFalse(noPrefs.interludeDots)
        assertFalse(noPrefs.lyricScroll)
    }

    private companion object {
        const val CLASSIC_WORD_SYNC = "key_hook_classic_aod_word_sync_effect"
        const val CLASSIC_INTERLUDE_DOTS = "key_hook_classic_aod_interlude_dots"
        const val CLASSIC_LYRIC_SCROLL = "key_hook_classic_aod_lyric_scroll"
        const val LOCK_WORD_SYNC = "key_hook_lock_screen_aod_word_sync_effect"
        const val LOCK_INTERLUDE_DOTS = "key_hook_lock_screen_aod_interlude_dots"
        const val LOCK_LYRIC_SCROLL = "key_hook_lock_screen_aod_lyric_scroll"
    }
}
