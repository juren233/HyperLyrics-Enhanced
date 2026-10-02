/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class IslandReentrantBackgroundRemovalHookerTest {
    @Test
    fun `matches original plugin binary class names only`() {
        assertTrue(IslandReentrantBackgroundRemovalHooker.isTarget(
            "miui.systemui.dynamicisland.window.DynamicIslandWindowView",
            "miui.systemui.dynamicisland.DynamicIslandBackgroundView",
        ))
        assertFalse(IslandReentrantBackgroundRemovalHooker.isTarget(
            "miui.systemui.dynamicisland.window.DynamicIslandWindowViewImpl",
            "miui.systemui.dynamicisland.DynamicIslandBackgroundView",
        ))
        assertFalse(IslandReentrantBackgroundRemovalHooker.isTarget(
            "miui.systemui.dynamicisland.window.DynamicIslandWindowView",
            "miui.systemui.dynamicisland.window.DynamicIslandBackgroundView",
        ))
    }

    @Test
    fun `nested same background is skipped while new background remains independent`() {
        val gate = IslandReentrantRemovalGate()
        val window = Any()
        val oldBackground = Any()
        val newBackground = Any()
        val children = mutableListOf(oldBackground, newBackground)
        var animationCancelled = false
        fun remove(child: Any) {
            if (!gate.tryEnter(window, child)) return
            try {
                val index = children.indexOf(child)
                if (child === oldBackground && !animationCancelled) {
                    animationCancelled = true
                    remove(oldBackground) // Animation cancel callback reenters before outer removeAt.
                }
                children.removeAt(index)
            } finally {
                gate.leave(window, child)
            }
        }
        remove(oldBackground)
        assertEquals(listOf(newBackground), children)
        assertTrue(gate.tryEnter(window, oldBackground))
        gate.leave(window, oldBackground)
    }

    @Test
    fun `identity and thread boundaries do not block other removals`() {
        val gate = IslandReentrantRemovalGate()
        val firstWindow = Any()
        val secondWindow = Any()
        val oldBackground = Any()
        assertTrue(gate.tryEnter(firstWindow, oldBackground))
        assertTrue(gate.tryEnter(secondWindow, oldBackground))
        var otherThreadAllowed = false
        val thread = Thread { otherThreadAllowed = gate.tryEnter(firstWindow, oldBackground) }
        thread.start()
        thread.join()
        assertTrue(otherThreadAllowed)
        gate.leave(firstWindow, oldBackground)
        gate.leave(secondWindow, oldBackground)
    }
}
