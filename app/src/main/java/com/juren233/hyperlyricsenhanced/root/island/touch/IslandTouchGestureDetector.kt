/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture
import kotlin.math.abs

/** One pointer, fixed down target, monotonic event times. Android only supplies timers/events. */
internal class IslandTouchGestureDetector<T>(
    private val slop: Float,
    private val swipeDistance: Float,
    private val doubleTapSlop: Float,
    private val longPressMs: Long,
    private val doubleTapMs: Long,
    private val emit: (T, IslandTouchGesture) -> Unit,
) {
    private data class Contact<T>(
        val target: T, val x: Float, val y: Float, val time: Long,
        val doubleEnabled: Boolean, val secondTap: Boolean,
        val doubleTapTimeoutMs: Long,
        val longPressTimeoutMs: Long,
        var moved: Boolean = false, var handled: Boolean = false,
    )
    private data class Tap<T>(val target: T, val x: Float, val y: Float, val deadline: Long)
    private var contact: Contact<T>? = null
    private var pending: Tap<T>? = null

    val nextDeadline: Long?
        get() = listOfNotNull(pending?.deadline, contact?.takeIf { !it.moved && !it.handled }
            ?.let { it.time + it.longPressTimeoutMs }).minOrNull()

    fun down(target: T, x: Float, y: Float, time: Long, doubleEnabled: Boolean,
        doubleTapTimeoutMs: Long = doubleTapMs, longPressTimeoutMs: Long = longPressMs) {
        advance(time)
        val first = pending
        val second = doubleEnabled && first != null && first.target == target &&
            squaredDistance(x - first.x, y - first.y) <= doubleTapSlop * doubleTapSlop
        pending = null
        if (first != null && !second) emit(first.target, IslandTouchGesture.TAP)
        contact = Contact(target, x, y, time, doubleEnabled, second, doubleTapTimeoutMs, longPressTimeoutMs)
    }

    fun move(x: Float, y: Float) {
        contact?.let {
            if (squaredDistance(x - it.x, y - it.y) > slop * slop) it.moved = true
        }
    }

    fun up(x: Float, y: Float, time: Long) {
        move(x, y)
        advance(time)
        val current = contact ?: return
        contact = null
        if (current.handled) return
        val dx = x - current.x
        val dy = y - current.y
        when {
            abs(dx) >= swipeDistance && abs(dx) > abs(dy) * 1.25f -> emit(current.target,
                if (dx < 0) IslandTouchGesture.SWIPE_LEFT else IslandTouchGesture.SWIPE_RIGHT)
            current.moved -> Unit // Vertical/diagonal movement is never a click.
            current.secondTap -> emit(current.target, IslandTouchGesture.DOUBLE_TAP)
            current.doubleEnabled -> pending = Tap(current.target, x, y, time + current.doubleTapTimeoutMs)
            else -> emit(current.target, IslandTouchGesture.TAP)
        }
    }

    fun advance(time: Long) {
        pending?.takeIf { time >= it.deadline }?.let {
            pending = null
            emit(it.target, IslandTouchGesture.TAP)
        }
        contact?.takeIf { !it.moved && !it.handled && time >= it.time + it.longPressTimeoutMs }?.let {
            it.handled = true
            emit(it.target, IslandTouchGesture.LONG_PRESS)
        }
    }

    fun cancel() { contact = null; pending = null }
    private fun squaredDistance(x: Float, y: Float) = x * x + y * y
}
