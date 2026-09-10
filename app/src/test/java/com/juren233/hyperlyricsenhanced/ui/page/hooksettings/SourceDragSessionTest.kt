/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDragSessionTest {
    private val order = listOf(Source.NE, Source.QM, Source.KUWO, Source.KUGOU)

    @Test
    fun `every source can cross multiple rows in either direction without changing other priorities`() {
        for (from in order.indices) for (to in order.indices) {
            val session = SourceDragSession(order, order[from]).moveBy((to - from).toFloat())
            val result = session.finish(cancelled = false).previewOrder
            assertEquals(order[from], result[to])
            assertEquals(order.filter { it != order[from] }, result.filter { it != order[from] })
            assertEquals(order.toSet(), result.toSet())
            assertEquals(order.size, result.size)
        }
    }

    @Test
    fun `holding near a crossing does not chatter and reversing still works`() {
        var session = SourceDragSession(order, Source.NE).moveBy(0.59f)
        assertEquals(1, session.targetIndex)
        for (delta in listOf(-0.05f, 0.03f, -0.06f, 0.04f, -0.04f)) {
            session = session.moveBy(delta)
            assertEquals(1, session.targetIndex)
        }
        session = session.moveBy(-0.1f)
        assertEquals(0, session.targetIndex)
        assertEquals(order, session.previewOrder)
    }

    @Test
    fun `dragging past either edge has no accumulated dead zone on reversal`() {
        val atEnd = SourceDragSession(order, Source.NE).moveBy(100f)
        assertEquals(3f, atEnd.position)
        assertEquals(2, atEnd.moveBy(-0.6f).targetIndex)
        val atStart = atEnd.moveBy(-100f)
        assertEquals(0f, atStart.position)
        assertEquals(1, atStart.moveBy(0.6f).targetIndex)
    }

    @Test
    fun `cancelling at any position restores the original order but retains landing origin`() {
        for (from in order.indices) for (to in order.indices) {
            val dragging = SourceDragSession(order, order[from]).moveBy((to - from).toFloat())
            val cancelled = dragging.finish(cancelled = true)
            assertTrue(cancelled.released)
            assertEquals(order, cancelled.previewOrder)
            assertEquals(dragging.position, cancelled.position)
            assertEquals(cancelled, cancelled.moveBy(10f))
        }
    }

    @Test
    fun `release is terminal and cancellation cannot undo an already committed drop`() {
        val dropped = SourceDragSession(order, Source.NE).moveBy(2.7f).finish(cancelled = false)
        assertEquals(listOf(Source.QM, Source.KUWO, Source.KUGOU, Source.NE), dropped.previewOrder)
        assertEquals(dropped, dropped.finish(cancelled = true))
        assertEquals(dropped, dropped.moveBy(-2f))
    }

    @Test
    fun `long press without moving preserves priority and non finite input is ignored`() {
        val session = SourceDragSession(order, Source.KUWO)
        assertEquals(order, session.finish(cancelled = false).previewOrder)
        assertEquals(session, session.moveBy(Float.NaN))
        assertEquals(session, session.moveBy(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a second drag starts from the previously saved order`() {
        val first = SourceDragSession(order, Source.NE).moveBy(3f).finish(false).previewOrder
        val second = SourceDragSession(first, Source.QM).moveBy(2f).finish(false).previewOrder
        assertEquals(listOf(Source.KUWO, Source.KUGOU, Source.QM, Source.NE), second)
    }

    @Test
    fun `scroll compensation is equivalent to finger movement`() {
        val session = SourceDragSession(order, Source.NE)
        val scrolled = session.moveBy(0.3f).moveBy(0.4f)
        assertEquals(session.moveBy(0.7f).previewOrder, scrolled.previewOrder)
        assertEquals(0.7f, scrolled.position, 0.001f)
    }
}
