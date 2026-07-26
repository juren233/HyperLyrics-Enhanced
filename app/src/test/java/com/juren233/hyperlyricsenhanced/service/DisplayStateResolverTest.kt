package com.juren233.hyperlyricsenhanced.service

import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayStateResolverTest {

    @Test
    fun `doze display is not interactive even when power manager reports interactive`() {
        assertFalse(
            DisplayStateResolver.isInteractive(
                displayState = Display.STATE_DOZE,
                powerInteractive = true,
            )
        )
    }

    @Test
    fun `on display is interactive even when power manager lags behind`() {
        assertTrue(
            DisplayStateResolver.isInteractive(
                displayState = Display.STATE_ON,
                powerInteractive = false,
            )
        )
    }
}
