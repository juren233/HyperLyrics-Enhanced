/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import java.util.concurrent.Executors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandPremeasureScopeTest {
    private data class Node(val id: Int)
    private val host = Node(0)
    private val left = Node(1)
    private val right = Node(2)
    private val measured = IslandNaturalWidthSnapshot(left, right, 1200, 104, 104)

    @Test
    fun onlyTheSameHostCanConsumeTheMeasurementAndOnlyOnce() {
        val scope = IslandPremeasureScope<Node, Node>()
        assertNull(scope.take(host))
        scope.run(host, measured) {
            assertNull(scope.take(Node(0)))
            assertSame(measured, scope.take(host))
            assertNull(scope.take(host))
        }
        assertNull(scope.take(host))
    }

    @Test
    fun nestedRequestsRestoreTheOuterMeasurementWithoutSharingResults() {
        val scope = IslandPremeasureScope<Node, Node>()
        val nestedHost = Node(3)
        val nestedMeasurement = measured.copy(availableWidth = 900)
        scope.run(host, measured) {
            scope.run(nestedHost, nestedMeasurement) {
                assertNull(scope.take(host))
                assertSame(nestedMeasurement, scope.take(nestedHost))
            }
            assertSame(measured, scope.take(host))
        }
    }

    @Test
    fun failedOrDisabledPremeasurementCannotBorrowAnOuterResult() {
        val scope = IslandPremeasureScope<Node, Node>()
        scope.run(host, measured) {
            scope.run(host, null) { assertNull(scope.take(host)) }
            assertSame(measured, scope.take(host))
        }
    }

    @Test
    fun exceptionsCannotLeakMeasurementsIntoTheNextRequest() {
        val scope = IslandPremeasureScope<Node, Node>()
        runCatching { scope.run(host, measured) { error("native failure") } }
        assertNull(scope.take(host))
        scope.run(host, null) { assertNull(scope.take(host)) }
    }

    @Test
    fun measurementsNeverCrossThreads() {
        val scope = IslandPremeasureScope<Node, Node>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            scope.run(host, measured) {
                assertNull(executor.submit<IslandNaturalWidthSnapshot<Node>?> { scope.take(host) }.get())
                assertSame(measured, scope.take(host))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun replacingEitherAreaInvalidatesEvenAnEqualLookingSnapshot() {
        assertTrue(valid())
        assertFalse(valid(left = Node(1)))
        assertFalse(valid(right = Node(2)))
        assertFalse(valid(left = null))
        assertFalse(valid(right = null))
    }

    @Test
    fun contentOrLayoutChangesAlwaysRequireFreshMeasurement() {
        assertFalse(valid(contentChanged = true))
        assertFalse(valid(layoutPending = true))
        assertFalse(valid(contentChanged = true, layoutPending = true))
    }

    @Test
    fun everyMeasureSpecChangeRequiresFreshMeasurement() {
        assertFalse(valid(width = 1199))
        assertFalse(valid(leftHeight = 103))
        assertFalse(valid(rightHeight = 105))
    }

    private fun valid(
        left: Node? = this.left,
        right: Node? = this.right,
        width: Int = 1200,
        leftHeight: Int = 104,
        rightHeight: Int = 104,
        contentChanged: Boolean = false,
        layoutPending: Boolean = false,
    ) = measured.isStillValid(left, right, width, leftHeight, rightHeight, contentChanged, layoutPending)
}
