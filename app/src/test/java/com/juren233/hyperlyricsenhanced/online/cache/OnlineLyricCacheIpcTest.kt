/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnlineLyricCacheIpcTest {

    @Test
    fun `a large snapshot round-trips through the shared memory wire form`() {
        val songs = (0 until 2_000).map { i ->
            OnlineLyricCacheSongSummary(
                song = OnlineLyricCacheSong(
                    tag = "song:com.example.player\u0000一首歌名比较长的歌曲 $i\u0000歌手甲/歌手乙\u0000245000",
                    title = "一首歌名比较长的歌曲 $i",
                    artist = "歌手甲/歌手乙",
                    packageName = "com.example.player",
                    album = "专辑 $i",
                ),
                usedAtMs = 1_794_805_717_812L + i,
                sources = listOf(
                    OnlineLyricCacheSourceStats("QM", 1, 0, 12_345),
                    OnlineLyricCacheSourceStats("NE", 0, 1, 0),
                ),
            )
        }
        val snapshot = OnlineLyricCacheSnapshot(songs, totalBytes = 9_876_543L)

        val bytes = OnlineLyricCacheIpc.encodeSnapshot(snapshot)
        // As UTF-16 inline this would exceed what one oneway Binder call may carry (~508 KB).
        assertTrue(bytes.decodeToString().length * 2 > 512 * 1024)
        assertEquals(snapshot, OnlineLyricCacheIpc.decodeSnapshot(bytes))
    }

    @Test
    fun `a delete of many songs is split into batches that cover every tag once`() {
        val tags = (0 until 450).map { "tag$it" }.toSet()
        val selection = OnlineLyricCacheSelection(tags = tags, sources = setOf("QM"))

        val batches = OnlineLyricCacheIpc.deleteBatches(selection)

        assertEquals(3, batches.size)
        assertTrue(batches.all { it.tags!!.size <= OnlineLyricCacheIpc.DELETE_BATCH_TAGS })
        assertTrue(batches.all { it.sources == setOf("QM") })
        assertEquals(tags, batches.flatMap { it.tags!! }.toSet())
        assertEquals(tags.size, batches.sumOf { it.tags!!.size })
    }

    @Test
    fun `a delete without tags or with few tags is sent as is`() {
        val all = OnlineLyricCacheSelection(sources = setOf("AMLL"))
        val few = OnlineLyricCacheSelection(tags = setOf("a", "b"))

        assertEquals(listOf(all), OnlineLyricCacheIpc.deleteBatches(all))
        assertEquals(listOf(few), OnlineLyricCacheIpc.deleteBatches(few))
    }
}
