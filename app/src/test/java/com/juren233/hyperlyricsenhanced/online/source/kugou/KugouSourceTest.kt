/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.kugou

import java.util.Base64
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouSourceTest {
    private val searchParameters = mapOf(
        "album_audio_id" to "0",
        "appid" to "1005",
        "clientver" to "20759",
        "duration" to "269000",
        "hash" to "",
        "keyword" to "周杰伦 - 晴天",
        "lrctxt" to "1",
        "man" to "yes",
        "query_copyright" to "1",
    )

    @Test
    fun `matches the signature used by the provider Kugou v2 protocol`() {
        assertEquals(
            "05bc38d0cc855ae66995137e7e62900a",
            KugouApiProtocol.signature(searchParameters),
        )
        val query = KugouApiProtocol.signedQuery(searchParameters)
        assertEquals(
            query.split('&').map { it.substringBefore('=') }.sorted(),
            query.split('&').map { it.substringBefore('=') },
        )
        assertTrue(query.contains("duration=269000"))
        assertTrue(query.endsWith("signature=05bc38d0cc855ae66995137e7e62900a"))
    }

    @Test
    fun `parses KRC type one translation with matching line count`() {
        val raw = krc(
            """
            [language:${languagePayload(listOf(listOf("第一", "句"), listOf("第二句")))}]
            [0,1000]<0,1000,0>First
            [1000,1000]<0,1000,0>Second
            """.trimIndent(),
        )

        val result = KugouLyricsParser.parse(raw, durationMs = 2_000L)

        assertEquals(listOf("First", "Second"), result?.original?.map(::lineText))
        assertEquals(listOf("第一句", "第二句"), result?.translated?.map(::lineText))
    }

    @Test
    fun `rejects KRC translation whose line count differs from original lyrics`() {
        val raw = krc(
            """
            [language:${languagePayload(listOf(listOf("只有一句")))}]
            [0,1000]<0,1000,0>First
            [1000,1000]<0,1000,0>Second
            """.trimIndent(),
        )

        assertNull(KugouLyricsParser.parse(raw, durationMs = 2_000L)?.translated)
    }

    private fun languagePayload(lines: List<List<String>>): String {
        val lyricContent = lines.joinToString(",") { chunks ->
            chunks.joinToString(prefix = "[", postfix = "]", separator = ",") { chunk ->
                "\"$chunk\""
            }
        }
        val json = """{"version":1,"content":[{"type":1,"lyricContent":[$lyricContent]}]}"""
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    private fun krc(content: String): ByteArray {
        val compressed = java.io.ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(content.toByteArray()) }
        }.toByteArray()
        val key = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
            206.toByte(), 210.toByte(), 110, 105,
        )
        val encrypted = ByteArray(compressed.size) { index ->
            (compressed[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
        return "krc1".toByteArray() + encrypted
    }

    private fun lineText(line: com.juren233.hyperlyricsenhanced.online.model.LyricsLine): String =
        line.words.joinToString("") { it.text }
}
