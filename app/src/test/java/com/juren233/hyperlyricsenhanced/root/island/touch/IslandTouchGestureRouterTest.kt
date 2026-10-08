/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture
import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture.*
import org.junit.Assert.*
import org.junit.Test

class IslandTouchGestureRouterTest {
    private val custom = mutableSetOf<IslandTouchGesture>()
    private val actions = mutableListOf<String>()
    private val router = IslandTouchGestureRouter<String>(8f, 32f, 50f, 500, 300,
        isCustom = { _, gesture -> gesture in custom },
        cancelNative = { actions += "cancel native" },
        emit = { side, gesture -> actions += "${if (gesture in custom) "custom" else "native"} $side $gesture" },
    )
    private fun down(time: Long = 0) = router.down("left", 0f, 0f, time)
    private fun tap(time: Long = 0) {
        down(time)
        assertTrue(router.up(0f, 0f, time + 30))
    }

    @Test fun `unassigned single uses native action even with another gesture customized`() {
        custom += SWIPE_LEFT
        tap()
        assertEquals(listOf("cancel native", "native left TAP"), actions)
        assertNull(router.nextDeadline)
    }

    @Test fun `custom single cancels native before acting`() {
        custom += TAP
        tap()
        assertEquals(listOf("cancel native", "custom left TAP"), actions)
    }

    @Test fun `native single waits for custom double and fires only once`() {
        custom += DOUBLE_TAP
        tap()
        assertEquals(listOf("cancel native"), actions)
        router.advance(330)
        router.advance(900)
        assertEquals(listOf("cancel native", "native left TAP"), actions)
    }

    @Test fun `custom double never runs native single`() {
        custom += DOUBLE_TAP
        tap()
        tap(130)
        router.advance(1000)
        assertEquals(listOf("cancel native", "cancel native", "custom left DOUBLE_TAP"), actions)
    }

    @Test fun `double disabled preserves successive single actions`() {
        custom += TAP
        tap()
        tap(130)
        assertEquals(2, actions.count { it == "custom left TAP" })
        assertNull(router.nextDeadline)
    }

    @Test fun `unassigned long press lets native own timing and release`() {
        custom += TAP
        down()
        assertFalse(router.interceptNativeLongPress())
        assertTrue(router.nativeOwnsContact)
        router.advance(1000)
        assertFalse(router.up(0f, 0f, 1100))
        assertTrue(actions.isEmpty())
    }

    @Test fun `native long press after module timer still keeps original behavior`() {
        custom += TAP
        down()
        router.advance(500)
        assertTrue(actions.isEmpty())
        assertFalse(router.interceptNativeLongPress())
        assertFalse(router.up(0f, 0f, 1000))
        assertTrue(actions.isEmpty())
    }

    @Test fun `custom long press blocks native and cannot turn into native swipe on release`() {
        custom += LONG_PRESS
        down()
        assertTrue(router.interceptNativeLongPress())
        router.advance(500)
        assertTrue(router.move(-100f, 0f))
        assertTrue(router.up(-100f, 0f, 800))
        assertEquals(listOf("cancel native", "custom left LONG_PRESS"), actions)
    }

    @Test fun `both unassigned swipe directions preserve native stream on a customized side`() {
        custom += TAP
        for (x in listOf(-80f, 80f)) {
            down()
            assertFalse(router.move(x, 0f))
            router.advance(700)
            assertFalse(router.up(x, 0f, 800))
        }
        assertTrue(actions.isEmpty())
    }

    @Test fun `one custom swipe does not intercept the other direction`() {
        custom += SWIPE_LEFT
        down()
        assertFalse(router.move(80f, 0f))
        assertFalse(router.up(80f, 0f, 100))
        assertTrue(actions.isEmpty())
        down(200)
        assertTrue(router.move(-12f, 0f))
        assertTrue(router.up(-80f, 0f, 300))
        assertEquals(listOf("cancel native", "custom left SWIPE_LEFT"), actions)
    }

    @Test fun `small movement preserves native long press but custom swipe cancels it`() {
        custom += SWIPE_LEFT
        down()
        assertFalse(router.move(-4f, 0f))
        assertTrue(actions.isEmpty())
        assertTrue(router.move(-20f, 0f))
        assertTrue(router.interceptNativeLongPress())
        router.advance(800)
        assertTrue(router.up(-90f, 0f, 900))
        assertEquals(listOf("cancel native", "custom left SWIPE_LEFT"), actions)
    }

    @Test fun `vertical gestures hand back to native without custom tap or long press`() {
        custom += setOf(TAP, LONG_PRESS, SWIPE_LEFT, SWIPE_RIGHT)
        down()
        assertFalse(router.move(4f, 50f))
        router.advance(900)
        assertFalse(router.up(5f, 100f, 1000))
        assertTrue(actions.isEmpty())
    }

    @Test fun `reversing a captured custom drag cannot cause an unrelated action`() {
        custom += setOf(SWIPE_LEFT, SWIPE_RIGHT)
        down()
        assertTrue(router.move(-20f, 0f))
        assertTrue(router.up(80f, 0f, 200))
        assertEquals(listOf("cancel native"), actions)
    }

    @Test fun `cancel removes native press and all deferred custom work`() {
        custom += setOf(TAP, DOUBLE_TAP, LONG_PRESS)
        down()
        router.cancel()
        router.advance(1000)
        assertEquals(listOf("cancel native"), actions)
        assertNull(router.nextDeadline)
        actions.clear()
        tap(1200)
        router.cancel()
        router.advance(2000)
        assertEquals(listOf("cancel native"), actions)
    }

    @Test fun `configured shorter interval ends single wait at its exact deadline`() {
        custom += setOf(TAP, DOUBLE_TAP)
        router.down("left", 0f, 0f, 0, doubleTapTimeoutMs = 200)
        router.up(0f, 0f, 30)
        assertEquals(230L, router.nextDeadline)
        router.advance(229)
        assertEquals(listOf("cancel native"), actions)
        router.advance(230)
        router.advance(900)
        assertEquals(listOf("cancel native", "custom left TAP"), actions)
    }

    @Test fun `second down inside shorter interval remains a double when second up is later`() {
        custom += setOf(TAP, DOUBLE_TAP)
        router.down("left", 0f, 0f, 0, doubleTapTimeoutMs = 200)
        router.up(0f, 0f, 30)
        router.down("left", 0f, 0f, 229, doubleTapTimeoutMs = 200)
        router.up(0f, 0f, 270)
        router.advance(900)
        assertEquals(listOf("cancel native", "cancel native", "custom left DOUBLE_TAP"), actions)
    }

    @Test fun `tap beyond shorter interval stays two singles without double side effects`() {
        custom += setOf(TAP, DOUBLE_TAP)
        router.down("left", 0f, 0f, 0, doubleTapTimeoutMs = 150)
        router.up(0f, 0f, 30)
        router.down("left", 0f, 0f, 181, doubleTapTimeoutMs = 150)
        router.up(0f, 0f, 211)
        router.advance(1000)
        assertEquals(listOf("cancel native", "custom left TAP", "cancel native", "custom left TAP"), actions)
    }

    @Test fun `new contacts use changed interval without rebuilding the router`() {
        custom += setOf(TAP, DOUBLE_TAP)
        router.down("left", 0f, 0f, 0, doubleTapTimeoutMs = 150)
        router.up(0f, 0f, 30)
        router.advance(180)
        actions.clear()
        router.down("left", 0f, 0f, 1000, doubleTapTimeoutMs = 500)
        router.up(0f, 0f, 1030)
        router.advance(1529)
        assertEquals(listOf("cancel native"), actions)
        router.advance(1530)
        assertEquals(listOf("cancel native", "custom left TAP"), actions)
    }

    @Test fun `interval does not delay single when double tap is disabled`() {
        custom += TAP
        router.down("left", 0f, 0f, 0, doubleTapTimeoutMs = 500)
        router.up(0f, 0f, 30)
        assertEquals(listOf("cancel native", "custom left TAP"), actions)
        assertNull(router.nextDeadline)
    }

    @Test fun `system single fallback observes configured interval without duplicate actions`() {
        custom += DOUBLE_TAP
        router.down("left", 0f, 0f, 0, doubleTapTimeoutMs = 250)
        router.up(0f, 0f, 30)
        router.advance(279)
        assertEquals(listOf("cancel native"), actions)
        router.advance(280)
        router.advance(1000)
        assertEquals(listOf("cancel native", "native left TAP"), actions)
    }

    @Test fun `short custom long press fires at configured time before native deadline only once`() {
        custom += setOf(TAP, LONG_PRESS)
        router.down("left", 0f, 0f, 0, longPressTimeoutMs = 200)
        assertEquals(200L, router.nextDeadline)
        router.advance(199)
        assertTrue(actions.isEmpty())
        router.advance(200)
        assertTrue(router.interceptNativeLongPress())
        router.advance(500)
        router.up(0f, 0f, 600)
        assertEquals(listOf("cancel native", "custom left LONG_PRESS"), actions)
        assertNull(router.nextDeadline)
    }

    @Test fun `long custom deadline suppresses earlier native callback without firing early`() {
        custom += setOf(TAP, LONG_PRESS)
        router.down("left", 0f, 0f, 0, longPressTimeoutMs = 900)
        router.advance(500)
        assertTrue(router.interceptNativeLongPress())
        assertTrue(actions.isEmpty())
        assertEquals(900L, router.nextDeadline)
        router.advance(899)
        assertTrue(actions.isEmpty())
        router.advance(900)
        router.up(0f, 0f, 1000)
        assertEquals(listOf("cancel native", "custom left LONG_PRESS"), actions)
    }

    @Test fun `release before configured long press time remains a single tap`() {
        custom += setOf(TAP, LONG_PRESS)
        router.down("left", 0f, 0f, 0, longPressTimeoutMs = 900)
        assertTrue(router.interceptNativeLongPress())
        router.up(0f, 0f, 700)
        router.advance(1500)
        assertEquals(listOf("cancel native", "custom left TAP"), actions)
    }

    @Test fun `custom time does not shorten unassigned native long press or swallow an earlier tap`() {
        custom += TAP
        router.down("left", 0f, 0f, 0, longPressTimeoutMs = 200)
        assertEquals(500L, router.nextDeadline)
        router.advance(200)
        router.up(0f, 0f, 300)
        assertEquals(listOf("cancel native", "custom left TAP"), actions)
        actions.clear()
        router.down("left", 0f, 0f, 1000, longPressTimeoutMs = 1500)
        assertFalse(router.interceptNativeLongPress())
        router.advance(2500)
        assertFalse(router.up(0f, 0f, 2600))
        assertTrue(actions.isEmpty())
    }

    @Test fun `later contacts use new long press time and movement cancels the deadline`() {
        custom += setOf(LONG_PRESS, SWIPE_LEFT)
        router.down("left", 0f, 0f, 0, longPressTimeoutMs = 200)
        router.advance(200)
        router.up(0f, 0f, 300)
        actions.clear()
        router.down("left", 0f, 0f, 1000, longPressTimeoutMs = 900)
        assertEquals(1900L, router.nextDeadline)
        router.advance(1500)
        assertTrue(actions.isEmpty())
        router.move(-40f, 0f)
        assertNull(router.nextDeadline)
        router.advance(1900)
        router.up(-80f, 0f, 2000)
        assertEquals(listOf("cancel native", "custom left SWIPE_LEFT"), actions)
    }

    @Test fun `continuous drag reports travel from the press and reverses without a release swipe`() {
        val drags = mutableListOf<Float>()
        val router = IslandTouchGestureRouter<String>(8f, 32f, 50f, 500, 300,
            isCustom = { _, gesture -> gesture == SWIPE_LEFT },
            cancelNative = { actions += "cancel native" },
            emit = { side, gesture -> actions += "emit $side $gesture" },
            isContinuous = { _, gesture -> gesture == SWIPE_LEFT },
            startDrag = { side, gesture -> actions += "start $side $gesture" },
            dragTo = { _, dx -> drags += dx },
        )
        router.down("left", 0f, 0f, 0)
        assertFalse(router.move(-4f, 0f))
        assertTrue(router.move(-20f, 2f))
        assertTrue(router.move(-90f, 0f))
        assertTrue(router.move(30f, 0f))
        assertTrue(router.up(10f, 0f, 400))
        router.advance(2000)
        assertEquals(listOf("cancel native", "start left SWIPE_LEFT"), actions)
        assertEquals(listOf(-20f, -90f, 30f, 10f), drags)
        assertNull(router.nextDeadline)
    }

    @Test fun `continuous binding on one direction leaves the other direction to native`() {
        val router = IslandTouchGestureRouter<String>(8f, 32f, 50f, 500, 300,
            isCustom = { _, gesture -> gesture == SWIPE_LEFT },
            cancelNative = { actions += "cancel native" },
            emit = { side, gesture -> actions += "emit $side $gesture" },
            isContinuous = { _, gesture -> gesture == SWIPE_LEFT },
            startDrag = { _, _ -> actions += "start" },
        )
        router.down("left", 0f, 0f, 0)
        assertFalse(router.move(20f, 0f))
        assertTrue(router.nativeOwnsContact)
        assertFalse(router.up(-80f, 0f, 400))
        assertEquals(emptyList<String>(), actions)
    }
}
