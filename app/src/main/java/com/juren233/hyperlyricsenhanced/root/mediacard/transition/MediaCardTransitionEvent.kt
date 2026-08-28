/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.transition

import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCardFullAodTransitionMode
import com.juren233.hyperlyricsenhanced.root.mediacard.host.NativeHeightLease

/** A stable identity for one concrete SystemUI media-card controller/player pair. */
internal data class MediaCardControllerIdentity(
    val controllerIdentity: Int,
    val playerIdentity: Int,
) {
    companion object {
        fun of(controller: Any?, player: Any): MediaCardControllerIdentity =
            MediaCardControllerIdentity(
                controllerIdentity = controller?.let(System::identityHashCode) ?: 0,
                playerIdentity = System.identityHashCode(player),
            )
    }
}

internal data class MediaCardTransitionToken(
    val sessionId: Long,
    val epoch: Long,
    val listenerIdentity: Int,
    val identity: MediaCardControllerIdentity,
    val targetFullAod: Boolean,
    val snapshotSequence: Long,
)

internal enum class MediaCardSessionState {
    DETACHED,
    STABLE_NOTIFICATION,
    STABLE_FULL_AOD,
    TRANSITIONING_TO_FULL_AOD,
    TRANSITIONING_TO_NOTIFICATION,
    RECOVERING,
}

internal enum class MediaCardTransitionCallback {
    BEGIN,
    UPDATE,
    COMPLETE,
    CANCEL,
    DETACH,
    RECOVER,
}

internal data class MediaCardTransitionResult(
    val accepted: Boolean,
    val state: MediaCardSessionState,
    val token: MediaCardTransitionToken?,
    val callback: MediaCardTransitionCallback,
    val releaseHeightLease: Boolean = false,
    val reason: String? = null,
)

/**
 * Session-scoped transition authority. It deliberately has no global target state:
 * every callback must carry the token produced by the same player session.
 */
internal class MediaCardTransitionCoordinator(
    val identity: MediaCardControllerIdentity,
) {
    private var nextSessionId = 0L
    private var epoch = 0L
    private var activeToken: MediaCardTransitionToken? = null
    private var lastFraction = 0f
    private var lease: NativeHeightLease? = null

    var state: MediaCardSessionState = MediaCardSessionState.DETACHED
        private set

    var frozenSnapshotSequence: Long = 0L
        private set

    fun attach(snapshotSequence: Long): MediaCardTransitionResult {
        frozenSnapshotSequence = snapshotSequence
        if (state == MediaCardSessionState.DETACHED) {
            state = MediaCardSessionState.STABLE_NOTIFICATION
        }
        return result(true, MediaCardTransitionCallback.RECOVER)
    }

    fun attachHeightLease(value: NativeHeightLease?) {
        lease?.close()
        lease = value
    }

    fun begin(
        listener: Any?,
        targetFullAod: Boolean,
        mode: MediaCardFullAodTransitionMode,
        snapshotSequence: Long,
    ): MediaCardTransitionResult {
        val listenerIdentity = listener?.let(System::identityHashCode) ?: identity.playerIdentity
        epoch += 1L
        val token = MediaCardTransitionToken(
            sessionId = ++nextSessionId,
            epoch = epoch,
            listenerIdentity = listenerIdentity,
            identity = identity,
            targetFullAod = targetFullAod,
            snapshotSequence = snapshotSequence,
        )
        activeToken = token
        frozenSnapshotSequence = snapshotSequence
        lastFraction = 0f
        state = if (targetFullAod) {
            MediaCardSessionState.TRANSITIONING_TO_FULL_AOD
        } else {
            MediaCardSessionState.TRANSITIONING_TO_NOTIFICATION
        }
        return result(true, MediaCardTransitionCallback.BEGIN)
            .copy(token = token)
    }

    fun update(token: MediaCardTransitionToken?, fraction: Float): MediaCardTransitionResult {
        val check = validate(token, MediaCardTransitionCallback.UPDATE)
        if (!check.accepted) return check
        if (!fraction.isFinite() || fraction !in 0f..1f) {
            return result(false, MediaCardTransitionCallback.UPDATE, "fraction_out_of_range")
        }
        // Native callbacks are the only clock. A late frame from the same token
        // is rejected instead of rewinding the already rendered native frame.
        if (fraction < lastFraction) {
            return result(false, MediaCardTransitionCallback.UPDATE, "fraction_rewound")
        }
        lastFraction = fraction
        return result(true, MediaCardTransitionCallback.UPDATE)
    }

    fun complete(token: MediaCardTransitionToken?): MediaCardTransitionResult {
        val check = validate(token, MediaCardTransitionCallback.COMPLETE)
        if (!check.accepted) return check
        val completedToken = requireNotNull(activeToken)
        val target = completedToken.targetFullAod
        state = if (target) {
            MediaCardSessionState.STABLE_FULL_AOD
        } else {
            MediaCardSessionState.STABLE_NOTIFICATION
        }
        activeToken = null
        lastFraction = 1f
        val released = releaseLease()
        return result(true, MediaCardTransitionCallback.COMPLETE).copy(
            token = completedToken,
            releaseHeightLease = released,
        )
    }

    fun cancel(token: MediaCardTransitionToken?): MediaCardTransitionResult {
        val check = validate(token, MediaCardTransitionCallback.CANCEL)
        if (!check.accepted) return check
        val cancelledToken = requireNotNull(activeToken)
        state = if (cancelledToken.targetFullAod) {
            MediaCardSessionState.STABLE_NOTIFICATION
        } else {
            MediaCardSessionState.STABLE_FULL_AOD
        }
        activeToken = null
        lastFraction = 0f
        val released = releaseLease()
        return result(true, MediaCardTransitionCallback.CANCEL).copy(
            token = cancelledToken,
            releaseHeightLease = released,
        )
    }

    fun detach(): MediaCardTransitionResult {
        activeToken = null
        epoch += 1L
        lastFraction = 0f
        val released = releaseLease()
        state = MediaCardSessionState.DETACHED
        return result(true, MediaCardTransitionCallback.DETACH).copy(
            releaseHeightLease = released,
        )
    }

    fun recover(snapshotSequence: Long, stableFullAod: Boolean): MediaCardTransitionResult {
        activeToken = null
        epoch += 1L
        lastFraction = if (stableFullAod) 1f else 0f
        frozenSnapshotSequence = snapshotSequence
        val released = releaseLease()
        state = if (stableFullAod) {
            MediaCardSessionState.STABLE_FULL_AOD
        } else {
            MediaCardSessionState.STABLE_NOTIFICATION
        }
        return result(true, MediaCardTransitionCallback.RECOVER).copy(
            releaseHeightLease = released,
        )
    }

    fun activeToken(): MediaCardTransitionToken? = activeToken

    fun lastFraction(): Float = lastFraction

    private fun validate(
        token: MediaCardTransitionToken?,
        callback: MediaCardTransitionCallback,
    ): MediaCardTransitionResult {
        val active = activeToken
        if (active == null) {
            return result(false, callback, "no_active_transition")
        }
        if (token == null) {
            return result(false, callback, "token_missing")
        }
        if (token != active) {
            return result(false, callback, "stale_token")
        }
        if (token.identity != identity) {
            return result(false, callback, "identity_mismatch")
        }
        if (token.epoch != epoch) {
            return result(false, callback, "epoch_mismatch")
        }
        return result(true, callback)
    }

    private fun releaseLease(): Boolean {
        val value = lease ?: return false
        lease = null
        return runCatching { value.close() }.isSuccess
    }

    private fun result(
        accepted: Boolean,
        callback: MediaCardTransitionCallback,
        reason: String? = null,
    ): MediaCardTransitionResult = MediaCardTransitionResult(
        accepted = accepted,
        state = state,
        token = activeToken,
        callback = callback,
        reason = reason,
    )
}
