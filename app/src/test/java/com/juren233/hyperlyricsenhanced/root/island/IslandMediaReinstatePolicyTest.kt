/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandMediaReinstatePolicyTest {
    @Test
    fun `no cached data or package mismatch never reinstates`() {
        assertFalse(
            IslandMediaReinstatePolicy.shouldReinstate(
                cachedDataAvailable = false,
                cachedPkgMatchesLyric = true,
                playbackActive = true,
                screenInteractive = true,
                msSinceLastAttempt = null,
            ),
        )
        assertFalse(
            IslandMediaReinstatePolicy.shouldReinstate(
                cachedDataAvailable = true,
                cachedPkgMatchesLyric = false,
                playbackActive = true,
                screenInteractive = true,
                msSinceLastAttempt = null,
            ),
        )
    }

    @Test
    fun `paused or screen off never reinstates`() {
        // 锁屏/息屏/暂停的原生收岛是正常行为，不得重放。
        assertFalse(
            IslandMediaReinstatePolicy.shouldReinstate(
                cachedDataAvailable = true,
                cachedPkgMatchesLyric = true,
                playbackActive = false,
                screenInteractive = true,
                msSinceLastAttempt = null,
            ),
        )
        assertFalse(
            IslandMediaReinstatePolicy.shouldReinstate(
                cachedDataAvailable = true,
                cachedPkgMatchesLyric = true,
                playbackActive = true,
                screenInteractive = false,
                msSinceLastAttempt = null,
            ),
        )
    }

    @Test
    fun `eligible window reinstates immediately on first attempt`() {
        assertTrue(
            IslandMediaReinstatePolicy.shouldReinstate(
                cachedDataAvailable = true,
                cachedPkgMatchesLyric = true,
                playbackActive = true,
                screenInteractive = true,
                msSinceLastAttempt = null,
            ),
        )
    }

    @Test
    fun `retry is throttled until interval elapses`() {
        fun eligible(msSinceLastAttempt: Long?): Boolean =
            IslandMediaReinstatePolicy.shouldReinstate(
                cachedDataAvailable = true,
                cachedPkgMatchesLyric = true,
                playbackActive = true,
                screenInteractive = true,
                msSinceLastAttempt = msSinceLastAttempt,
            )
        assertFalse(eligible(7_999L))
        assertTrue(eligible(8_000L))
        assertTrue(eligible(60_000L))
    }
}
