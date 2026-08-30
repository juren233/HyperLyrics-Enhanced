/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.integration

/**
 * Deterministic SystemUI/native callback fixture. It models only the event and
 * identity contract; it never pretends to be a visual device test.
 */
class FakeSystemUiMediaHost(
    private val trace: DeterministicMediaCardTrace,
    val classLoaderId: String,
    val controllerId: TraceControllerId,
    val playerId: TraceControllerId,
    sessionId: TraceSessionId,
) {
    private var nextSessionId = sessionId.value
    private var listenerGeneration = 0L
    private var listenerId = TraceListenerId(controllerId.value * 10L + 1L)
    private var activeToken: TraceTransitionToken? = null
    private var epoch = 0L
    private var activeResourceCount = 0
    private var visibleRootCount = 1

    private val playerBounds = TraceRect(0, 0, 400, 520)
    private val lyricBounds = TraceRect(20, 100, 380, 190)
    private val progressBounds = TraceRect(20, 270, 380, 290)
    private val actionBounds = listOf(TraceRect(20, 320, 80, 380))

    fun bind(snapshotSequence: Long = 1L) {
        trace.record(
            sessionId = TraceSessionId(nextSessionId),
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            playerId = playerId,
            type = TraceEventType.MEDIA_BOUND,
            listenerId = listenerId,
            snapshotSequence = snapshotSequence,
            visibleRootCount = visibleRootCount,
            activeResourceCount = activeResourceCount,
        )
    }

    fun begin(targetFullAod: Boolean, snapshotSequence: Long = 1L): TraceTransitionToken {
        epoch += 1L
        val token = TraceTransitionToken(
            sessionId = TraceSessionId(nextSessionId),
            epoch = epoch,
            listenerId = listenerId,
            targetFullAod = targetFullAod,
        )
        activeToken = token
        activeResourceCount = 1
        trace.record(
            sessionId = token.sessionId,
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            playerId = playerId,
            type = TraceEventType.NATIVE_BEGIN,
            listenerId = listenerId,
            token = token,
            snapshotSequence = snapshotSequence,
            fraction = 0f,
            callbackDisposition = TraceCallbackDisposition.ACCEPTED,
            visibleRootCount = visibleRootCount,
            activeResourceCount = activeResourceCount,
            detail = "target=$targetFullAod",
        )
        return token
    }

    fun update(token: TraceTransitionToken, fraction: Float): Boolean {
        val accepted = token == activeToken && fraction in 0f..1f
        trace.record(
            sessionId = token.sessionId,
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            playerId = playerId,
            type = TraceEventType.NATIVE_UPDATE,
            listenerId = listenerId,
            token = token,
            fraction = fraction,
            callbackDisposition = if (accepted) {
                TraceCallbackDisposition.ACCEPTED
            } else {
                TraceCallbackDisposition.STALE_REJECTED
            },
            mutatesUi = accepted,
            visibleRootCount = visibleRootCount,
            activeResourceCount = activeResourceCount,
        )
        if (accepted) {
            trace.recordFrame(
                sessionId = token.sessionId,
                controllerId = controllerId,
                classLoaderId = classLoaderId,
                token = token,
                fraction = fraction,
                playerBounds = playerBounds,
                lyricBounds = lyricBounds,
                progressBounds = progressBounds,
                actionBounds = actionBounds,
                safeBottomInset = 20,
                visibleRootCount = visibleRootCount,
                activeResourceCount = activeResourceCount,
                playerId = playerId,
            )
        }
        return accepted
    }

    fun complete(token: TraceTransitionToken): Boolean {
        val accepted = token == activeToken
        trace.record(
            sessionId = token.sessionId,
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            playerId = playerId,
            type = TraceEventType.NATIVE_COMPLETE,
            listenerId = listenerId,
            token = token,
            callbackDisposition = if (accepted) {
                TraceCallbackDisposition.ACCEPTED
            } else {
                TraceCallbackDisposition.STALE_REJECTED
            },
            mutatesUi = accepted,
            visibleRootCount = visibleRootCount,
            activeResourceCount = 0,
        )
        if (accepted) {
            activeResourceCount = 0
            activeToken = null
        }
        return accepted
    }

    fun cancel(token: TraceTransitionToken): Boolean {
        val accepted = token == activeToken
        trace.record(
            sessionId = token.sessionId,
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            playerId = playerId,
            type = TraceEventType.NATIVE_CANCEL,
            listenerId = listenerId,
            token = token,
            callbackDisposition = if (accepted) {
                TraceCallbackDisposition.ACCEPTED
            } else {
                TraceCallbackDisposition.STALE_REJECTED
            },
            mutatesUi = accepted,
            visibleRootCount = visibleRootCount,
            activeResourceCount = 0,
        )
        if (accepted) {
            activeResourceCount = 0
            activeToken = null
        }
        return accepted
    }

    fun screenInteractiveChanged(interactive: Boolean) {
        trace.record(
            sessionId = TraceSessionId(nextSessionId),
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            playerId = playerId,
            type = TraceEventType.SCREEN_INTERACTIVE_CHANGED,
            listenerId = listenerId,
            token = activeToken,
            mutatesUi = false,
            visibleRootCount = visibleRootCount,
            activeResourceCount = activeResourceCount,
            detail = "desiredInteractive=$interactive",
        )
    }

    fun reload() {
        trace.record(
            sessionId = TraceSessionId(nextSessionId),
            controllerId = controllerId,
            classLoaderId = classLoaderId,
            playerId = playerId,
            type = TraceEventType.HOST_DETACHED,
            listenerId = listenerId,
            token = activeToken,
            mutatesUi = false,
            visibleRootCount = visibleRootCount,
            activeResourceCount = 0,
            detail = "systemui_reload",
        )
        listenerGeneration += 1L
        listenerId = TraceListenerId(controllerId.value * 10L + listenerGeneration + 1L)
        epoch += 1L
        activeToken = null
        activeResourceCount = 0
        nextSessionId += 1L
    }
}
