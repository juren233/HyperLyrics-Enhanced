/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture
import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture.*
import kotlin.math.abs

/**
 * Native receives DOWN for its press/long-press handling. Custom gestures cancel that
 * stream before acting; unassigned drags hand the live stream back without replay.
 * Unassigned taps use the native click entry after the optional double-tap deadline.
 */
internal class IslandTouchGestureRouter<T>(
    private val slop: Float,
    swipeDistance: Float,
    doubleTapSlop: Float,
    private val longPressMs: Long,
    private val doubleTapMs: Long,
    private val isCustom: (T, IslandTouchGesture) -> Boolean,
    private val cancelNative: () -> Unit,
    private val emit: (T, IslandTouchGesture) -> Unit,
) {
    private val detector = IslandTouchGestureDetector<T>(
        slop, swipeDistance, doubleTapSlop, longPressMs, doubleTapMs,
    ) { target, gesture ->
        if (gesture == LONG_PRESS) {
            // The system owns the timing, eligibility and feedback of native long press.
            if (isCustom(target, gesture)) {
                customHandled = true
                cancelNativeOnce()
                emit(target, gesture)
            }
        } else if (drag == null || drag == gesture) {
            emit(target, gesture)
        }
    }
    var active: T? = null
        private set
    var nativeOwnsContact = false
        private set
    private var nativeStarted = false
    private var customHandled = false
    private var drag: IslandTouchGesture? = null
    private var downX = 0f
    private var downY = 0f
    val nextDeadline: Long? get() = detector.nextDeadline

    fun down(target: T, x: Float, y: Float, time: Long, doubleTapTimeoutMs: Long = doubleTapMs,
        longPressTimeoutMs: Long = longPressMs) {
        if (active != null) cancel()
        // Resolve the previous pending tap before installing the new contact.
        detector.down(target, x, y, time, isCustom(target, DOUBLE_TAP), doubleTapTimeoutMs,
            if (isCustom(target, LONG_PRESS)) longPressTimeoutMs else longPressMs)
        active = target
        nativeOwnsContact = false
        nativeStarted = true
        customHandled = false
        drag = null
        downX = x
        downY = y
    }

    /** true consumes this event; false lets the original dispatch continue. */
    fun move(x: Float, y: Float): Boolean {
        val target = active ?: return true
        if (nativeOwnsContact) return false
        if (customHandled) return true
        val dx = x - downX
        val dy = y - downY
        if (drag == null && dx * dx + dy * dy > slop * slop) {
            val direction = if (abs(dx) > abs(dy)) {
                if (dx < 0) SWIPE_LEFT else SWIPE_RIGHT
            } else null
            if (direction == null || !isCustom(target, direction)) {
                detector.cancel()
                nativeOwnsContact = true
                return false
            }
            drag = direction
            cancelNativeOnce()
        }
        detector.move(x, y)
        // Small motion must reach native to keep its long-press eligibility accurate.
        return !nativeStarted
    }

    fun up(x: Float, y: Float, time: Long): Boolean {
        move(x, y)
        val consumed = !nativeOwnsContact
        if (consumed) {
            cancelNativeOnce()
            detector.up(x, y, time)
        }
        active = null
        nativeStarted = false
        nativeOwnsContact = false
        customHandled = false
        drag = null
        return consumed
    }

    /** Suppress native long press only while this contact belongs to custom handling. */
    fun interceptNativeLongPress(): Boolean {
        val target = active ?: return false
        if (nativeOwnsContact) return false
        if (!nativeStarted || isCustom(target, LONG_PRESS)) return true
        detector.cancel()
        nativeOwnsContact = true
        return false
    }

    fun advance(time: Long) = detector.advance(time)

    fun cancel() {
        detector.cancel()
        cancelNativeOnce()
        active = null
        nativeOwnsContact = false
        drag = null
    }

    private fun cancelNativeOnce() {
        if (!nativeStarted) return
        nativeStarted = false
        cancelNative()
    }
}
