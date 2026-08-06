/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderDelayEditorStateTest {
    @Test
    fun `card and editor observe the same rounded value while dragging`() {
        val state = ProviderDelayEditorState(initialDelay = 0)

        state.updateSlider(724f)

        assertEquals(700, state.currentDelay)
        assertEquals("+700 ms", formatProviderDelay(state.currentDelay))
    }

    @Test
    fun `finishing snaps slider and displayed value to the persisted step`() {
        val state = ProviderDelayEditorState(initialDelay = 0)
        state.updateSlider(-726f)

        val finalValue = state.finishSlider()

        assertEquals(-750, finalValue)
        assertEquals(-750, state.currentDelay)
        assertEquals(-750f, state.sliderPosition)
        assertEquals("-750 ms", formatProviderDelay(state.currentDelay))
    }
}
