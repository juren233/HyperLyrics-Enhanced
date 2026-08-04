package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineTranslationContentPolicyTest {
    @Test
    fun `drops slash only missing translation placeholders`() {
        listOf(
            null,
            "",
            "   ",
            "//",
            " // ",
            "// //",
            "///",
            "//\n //",
            "//\u00a0//",
        ).forEach { value ->
            assertNull(value, OnlineTranslationContentPolicy.sanitize(value))
        }
    }

    @Test
    fun `keeps slash characters when translation contains real text`() {
        assertEquals(
            "AC/DC // Live",
            OnlineTranslationContentPolicy.sanitize("  AC/DC // Live  ")
        )
        assertEquals(
            "访问 https://example.com",
            OnlineTranslationContentPolicy.sanitize("访问 https://example.com")
        )
    }
}
