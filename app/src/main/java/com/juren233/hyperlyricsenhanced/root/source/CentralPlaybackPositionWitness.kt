/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

/**
 * Recovers a missed active-playback callback from the built-in Apple Central position ticker.
 *
 * PlayerBinder only runs that ticker while its recorder remains active (PLAYING or BUFFERING).
 * Requiring several closely spaced callbacks avoids treating one or two in-flight positions after
 * a real pause as playback resumption.
 */
internal class CentralPlaybackPositionWitness(
    private val requiredWitnesses: Int = 3,
    private val minimumWitnessSpanMs: Long = 40L,
    private val maximumWitnessGapMs: Long = 500L,
) {
    private var sinkPlaybackActive: Boolean? = null
    private var witnessCount = 0
    private var firstWitnessAtMs = Long.MIN_VALUE
    private var lastWitnessAtMs = Long.MIN_VALUE

    init {
        require(requiredWitnesses >= 2)
        require(minimumWitnessSpanMs >= 0L)
        require(maximumWitnessGapMs >= minimumWitnessSpanMs)
    }

    @Synchronized
    fun onSinkPlaybackState(isPlaying: Boolean) {
        sinkPlaybackActive = isPlaying
        clearWitnesses()
    }

    @Synchronized
    fun onSinkStopped() {
        sinkPlaybackActive = false
        clearWitnesses()
    }

    @Synchronized
    fun reset() {
        sinkPlaybackActive = null
        clearWitnesses()
    }

    @Synchronized
    fun observeActivePosition(observedAtMs: Long): Boolean {
        if (sinkPlaybackActive == true) {
            clearWitnesses()
            return false
        }

        val startsNewSequence = lastWitnessAtMs == Long.MIN_VALUE ||
            observedAtMs < lastWitnessAtMs ||
            observedAtMs - lastWitnessAtMs > maximumWitnessGapMs
        if (startsNewSequence) {
            witnessCount = 1
            firstWitnessAtMs = observedAtMs
        } else {
            witnessCount += 1
        }
        lastWitnessAtMs = observedAtMs

        val spanMs = observedAtMs - firstWitnessAtMs
        if (witnessCount < requiredWitnesses || spanMs < minimumWitnessSpanMs) return false

        sinkPlaybackActive = true
        clearWitnesses()
        return true
    }

    private fun clearWitnesses() {
        witnessCount = 0
        firstWitnessAtMs = Long.MIN_VALUE
        lastWitnessAtMs = Long.MIN_VALUE
    }
}
