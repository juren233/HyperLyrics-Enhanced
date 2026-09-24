/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceClockUpdatePolicyTest {
    @Test
    fun `newer QQ HD system seek overrides stale source but normal offset does not`() {
        assertEquals(
            true,
            SourceClockUpdatePolicy.preferSystemAfterSeek(
                packageName = "com.tencent.qqmusicpad",
                sourcePosition = 1_240,
                sourceSampleAtMs = 1_074_837_251L,
                systemPosition = 129_346,
                systemStateUpdatedAtMs = 1_074_857_873L,
            ),
        )
        assertEquals(
            false,
            SourceClockUpdatePolicy.preferSystemAfterSeek(
                packageName = "com.tencent.qqmusicpad",
                sourcePosition = 129_000,
                sourceSampleAtMs = 1_074_837_251L,
                systemPosition = 129_346,
                systemStateUpdatedAtMs = 1_074_857_873L,
            ),
        )
        assertEquals(
            false,
            SourceClockUpdatePolicy.preferSystemAfterSeek(
                packageName = "com.apple.android.music",
                sourcePosition = 1_240,
                sourceSampleAtMs = 1_074_837_251L,
                systemPosition = 129_346,
                systemStateUpdatedAtMs = 1_074_857_873L,
            ),
        )
    }
    @Test
    fun `duplicate source samples do not reanchor the clock`() {
        assertEquals(
            SourceClockUpdatePolicy.Action.IGNORE_DUPLICATE,
            SourceClockUpdatePolicy.decide(
                explicitSeek = false,
                previousAnchorPosition = 10_000,
                projectedPosition = 10_016,
                incomingPosition = 10_000,
            )
        )
    }

    @Test
    fun `expired duplicate source sample does not restart a frozen clock`() {
        assertEquals(
            SourceClockUpdatePolicy.Action.IGNORE_DUPLICATE,
            SourceClockUpdatePolicy.decide(
                explicitSeek = false,
                previousAnchorPosition = 1_240,
                projectedPosition = null,
                incomingPosition = 1_240,
            ),
        )
    }

    @Test
    fun `small source backtrack is ignored`() {
        assertEquals(
            SourceClockUpdatePolicy.Action.IGNORE_BACKTRACK,
            SourceClockUpdatePolicy.decide(
                explicitSeek = false,
                previousAnchorPosition = 856,
                projectedPosition = 901,
                incomingPosition = 352,
            )
        )
    }

    @Test
    fun `large reset is treated as a seek`() {
        assertEquals(
            SourceClockUpdatePolicy.Action.SEEK,
            SourceClockUpdatePolicy.decide(
                explicitSeek = false,
                previousAnchorPosition = 254_485,
                projectedPosition = 254_580,
                incomingPosition = 77,
            )
        )
    }

    @Test
    fun `large forward jump is treated as a seek`() {
        assertEquals(
            SourceClockUpdatePolicy.Action.SEEK,
            SourceClockUpdatePolicy.decide(
                explicitSeek = false,
                previousAnchorPosition = 10_000,
                projectedPosition = 10_040,
                incomingPosition = 15_000,
            )
        )
    }

    @Test
    fun `normal forward sample updates the anchor`() {
        assertEquals(
            SourceClockUpdatePolicy.Action.ANCHOR,
            SourceClockUpdatePolicy.decide(
                explicitSeek = false,
                previousAnchorPosition = 10_000,
                projectedPosition = 10_016,
                incomingPosition = 10_041,
            )
        )
    }

    @Test
    fun `explicit seek always keeps seek semantics`() {
        assertEquals(
            SourceClockUpdatePolicy.Action.SEEK,
            SourceClockUpdatePolicy.decide(
                explicitSeek = true,
                previousAnchorPosition = 10_000,
                projectedPosition = 10_016,
                incomingPosition = 9_900,
            )
        )
    }
}
