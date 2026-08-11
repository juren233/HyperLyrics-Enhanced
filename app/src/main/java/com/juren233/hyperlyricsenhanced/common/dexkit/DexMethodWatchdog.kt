/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.dexkit

import java.util.concurrent.ConcurrentHashMap

internal enum class DexResolutionSource {
    CACHE,
    PREFERRED_TARGET,
    DEXKIT,
}

internal data class DexWatchdogEvent(
    val cacheKey: String,
    val runtimeCacheKey: String,
    val stage: String,
    val result: String,
    val source: DexResolutionSource?,
    val cacheWritten: Boolean,
    val hookInstalled: Boolean,
    val callbackCount: Long,
    val validationCount: Long,
    val validObserved: Boolean,
    val target: String?,
    val detail: String?,
)

/**
 * Thread-safe, bounded state machine for debug-only DexKit diagnostics.
 *
 * It deliberately never invalidates a cache. A callback can legitimately carry
 * no lyric or no next item, so only the caller can decide whether business data
 * was valid and report that fact through validation().
 */
internal class DexMethodWatchdog(
    private val emit: (DexWatchdogEvent) -> Unit,
) {
    private data class State(
        val cacheKey: String,
        var runtimeCacheKey: String,
        var source: DexResolutionSource? = null,
        var cacheWritten: Boolean = false,
        var hookInstalled: Boolean = false,
        var callbackCount: Long = 0L,
        var validationCount: Long = 0L,
        var firstProbeRecorded: Boolean = false,
        var validObserved: Boolean = false,
        var firstInvalidRecorded: Boolean = false,
        var timeoutRecorded: Boolean = false,
        var target: String? = null,
    )

    private val states = ConcurrentHashMap<String, State>()

    fun register(cacheKey: String, runtimeCacheKey: String) {
        states.compute(cacheKey) { _, current ->
            current?.apply { this.runtimeCacheKey = runtimeCacheKey }
                ?: State(cacheKey = cacheKey, runtimeCacheKey = runtimeCacheKey)
        }
    }

    fun resolved(
        cacheKey: String,
        source: DexResolutionSource,
        cacheWritten: Boolean,
        target: String,
    ) = update(cacheKey) { state ->
        state.source = source
        state.cacheWritten = cacheWritten
        state.target = target
        state.timeoutRecorded = false
        state.event(stage = "resolved", result = "success")
    }

    fun hookInstalled(cacheKey: String, target: String) = update(cacheKey) { state ->
        state.hookInstalled = true
        state.target = target
        state.event(stage = "hookInstalled", result = "success")
    }

    fun callback(cacheKey: String) = update(cacheKey) { state ->
        state.callbackCount += 1L
        if (state.callbackCount == 1L) {
            state.event(stage = "firstCallback", result = "observed")
        } else {
            null
        }
    }

    fun validation(cacheKey: String, valid: Boolean, detail: String?) {
        val state = states[cacheKey] ?: return
        val events = synchronized(state) {
            state.validationCount += 1L
            val firstProbe = !state.firstProbeRecorded
            state.firstProbeRecorded = true
            val firstValid = valid && !state.validObserved
            if (valid) state.validObserved = true
            val firstInvalid = !valid && !state.firstInvalidRecorded
            if (!valid) state.firstInvalidRecorded = true
            buildList {
                if (firstProbe) {
                    add(
                        state.event(
                            stage = "firstProbe",
                            result = if (valid) "valid" else "invalid",
                            detail = detail,
                        ),
                    )
                }
                if (firstValid) {
                    add(state.event(stage = "firstValid", result = "valid", detail = detail))
                } else if (firstInvalid && !firstProbe) {
                    add(state.event(stage = "firstInvalid", result = "invalid", detail = detail))
                }
            }
        }
        events.forEach(emit)
    }

    fun timeout(cacheKey: String) = update(cacheKey) { state ->
        if (state.timeoutRecorded || state.firstProbeRecorded || state.validObserved) {
            return@update null
        }
        state.timeoutRecorded = true
        state.event(
            stage = "timeout",
            result = if (state.callbackCount == 0L) {
                "not_exercised"
            } else {
                "unverified_after_activity"
            },
        )
    }

    internal fun snapshot(cacheKey: String): DexWatchdogEvent? =
        states[cacheKey]?.let { state ->
            synchronized(state) { state.event(stage = "snapshot", result = "current") }
        }

    private fun update(
        cacheKey: String,
        block: (State) -> DexWatchdogEvent?,
    ) {
        val state = states[cacheKey] ?: return
        val event = synchronized(state) { block(state) }
        event?.let(emit)
    }

    private fun State.event(
        stage: String,
        result: String,
        detail: String? = null,
    ) = DexWatchdogEvent(
        cacheKey = cacheKey,
        runtimeCacheKey = runtimeCacheKey,
        stage = stage,
        result = result,
        source = source,
        cacheWritten = cacheWritten,
        hookInstalled = hookInstalled,
        callbackCount = callbackCount,
        validationCount = validationCount,
        validObserved = validObserved,
        target = target,
        detail = detail,
    )
}
