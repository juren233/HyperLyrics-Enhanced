/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveAudioPlaybackMonitorTest {
    private val currentPlayer = "com.netease.cloudmusic"

    @Test
    fun `current player output is not a conflict`() {
        assertEquals(
            false,
            resolve(
                activePackages = setOf(currentPlayer),
                hasRelevantStartedPlayback = true,
            ),
        )
    }

    @Test
    fun `another player output conflicts with stale current state`() {
        assertEquals(
            true,
            resolve(
                activePackages = setOf("com.ss.android.ugc.aweme"),
                hasRelevantStartedPlayback = true,
            ),
        )
    }

    @Test
    fun `no active media output is unknown and preserves Provider behavior`() {
        assertNull(
            resolve(
                activePackages = emptySet(),
                hasRelevantStartedPlayback = false,
            ),
        )
    }

    @Test
    fun `unresolved active uid is unknown and preserves Provider behavior`() {
        assertNull(
            resolve(
                activePackages = emptySet(),
                hasRelevantStartedPlayback = true,
                hasUnresolvedStartedPlayback = true,
            ),
        )
    }

    @Test
    fun `parallel current and other output does not suppress current Provider`() {
        assertEquals(
            false,
            resolve(
                activePackages = setOf(currentPlayer, "com.ss.android.ugc.aweme"),
                hasRelevantStartedPlayback = true,
            ),
        )
    }

    private fun resolve(
        activePackages: Set<String>,
        hasRelevantStartedPlayback: Boolean,
        hasUnresolvedStartedPlayback: Boolean = false,
    ) = resolveActiveAudioConflict(
        playerPackageName = currentPlayer,
        activePackages = activePackages,
        hasRelevantStartedPlayback = hasRelevantStartedPlayback,
        hasUnresolvedStartedPlayback = hasUnresolvedStartedPlayback,
    )
}
