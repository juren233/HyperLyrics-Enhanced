/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandIconReservationTest {

    @Test
    fun `text limit is the budget minus the reservation`() {
        assertEquals(372, IslandIconReservation.textLimitPx(budgetPx = 450, reservedPx = 78))
    }

    @Test
    fun `no reservation gives the whole budget to the text`() {
        assertEquals(450, IslandIconReservation.textLimitPx(budgetPx = 450, reservedPx = 0))
    }

    @Test
    fun `a negative reservation is ignored and an oversized one leaves one pixel`() {
        assertEquals(450, IslandIconReservation.textLimitPx(budgetPx = 450, reservedPx = -10))
        assertEquals(1, IslandIconReservation.textLimitPx(budgetPx = 60, reservedPx = 90))
    }

    @Test
    fun `an icon that is not placed reserves nothing without touching any view`() {
        assertEquals(0, IslandIconReservation.reservedPx(container = null, isLeft = true, iconPlaced = true))
        assertEquals(0, IslandIconReservation.reservedPx(container = null, isLeft = false, iconPlaced = false))
    }
}
