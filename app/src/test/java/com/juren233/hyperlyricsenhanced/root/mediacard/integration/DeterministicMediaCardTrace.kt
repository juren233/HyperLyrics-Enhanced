/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.integration

class ManualFrameClock(
    private val frameIntervalNanos: Long = 16_666_667L,
) {
    init {
        require(frameIntervalNanos > 0) { "frame interval must be positive" }
    }

    var nowNanos: Long = 0L
        private set

    var frameId: Long = 0L
        private set

    fun advanceBy(deltaNanos: Long) {
        require(deltaNanos >= 0) { "delta must not be negative" }
        nowNanos += deltaNanos
    }

    fun nextFrame(): Long {
        nowNanos += frameIntervalNanos
        frameId += 1L
        return frameId
    }
}

class DeterministicMediaCardTrace(
    private val clock: ManualFrameClock,
) {
    private var nextEventSequence = 0L

    private val recordedEvents = mutableListOf<TraceEvent>()
    private val recordedFrames = mutableListOf<PresentedFrame>()

    val events: List<TraceEvent>
        get() = recordedEvents.toList()

    val frames: List<PresentedFrame>
        get() = recordedFrames.toList()

    fun record(
        sessionId: TraceSessionId,
        controllerId: TraceControllerId,
        type: TraceEventType,
        classLoaderId: String? = null,
        listenerId: TraceListenerId? = null,
        token: TraceTransitionToken? = null,
        snapshotSequence: Long? = null,
        fraction: Float? = null,
        callbackDisposition: TraceCallbackDisposition = TraceCallbackDisposition.NOT_APPLICABLE,
        mutatesUi: Boolean = false,
        visibleRootCount: Int? = null,
        activeResourceCount: Int? = null,
        detail: String? = null,
        playerId: TraceControllerId? = null,
    ): TraceEvent {
        val event = TraceEvent(
            eventSequence = ++nextEventSequence,
            timestampNanos = clock.nowNanos,
            frameId = clock.frameId.takeIf { it > 0L },
            sessionId = sessionId,
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            listenerId = listenerId,
            token = token,
            snapshotSequence = snapshotSequence,
            type = type,
            fraction = fraction,
            callbackDisposition = callbackDisposition,
            mutatesUi = mutatesUi,
            visibleRootCount = visibleRootCount,
            activeResourceCount = activeResourceCount,
            detail = detail,
            playerId = playerId,
        )
        recordedEvents += event
        return event
    }

    fun recordFrame(
        sessionId: TraceSessionId,
        controllerId: TraceControllerId,
        token: TraceTransitionToken?,
        fraction: Float,
        playerBounds: TraceRect,
        lyricBounds: TraceRect?,
        progressBounds: TraceRect?,
        actionBounds: List<TraceRect>,
        safeBottomInset: Int,
        visibleRootCount: Int,
        activeResourceCount: Int,
        classLoaderId: String? = null,
        playerId: TraceControllerId? = null,
    ): PresentedFrame {
        require(fraction in 0f..1f) { "fraction must be in [0, 1]" }
        require(safeBottomInset >= 0) { "safe bottom inset must not be negative" }
        require(visibleRootCount >= 0) { "visible root count must not be negative" }
        require(activeResourceCount >= 0) { "active resource count must not be negative" }

        val frame = PresentedFrame(
            frameId = clock.nextFrame(),
            timestampNanos = clock.nowNanos,
            sessionId = sessionId,
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            token = token,
            fraction = fraction,
            playerBounds = playerBounds,
            lyricBounds = lyricBounds,
            progressBounds = progressBounds,
            actionBounds = actionBounds.toList(),
            safeBottomInset = safeBottomInset,
            visibleRootCount = visibleRootCount,
            activeResourceCount = activeResourceCount,
            playerId = playerId,
        )
        recordedFrames += frame
        return frame
    }

    fun replay(visitor: (TraceEvent) -> Unit) {
        events.forEach(visitor)
    }
}
