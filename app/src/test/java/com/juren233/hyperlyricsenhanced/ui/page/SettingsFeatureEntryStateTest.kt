/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 复现：打开应用设置 →「回顾基础设置流程页」改选工作模式 → 返回设置页。
 *
 * 回顾页盖在设置页上时设置页仍在组合中（导航会保留栈顶下面一层），设置页的入口状态对象一直存活。
 * 回顾页选模式通过 [applySetupModeEntrySideEffects] 改写入口偏好，返回后设置页的开关与
 * 「主页隐藏时迁入设置页」的歌词设置/特殊功能必须跟着变；此前设置页只在打开时读一次偏好，回来后仍是旧状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsFeatureEntryStateTest {

    private lateinit var prefs: SharedPreferences
    private var state: SettingsFeatureEntryState? = null

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit(commit = true) { clear() }
        // 回顾页选模式时经 PrefsBridge 同步功能开关；应用里由 RootApplication.onCreate 初始化。
        PrefsBridge.init(app)
    }

    @After
    fun tearDown() {
        state?.stopObserving()
    }

    /** 小米设备默认状态：三个歌词入口与 Apple Music 入口都开，功能自身也开着。 */
    private fun seedAllEntriesOn() {
        prefs.edit(commit = true) {
            putBoolean(UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND, true)
            putBoolean(UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS, true)
            putBoolean(UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND, true)
            putBoolean(UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC, true)
            putBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, true)
            putBoolean(RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS, true)
            putBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, true)
        }
    }

    /** 打开设置页：创建入口状态并开始跟随偏好（对应设置页进入组合）。 */
    private fun openSettingsPage(): SettingsFeatureEntryState =
        SettingsFeatureEntryState(prefs, xiaomiDevice = true, appleMusicInstalled = true)
            .also { it.startObserving(); state = it }

    /** 偏好变更回调派发在主线程，等它们跑完。 */
    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun returningFromSetupReviewWithAppleMusicOnly_hidesLyricEntriesAndMovesHomeSectionsIntoSettings() {
        seedAllEntriesOn()
        val settings = openSettingsPage()
        assertTrue(settings.homePageVisible)

        // 在盖在设置页上的回顾页里选「仅 Apple Music」。
        applySetupModeEntrySideEffects(prefs, SETUP_MODE_APPLE_MUSIC_ONLY)
        idleMainLooper()

        // 返回设置页：入口开关与主页面看到的一致，主页隐藏，歌词设置与特殊功能迁入设置页。
        assertFalse(settings.hyperIsland)
        assertFalse(settings.aodLyrics)
        assertFalse(settings.dynamicIsland)
        assertTrue(settings.appleMusic)
        assertFalse(settings.homePageVisible)
    }

    @Test
    fun returningFromSetupReviewWithMiHyperIsland_closesOnlyDynamicIslandEntry() {
        seedAllEntriesOn()
        val settings = openSettingsPage()

        applySetupModeEntrySideEffects(prefs, SETUP_MODE_MI_HYPER_ISLAND)
        idleMainLooper()

        assertTrue(settings.hyperIsland)
        assertTrue(settings.aodLyrics)
        assertFalse(settings.dynamicIsland)
        assertTrue(settings.homePageVisible)
    }

    @Test
    fun returningFromSetupReviewWithDynamicIsland_reopensDynamicIslandEntryAndKeepsHomePage() {
        seedAllEntriesOn()
        val settings = openSettingsPage()
        applySetupModeEntrySideEffects(prefs, SETUP_MODE_APPLE_MUSIC_ONLY)
        idleMainLooper()
        assertFalse(settings.homePageVisible)

        applySetupModeEntrySideEffects(prefs, SETUP_MODE_DYNAMIC_ISLAND)
        idleMainLooper()

        assertTrue(settings.dynamicIsland)
        assertTrue(settings.homePageVisible)
        // 入口重新打开后，功能自身开关恢复为关闭入口前的值。
        assertTrue(prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, false))
    }

    @Test
    fun changesMadeWhileSettingsPageNotObserving_areReadWhenItObservesAgain() {
        seedAllEntriesOn()
        val settings = openSettingsPage()
        settings.stopObserving()

        prefs.edit(commit = true) { putBoolean(UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS, false) }
        idleMainLooper()
        settings.startObserving()

        assertFalse(settings.aodLyrics)
    }

    @Test
    fun clearingPreferences_fallsBackToDeviceDefaults() {
        seedAllEntriesOn()
        val settings = SettingsFeatureEntryState(prefs, xiaomiDevice = false, appleMusicInstalled = false)
            .also { it.startObserving(); state = it }

        prefs.edit(commit = true) { clear() }
        idleMainLooper()

        assertEquals(false, settings.hyperIsland)
        assertEquals(false, settings.aodLyrics)
        assertEquals(true, settings.dynamicIsland)
        assertEquals(false, settings.appleMusic)
    }
}
