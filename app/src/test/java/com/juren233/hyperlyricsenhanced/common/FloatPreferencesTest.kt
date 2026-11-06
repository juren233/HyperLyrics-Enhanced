/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertTrue
import org.junit.Test

class FloatPreferencesTest {
    @Test
    fun registersEveryFloatBackedKey() {
        assertTrue(RootConstants.KEY_HOOK_SWITCH_ANIM_CUSTOM_RATE in FloatPreferences.KEYS)
        assertTrue(RootConstants.KEY_HOOK_AI_TRANS_TOP_P in FloatPreferences.KEYS)
        assertTrue(RootConstants.KEY_HOOK_TEXT_SIZE_RATIO in FloatPreferences.KEYS)
    }
}
