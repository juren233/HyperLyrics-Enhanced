package com.juren233.hyperlyricsenhanced.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display

internal object DisplayStateResolver {
    fun isInteractive(context: Context): Boolean {
        val displayState = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.state
        val powerInteractive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        return isInteractive(displayState, powerInteractive)
    }

    internal fun isInteractive(displayState: Int?, powerInteractive: Boolean): Boolean =
        when (displayState) {
            Display.STATE_OFF,
            Display.STATE_DOZE,
            Display.STATE_DOZE_SUSPEND -> false

            Display.STATE_ON,
            Display.STATE_VR,
            Display.STATE_ON_SUSPEND -> true

            else -> powerInteractive
        }
}
