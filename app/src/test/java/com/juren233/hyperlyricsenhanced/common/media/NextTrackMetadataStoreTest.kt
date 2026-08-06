/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTrackMetadataStoreTest {
    private var now = 1_000L
    private val store = NextTrackMetadataStore(nowMs = { now }, ttlMs = 5_000L)

    @Test
    fun `matches official metadata by current media id`() {
        assertTrue(
            store.update(
                playerPackageName = "com.tencent.qqmusic",
                currentId = "1",
                currentTitle = "Current",
                currentArtist = "Artist",
                metadata = NextTrackMetadata("2", "Next", "Next Artist", "Album", 10_000L),
            )
        )

        assertEquals(
            "Next",
            store.find("com.tencent.qqmusic", "1", "Different title", "Different artist")?.title,
        )
        assertNull(store.find("com.tencent.qqmusic", "9", "Other", "Artist"))
    }

    @Test
    fun `falls back to normalized title and artist identity`() {
        store.update(
            playerPackageName = "com.netease.cloudmusic",
            currentId = "",
            currentTitle = "  Current   Song ",
            currentArtist = "Artist",
            metadata = NextTrackMetadata("2", "Next", "Singer", "", -1L),
        )

        assertEquals(
            "Next",
            store.find("com.netease.cloudmusic", null, "current song", "artist")?.title,
        )
    }

    @Test
    fun `keeps different current songs isolated and expires stale data`() {
        store.update(
            "com.tencent.qqmusic",
            "1",
            "First",
            "Artist",
            NextTrackMetadata("2", "Second", "Artist", "", -1L),
        )
        store.update(
            "com.tencent.qqmusic",
            "2",
            "Second",
            "Artist",
            NextTrackMetadata("3", "Third", "Artist", "", -1L),
        )

        assertEquals("Second", store.find("com.tencent.qqmusic", "1", "", "")?.title)
        assertEquals("Third", store.find("com.tencent.qqmusic", "2", "", "")?.title)
        now += 5_001L
        assertNull(store.find("com.tencent.qqmusic", "2", "Second", "Artist"))
    }

    @Test
    fun `clear only removes the matching current identity`() {
        store.update(
            "com.tencent.qqmusic",
            "1",
            "First",
            "Artist",
            NextTrackMetadata("2", "Second", "Artist", "", -1L),
        )
        store.update(
            "com.tencent.qqmusic",
            "2",
            "Second",
            "Artist",
            NextTrackMetadata("3", "Third", "Artist", "", -1L),
        )

        store.clear("com.tencent.qqmusic", "1", "First", "Artist")

        assertNull(store.find("com.tencent.qqmusic", "1", "First", "Artist"))
        assertEquals("Third", store.find("com.tencent.qqmusic", "2", "Second", "Artist")?.title)
    }
}
