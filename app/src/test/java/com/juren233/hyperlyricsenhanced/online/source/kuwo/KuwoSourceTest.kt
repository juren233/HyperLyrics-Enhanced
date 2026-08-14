/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KuwoSourceTest {
    @Test
    fun `uses auxiliary LRCX lines as translation then romanization`() {
        val raw = """
            [00:01.000]<0,2000>Hello
            [00:01.000]<0,0>你好
            [00:01.000]<0,0>ni hao
            [00:03.000]<0,2000>World
            [00:03.000]<0,0>世界
        """.trimIndent()

        val result = KuwoLyricsParser.toLyricsResult(raw)

        assertEquals(listOf("Hello", "World"), result?.original?.map(::lineText))
        assertEquals(listOf("你好", "世界"), result?.translated?.map(::lineText))
        assertEquals(listOf("ni hao"), result?.romanization?.map(::lineText))
    }

    @Test
    fun `pairs same timestamp plain line with the following original line`() {
        val raw = """
            [00:01.00]First
            [00:02.00]第一句
            [00:02.00]Second
        """.trimIndent()

        val result = KuwoLyricsParser.toLyricsResult(raw)

        assertEquals(listOf("First", "Second"), result?.original?.map(::lineText))
        assertEquals(listOf("第一句"), result?.translated?.map(::lineText))
    }

    @Test
    fun `rejects response decoder input without Kuwo content envelope`() {
        assertNull(KuwoResponseDecoder.decode("not-kuwo-content".toByteArray()))
        assertTrue(KuwoResponseDecoder.buildRequestQuery(123456L).isNotBlank())
    }

    private fun lineText(line: com.juren233.hyperlyricsenhanced.online.model.LyricsLine): String =
        line.words.joinToString("") { it.text }
}
