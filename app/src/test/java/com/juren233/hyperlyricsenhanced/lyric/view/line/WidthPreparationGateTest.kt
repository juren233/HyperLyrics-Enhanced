/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.*
import org.junit.Test

class WidthPreparationGateTest {
    @Test fun onlyStableFramesAuthorizeBackgroundWork() {
        val gate = WidthPreparationGate()
        repeat(12) { gate.onFrame(true); assertFalse(gate.isOpen) }
        gate.onFrame(false)
        assertFalse(gate.isOpen)
        gate.onFrame(false)
        assertTrue(gate.isOpen)
        assertFalse(gate.needsObservation)
    }

    @Test fun newTransitionClosesSynchronouslyBeforeAnimatorCallback() {
        val gate = WidthPreparationGate()
        repeat(2) { gate.onFrame(false) }
        gate.onTransitionStarted()
        assertFalse(gate.isOpen)
        assertTrue(gate.needsObservation)
    }

    @Test fun cancellationAndReversalCannotReleaseAQuietGap() {
        val gate = WidthPreparationGate()
        gate.onFrame(false)
        gate.onTransitionStarted()
        gate.onFrame(false)
        assertFalse(gate.isOpen)
        gate.onFrame(true)
        gate.onFrame(false)
        assertFalse(gate.isOpen)
        gate.onFrame(false)
        assertTrue(gate.isOpen)
    }

    @Test fun unknownMotionDoesNotComputeOrPollForever() {
        val gate = WidthPreparationGate()
        gate.onFrame(null)
        assertFalse(gate.isOpen)
        assertFalse(gate.needsObservation)
        gate.requestObservation()
        assertTrue(gate.needsObservation)
        repeat(2) { gate.onFrame(false) }
        assertTrue(gate.isOpen)
    }

    @Test fun settledCollapsedAndExpandedBothResumeOncePerPause() {
        val gate = WidthPreparationGate()
        var resumed = 0
        val listener: () -> Unit = { resumed++ }
        gate.subscribe(listener)
        repeat(8) { gate.onFrame(false) } // settled at the status bar
        assertEquals(1, resumed)
        gate.onTransitionStarted()
        gate.onFrame(true)
        repeat(8) { gate.onFrame(false) } // settled expanded: no shape-name gate
        assertEquals(2, resumed)
        gate.unsubscribe(listener)
        gate.onTransitionStarted()
        repeat(2) { gate.onFrame(false) }
        assertEquals(2, resumed)
    }

    @Test fun repeatedPreparationDoesNotResetQuietFrameConfirmation() {
        val gate = WidthPreparationGate()
        gate.requestObservation()
        gate.onFrame(false)
        repeat(12) { gate.requestObservation() }
        gate.onFrame(false)
        assertTrue(gate.isOpen)
    }
}
