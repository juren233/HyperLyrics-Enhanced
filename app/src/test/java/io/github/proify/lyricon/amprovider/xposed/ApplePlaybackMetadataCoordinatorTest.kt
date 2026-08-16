/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplePlaybackMetadataCoordinatorTest {

    @Test
    fun `published current media id wins over a stale observed queue item`() {
        assertEquals(
            "635770202",
            selectCurrentPlaybackMediaId(
                publishedMediaId = "635770202",
                observedQueueMediaId = "1810905308",
            ),
        )
    }

    @Test
    fun `observed queue media id is used before the first publication`() {
        assertEquals(
            "635770202",
            selectCurrentPlaybackMediaId(
                publishedMediaId = null,
                observedQueueMediaId = "635770202",
            ),
        )
    }

    @Test
    fun `queue media id can supply adam id when lyrics item id is absent`() {
        assertEquals(
            635770202L,
            selectPlaybackAdamId(
                runtimeAdamId = null,
                itemMediaId = "635770202",
                expectedContentSongId = "635770202",
            ),
        )
    }
}
