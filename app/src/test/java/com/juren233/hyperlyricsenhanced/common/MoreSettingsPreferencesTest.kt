/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 「更多」子页开关的默认值：没写过键（新装、或旧版本里从没拨过开关）读到新默认；
 * 已写入的值（旧版本里拨过的）原样保留。
 */
class MoreSettingsPreferencesTest {

    @Test
    fun keysAbsent_interfaceSwitchesDefaultOn() {
        val prefs = preferences(emptyMap())
        assertEquals("预测性返回", true, MoreSettingsPreferences.predictiveBackGesture(prefs))
        assertEquals("侧滑返回", true, MoreSettingsPreferences.swipeBackGesture(prefs))
        assertEquals("平行窗口", true, MoreSettingsPreferences.parallelWindowUi(prefs))
        assertEquals("顶栏渐进模糊", true, MoreSettingsPreferences.topBarProgressiveBlur(prefs))
        assertEquals("悬浮底栏", true, MoreSettingsPreferences.floatingNavBar(prefs))
    }

    @Test
    fun keysAbsent_hidingSwitchesDefaultOff() {
        val prefs = preferences(emptyMap())
        assertEquals("最近任务中隐藏", false, MoreSettingsPreferences.excludeFromRecents(prefs))
        assertEquals("隐藏桌面图标", false, MoreSettingsPreferences.hideAppIcon(prefs))
    }

    @Test
    fun storedOffValues_areKeptAfterUpgrade() {
        val prefs = preferences(
            mapOf(
                UIConstants.KEY_PREDICTIVE_BACK_GESTURE to false,
                UIConstants.KEY_SWIPE_BACK_GESTURE to false,
                UIConstants.KEY_PARALLEL_WINDOW_UI to false,
                UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR to false,
                UIConstants.KEY_FLOATING_NAV_BAR to false,
                UIConstants.KEY_EXCLUDE_FROM_RECENTS to false,
                UIConstants.KEY_HIDE_APP_ICON to false,
            )
        )
        assertEquals(false, MoreSettingsPreferences.predictiveBackGesture(prefs))
        assertEquals(false, MoreSettingsPreferences.swipeBackGesture(prefs))
        assertEquals(false, MoreSettingsPreferences.parallelWindowUi(prefs))
        assertEquals(false, MoreSettingsPreferences.topBarProgressiveBlur(prefs))
        assertEquals(false, MoreSettingsPreferences.floatingNavBar(prefs))
        assertEquals(false, MoreSettingsPreferences.excludeFromRecents(prefs))
        assertEquals(false, MoreSettingsPreferences.hideAppIcon(prefs))
    }

    @Test
    fun storedOnValues_areKeptAfterUpgrade() {
        val prefs = preferences(
            mapOf(
                UIConstants.KEY_PREDICTIVE_BACK_GESTURE to true,
                UIConstants.KEY_FLOATING_NAV_BAR to true,
                UIConstants.KEY_EXCLUDE_FROM_RECENTS to true,
                UIConstants.KEY_HIDE_APP_ICON to true,
            )
        )
        assertEquals(true, MoreSettingsPreferences.predictiveBackGesture(prefs))
        assertEquals(true, MoreSettingsPreferences.floatingNavBar(prefs))
        assertEquals(true, MoreSettingsPreferences.excludeFromRecents(prefs))
        assertEquals(true, MoreSettingsPreferences.hideAppIcon(prefs))
    }

    @Test
    fun legacyBlurModeOff_isKept() {
        val prefs = preferences(mapOf(UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE to 0))
        assertEquals(false, MoreSettingsPreferences.topBarProgressiveBlur(prefs))
    }

    private fun preferences(values: Map<String, Any>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            val key = args?.firstOrNull() as? String
            when (method.name) {
                "contains" -> values.containsKey(key)
                "getInt", "getBoolean" -> values[key] ?: args?.get(1)
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
}
