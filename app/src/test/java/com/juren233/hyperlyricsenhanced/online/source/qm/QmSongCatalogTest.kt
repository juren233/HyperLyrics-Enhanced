/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.qm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmSongCatalogTest {
    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `parses selected song and candidates in server order`() {
        // 2026-11-09 实测《Broken》林峯 190 秒
        val match = QmSongCatalog.parseMetadataMatch(json("""
            {"code":0,"req_0":{"code":0,"data":{"songID":605895210,
              "vecSongID":[4907722,107770907,0,4907722]}}}
        """))!!
        assertEquals(605895210L, match.songId)
        assertEquals(listOf(605895210L, 4907722L, 107770907L), match.orderedIds)
    }

    @Test
    fun `duration mismatch keeps candidates without a selected song`() {
        val match = QmSongCatalog.parseMetadataMatch(json(
            """{"code":0,"req_0":{"code":0,"data":{"songID":0,"vecSongID":[4907722]}}}""",
        ))!!
        assertNull(match.songId)
        assertEquals(listOf(4907722L), match.orderedIds)
    }

    @Test
    fun `no match and errors parse to null`() {
        assertNull(QmSongCatalog.parseMetadataMatch(json("""{"code":0,"req_0":{"code":24001,"data":{"songID":0}}}""")))
        assertNull(QmSongCatalog.parseMetadataMatch(json("""{"code":0,"req_0":{"code":0,"data":{"songID":0,"vecSongID":[]}}}""")))
        assertNull(QmSongCatalog.parseMetadataMatch(json("""{"code":0,"req_0":{"code":10000}}""")))
        assertNull(QmSongCatalog.parseMetadataMatch(json("""{"code":500001}""")))
    }

    @Test
    fun `parses batched details and drops missing ids`() {
        // 2026-11-09 实测合并请求：detail_3 为不存在的 ID（code 500）
        val details = QmSongCatalog.parseDetails(json("""
            {"code":0,
             "detail_0":{"code":0,"data":{"track_info":{"id":1926,"name":"那么爱你为什么",
               "singer":[{"name":"莫文蔚"},{"name":"黄品源"}],"interval":264,
               "album":{"name":"含情莫莫 全精选辑","mid":"m1"},"time_public":"2002-03-01","index_album":4}}},
             "detail_1":{"code":0,"data":{"track_info":{"id":605895210,"name":"Broken",
               "singer":[{"name":"林峯"}],"interval":190,"album":{"name":"","mid":""},"index_album":-31073}}},
             "detail_2":{"code":500,"data":{"track_info":{"id":0,"name":""}}}}
        """), 3)
        assertEquals(setOf(1926L, 605895210L), details.keys)
        val first = details.getValue(1926L)
        assertEquals("那么爱你为什么", first.name)
        assertEquals(listOf("莫文蔚", "黄品源"), first.singers)
        assertEquals(264_000L, first.durationMs)
        assertEquals("含情莫莫 全精选辑", first.album)
        assertEquals(190_000L, details.getValue(605895210L).durationMs)
    }

    @Test
    fun `gateway error yields no details`() {
        assertTrue(QmSongCatalog.parseDetails(json("""{"code":500001}"""), 2).isEmpty())
    }
}
