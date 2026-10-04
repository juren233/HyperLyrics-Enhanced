/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.util

import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.amprovider.xposed.model.LyricAgent
import io.github.proify.lyricon.amprovider.xposed.model.LyricLine
import io.github.proify.lyricon.amprovider.xposed.model.LyricWord
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppleSongMapperDiagnosticsTest {
    private val entries = mutableListOf<String>()
    private var previousModule: XposedModule? = null

    @Before
    fun captureFrameworkLogs() {
        previousModule = HookLogger.module
        val framework = Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
        ) { _, method, args ->
            when (method.name) {
                "log" -> {
                    entries += requireNotNull(args)[2] as String
                    null
                }
                "getRemotePreferences" -> null
                else -> error("Unexpected framework call: ${method.name}")
            }
        } as XposedInterface
        HookLogger.module = object : XposedModule() {}.apply {
            attachFramework(framework) {}
        }
    }

    @After
    fun restoreFramework() {
        HookLogger.module = previousModule
    }

    @Test
    fun `caps diagnostic samples and omits lyric and agent payloads`() {
        val privateLyric = "private lyric payload"
        val privateAgent = "private agent identifier"
        val song = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = privateAgent, type = 1, typeName = "private type name"),
            ),
            lyrics = MutableList(100) {
                LyricLine(agent = privateAgent, htmlLineText = privateLyric)
            },
        )

        val mapped = AppleSongMapper.map(song)

        assertEquals(100, mapped.lyrics.orEmpty().size)
        assertEquals(privateLyric, mapped.lyrics.orEmpty().last().text)
        assertEquals(7, entries.size)
        assertTrue(entries.first().contains("sampleCount=6, omittedLines=94"))
        assertTrue(entries.drop(1).all { it.contains("textLength=${privateLyric.length}") })
        assertFalse(entries.joinToString().contains(privateLyric))
        assertFalse(entries.joinToString().contains(privateAgent))
        assertFalse(entries.joinToString().contains("private type name"))
    }

    @Test
    fun `reports fallback word length without leaking word text`() {
        AppleSongMapper.map(
            AppleSong(lyrics = mutableListOf(
                LyricLine(words = mutableListOf(LyricWord(text = "hidden"), LyricWord(text = "words"))),
            )),
        )

        assertEquals(2, entries.size)
        assertTrue(entries.last().contains("textLength=11, wordCount=2"))
        assertFalse(entries.joinToString().contains("hidden"))
        assertFalse(entries.joinToString().contains("hiddenwords"))
    }

    @Test
    fun `empty lyrics produce only a summary`() {
        AppleSongMapper.map(AppleSong())

        assertEquals(1, entries.size)
        assertTrue(entries.single().contains("sampleCount=0, omittedLines=0"))
    }
}
