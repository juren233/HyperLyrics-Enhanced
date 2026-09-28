/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class TopBarProgressiveBlurPreferenceTest {
    @Test
    fun defaultAndSavedSwitch() {
        assertTrue(TopBarProgressiveBlurPreference.read(preferences(emptyMap())))
        assertFalse(TopBarProgressiveBlurPreference.read(preferences(mapOf(
            UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR to false,
        ))))
    }

    @Test
    fun oldSelectionMapsToSwitch() {
        assertFalse(TopBarProgressiveBlurPreference.read(preferences(mapOf(
            UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE to 0,
        ))))
        assertTrue(TopBarProgressiveBlurPreference.read(preferences(mapOf(
            UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE to 1,
        ))))
        assertTrue(TopBarProgressiveBlurPreference.read(preferences(mapOf(
            UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE to 2,
        ))))
    }

    @Test
    fun savedSwitchOverridesOldSelection() {
        assertFalse(TopBarProgressiveBlurPreference.read(preferences(mapOf(
            UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR to false,
            UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE to 2,
        ))))
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
