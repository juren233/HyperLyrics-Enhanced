package com.juren233.hyperlyricsenhanced.online.source.qm

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class QmApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes current QQ item song numeric fields`() {
        val response = json.decodeFromString<QmBaseWrapper<QmSearchData>>(
            """
            {
              "req_0": {
                "code": 0,
                "data": {
                  "body": {
                    "item_song": [{
                      "id": 653802655,
                      "mid": "001jVyUl0Xublc",
                      "title": "満ちてゆく",
                      "singer": [{"name": "藤井风"}],
                      "album": {"name": "Pre: Prema", "mid": "001NrIj81X3DU7"},
                      "interval": 315,
                      "index_album": 6,
                      "genre": 0,
                      "time_public": "2026-04-03"
                    }]
                  }
                }
              }
            }
            """.trimIndent()
        )

        val song = response.req0.data?.body?.songs?.single()
        assertEquals(653802655L, song?.id)
        assertEquals(6, song?.trackerNumber)
        assertEquals("満ちてゆく", song?.title)
    }
}
