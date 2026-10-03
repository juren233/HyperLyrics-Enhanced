/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture.*
import org.junit.Assert.*
import org.junit.Test

class IslandTouchGestureDetectorTest {
    private val events = mutableListOf<Pair<String, com.juren233.hyperlyricsenhanced.common.IslandTouchGesture>>()
    private val detector = IslandTouchGestureDetector<String>(8f, 32f, 50f, 500, 300) { target, gesture ->
        events += target to gesture
    }
    private fun tap(target: String = "left", time: Long = 0, double: Boolean = true, x: Float = 0f) {
        detector.down(target, x, 0f, time, double)
        detector.up(x, 0f, time + 30)
    }

    @Test fun `single tap is immediate without double binding`() {
        tap(double = false)
        assertEquals(listOf("left" to TAP), events)
        assertNull(detector.nextDeadline)
    }
    @Test fun `single tap waits only for double tap deadline`() {
        tap()
        detector.advance(329)
        assertTrue(events.isEmpty())
        detector.advance(330)
        detector.advance(600)
        assertEquals(listOf("left" to TAP), events)
    }
    @Test fun `double tap emits once without single tap`() {
        tap()
        tap(time = 130)
        detector.advance(1000)
        assertEquals(listOf("left" to DOUBLE_TAP), events)
        assertNull(detector.nextDeadline)
    }
    @Test fun `cross region taps stay independent`() {
        tap("left")
        tap("right", 130)
        detector.advance(1000)
        assertEquals(listOf("left" to TAP, "right" to TAP), events)
    }
    @Test fun `player switch does not form double tap`() {
        tap("left-player-one")
        tap("left-player-two", 130)
        detector.advance(1000)
        assertEquals(listOf("left-player-one" to TAP, "left-player-two" to TAP), events)
    }
    @Test fun `far apart taps are not a double tap`() {
        tap(x = 0f)
        tap(time = 130, x = 80f)
        detector.advance(1000)
        assertEquals(listOf("left" to TAP, "left" to TAP), events)
    }
    @Test fun `long press fires once and suppresses up and swipe`() {
        detector.down("right", 0f, 0f, 0, true)
        detector.advance(500)
        detector.advance(900)
        detector.up(-100f, 0f, 1000)
        assertEquals(listOf("right" to LONG_PRESS), events)
        assertNull(detector.nextDeadline)
    }
    @Test fun `left swipe cancels long press and tap`() {
        detector.down("right", 100f, 0f, 0, true)
        detector.move(50f, 2f)
        detector.advance(600)
        detector.up(20f, 4f, 700)
        detector.advance(1200)
        assertEquals(listOf("right" to SWIPE_LEFT), events)
    }
    @Test fun `right swipe retains down region`() {
        detector.down("left", 0f, 0f, 0, true)
        detector.up(100f, 0f, 100)
        assertEquals(listOf("left" to SWIPE_RIGHT), events)
    }
    @Test fun `vertical motion never clicks or swipes`() {
        detector.down("left", 0f, 0f, 0, true)
        detector.move(20f, 50f)
        detector.up(40f, 100f, 300)
        detector.advance(1000)
        assertTrue(events.isEmpty())
    }
    @Test fun `small drag over slop is not a tap`() {
        detector.down("left", 0f, 0f, 0, true)
        detector.up(15f, 0f, 300)
        detector.advance(1000)
        assertTrue(events.isEmpty())
    }
    @Test fun `cancel removes long press and delayed single`() {
        tap()
        detector.cancel()
        detector.advance(1000)
        detector.down("left", 0f, 0f, 1100, true)
        detector.cancel()
        detector.up(0f, 0f, 1800)
        detector.advance(2000)
        assertTrue(events.isEmpty())
        assertNull(detector.nextDeadline)
    }
    @Test fun `late second tap produces two singles`() {
        tap()
        tap(time = 500)
        detector.advance(900)
        assertEquals(listOf("left" to TAP, "left" to TAP), events)
    }
}
