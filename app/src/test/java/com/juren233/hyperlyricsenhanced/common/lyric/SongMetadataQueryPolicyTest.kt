/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Test

class SongMetadataQueryPolicyTest {
    @Test
    fun `single artist plain title is queried once`() {
        assertEquals(listOf("Hello" to "Adele"), SongMetadataQueryPolicy.metadataQueries("Hello", "Adele"))
    }

    @Test
    fun `multi artist credits are retried one artist at a time`() {
        // 2026-11-09 实测：GetPlayLyricInfo 只认单个歌手
        assertEquals(
            listOf(
                "勿听" to "张紫宁/赵紫骅/游戏科学/8082Audio",
                "勿听" to "张紫宁",
                "勿听" to "赵紫骅",
                "勿听" to "游戏科学",
                "勿听" to "8082Audio",
            ),
            SongMetadataQueryPolicy.metadataQueries("勿听", "张紫宁/赵紫骅/游戏科学/8082Audio"),
        )
    }

    @Test
    fun `bracketed title is retried without the annotation after the original`() {
        assertEquals(
            listOf("Question...? (Explicit)" to "Taylor Swift", "Question...?" to "Taylor Swift"),
            SongMetadataQueryPolicy.metadataQueries("Question...? (Explicit)", "Taylor Swift"),
        )
        assertEquals(
            listOf(
                "勿听 (Live)" to "张紫宁/赵紫骅",
                "勿听 (Live)" to "张紫宁",
                "勿听 (Live)" to "赵紫骅",
                "勿听" to "张紫宁/赵紫骅",
                "勿听" to "张紫宁",
                "勿听" to "赵紫骅",
            ),
            SongMetadataQueryPolicy.metadataQueries("勿听 (Live)", "张紫宁/赵紫骅"),
        )
    }

    @Test
    fun `artist separators`() {
        assertEquals(
            listOf("A", "B", "C", "D", "E", "F", "G"),
            SongMetadataQueryPolicy.splitArtists("A&B,C，D、E/F／G"),
        )
    }
}
