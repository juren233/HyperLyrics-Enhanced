/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

/** Remembers native visibility while a single expanded-media element is hidden. */
internal class IslandMediaElementVisibilityOverride(initialVisibility: Int) {
    var hidden: Boolean = false
        private set

    private var nativeVisibility = initialVisibility

    fun apply(hide: Boolean, currentVisibility: Int): Int {
        if (hide) {
            // A native rebind can make the view visible after our binder callback. Save that
            // new native value before enforcing GONE, rather than capturing our own override.
            if (!hidden || currentVisibility != GONE) nativeVisibility = currentVisibility
            hidden = true
            return GONE
        }
        val result = if (hidden && currentVisibility == GONE) nativeVisibility else currentVisibility
        hidden = false
        return result
    }

    private companion object {
        const val GONE = 8
    }
}
