package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AiTranslationHealthLogTest {
    private val written = mutableListOf<String>()
    private fun success() = AiTranslationHealthLog.onSuccess { written += it }

    @Before
    fun reset() = AiTranslationHealthLog.resetForTest()

    @Test
    fun firstSuccess_loggedOnce_notOnEveryRequest() {
        repeat(5) { success() }
        assertEquals(listOf("翻译请求成功"), written)
    }

    @Test
    fun successAfterFailure_loggedAgain() {
        success()
        AiTranslationHealthLog.onFailure()
        AiTranslationHealthLog.onFailure()
        success()
        success()
        assertEquals(listOf("翻译请求成功", "翻译请求成功"), written)
    }

    @Test
    fun failureBeforeAnySuccess_firstSuccessStillLoggedOnce() {
        AiTranslationHealthLog.onFailure()
        success()
        success()
        assertEquals(listOf("翻译请求成功"), written)
    }
}
