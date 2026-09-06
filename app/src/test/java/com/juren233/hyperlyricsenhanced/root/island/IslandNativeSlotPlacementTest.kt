/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Test

class IslandNativeSlotPlacementTest {
    private val nativeRight = Gravity.END or Gravity.CENTER_VERTICAL

    @Test fun `short default content anchors native module at start not end`() {
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL,
            IslandNativeSlotPlacement.resolveGravity(nativeRight, true, Gravity.START))
    }

    @Test fun `explicit center and end remain selectable`() {
        for (horizontal in listOf(Gravity.CENTER_HORIZONTAL, Gravity.END)) {
            assertEquals(horizontal or Gravity.CENTER_VERTICAL,
                IslandNativeSlotPlacement.resolveGravity(nativeRight, true, horizontal))
        }
    }

    @Test fun `disabled dynamic width or removed injection restores exact native gravity`() {
        for (original in listOf(nativeRight, Gravity.START or Gravity.BOTTOM, -1)) {
            assertEquals(original, IslandNativeSlotPlacement.resolveGravity(original, false, Gravity.START))
        }
    }

    @Test fun `left slot and vertical gravity are preserved`() {
        assertEquals(Gravity.START or Gravity.BOTTOM,
            IslandNativeSlotPlacement.resolveGravity(Gravity.START or Gravity.BOTTOM, true, Gravity.START))
    }

    @Test fun `unspecified frame gravity does not propagate invalid mask bits`() {
        assertEquals(Gravity.START or Gravity.TOP,
            IslandNativeSlotPlacement.resolveGravity(-1, true, Gravity.START))
    }
}
