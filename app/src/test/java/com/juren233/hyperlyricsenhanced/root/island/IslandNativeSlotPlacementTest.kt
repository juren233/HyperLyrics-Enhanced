/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Test

class IslandNativeSlotPlacementTest {
    private val nativeRight = Gravity.END or Gravity.CENTER_VERTICAL

    @Test fun `rhythm stays at native right edge across content widths and alignments`() {
        // Fresh host evidence: area=275, content=182. Include wider/narrower lines
        // and START/CENTER/END placements without changing content measurement.
        for (width in listOf(182, 100, 275)) {
            for (left in listOf(0, (275 - width) / 2, 275 - width)) {
                val offset = IslandNativeSlotPlacement.rhythmOffset(
                    275, 0, 0, 0, 0, left, width, false)
                assertEquals(275f, left + width + offset, 0f)
            }
        }
        assertEquals(93f, IslandNativeSlotPlacement.rhythmOffset(
            275, 0, 0, 0, 0, 0, 182, false), 0f)
    }

    @Test fun `native padding and margins remain at the end edge`() {
        assertEquals(258f, 15 + 182 + IslandNativeSlotPlacement.rhythmOffset(
            275, 8, 10, 7, 7, 15, 182, false), 0f)
        assertEquals(15f, 78 + IslandNativeSlotPlacement.rhythmOffset(
            275, 8, 10, 7, 7, 78, 182, true), 0f)
    }

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
