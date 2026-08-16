/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMissingLyricsSourceMetadataTest {
    @Test
    fun `source metadata round trips selected source and statuses`() {
        val expected = listOf(
            AppleMissingLyricsSourceStatus("NE", true, true, true, 68),
            AppleMissingLyricsSourceStatus("QM", true, false, false, 0),
        )
        val decoded = AppleMissingLyricsSourceMetadata.decode(
            selectedSource = "NE",
            encodedStatuses = AppleMissingLyricsSourceMetadata.encodeStatuses(expected),
        )
        assertEquals(AppleMissingLyricsSourceInfo("NE", expected), decoded)
    }

    @Test
    fun `empty source metadata decodes as null`() {
        assertNull(AppleMissingLyricsSourceMetadata.decode(null, null))
    }

    @Test
    fun `merge statuses keeps previous results and overwrites incoming sources`() {
        val merged = AppleMissingLyricsSourceMetadata.mergeStatuses(
            previous = listOf(
                AppleMissingLyricsSourceStatus("NE", true, true, true, 60),
                AppleMissingLyricsSourceStatus("QM", true, false, false, 0),
            ),
            incoming = listOf(
                AppleMissingLyricsSourceStatus("KUGOU", true, true, true, 59),
                AppleMissingLyricsSourceStatus("QM", true, true, false, 44),
            ),
        )

        assertEquals(
            listOf(
                AppleMissingLyricsSourceStatus("NE", true, true, true, 60),
                AppleMissingLyricsSourceStatus("QM", true, true, false, 44),
                AppleMissingLyricsSourceStatus("KUGOU", true, true, true, 59),
            ),
            merged,
        )
    }
}
