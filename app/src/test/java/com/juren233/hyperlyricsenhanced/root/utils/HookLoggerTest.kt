/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.utils

import android.util.Log
import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.LogLevelPolicy
import com.juren233.hyperlyricsenhanced.utils.LOG_EXPORT_LEVEL_DEBUG
import com.juren233.hyperlyricsenhanced.utils.LogExportStream
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.StringReader
import java.io.StringWriter
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HookLoggerTest {
    private data class Entry(val priority: Int, val tag: String?, val message: String, val error: Throwable?)

    private val entries = mutableListOf<Entry>()
    private var previousModule: XposedModule? = null
    private var backendFailure: Throwable? = null
    private var preferenceFailure: Throwable? = null
    private var preferences: SharedPreferences? = null

    @Before
    fun attachFramework() {
        previousModule = HookLogger.module
        val framework = Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
        ) { _, method, args ->
            when (method.name) {
                "log" -> {
                    backendFailure?.let { throw it }
                    val values = requireNotNull(args)
                    entries += Entry(
                        values[0] as Int, values[1] as String?, values[2] as String,
                        values.getOrNull(3) as Throwable?,
                    )
                    null
                }
                "getRemotePreferences" -> {
                    preferenceFailure?.let { throw it }
                    preferences
                }
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

    private fun logAllLevels() {
        HookLogger.d("Lifecycle", "debug")
        HookLogger.i("Lifecycle", "info")
        HookLogger.w("Lifecycle", "warning", IllegalStateException("optional diagnostic"))
        HookLogger.e("Lifecycle", "error", IllegalStateException("optional diagnostic"))
    }

    @Test
    fun `logging backend failure cannot escape any severity`() {
        backendFailure = IllegalStateException("framework logger detached")
        logAllLevels()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `preference lookup failure falls back to build default`() {
        preferenceFailure = IllegalStateException("preferences unavailable")
        logAllLevels()
        val expected = if (LogLevelPolicy.defaultLevel(BuildConfig.DEBUG) > 0) 4 else 3
        assertEquals(expected, entries.size)
    }

    @Test
    fun `preference value failure cannot escape debug gating`() {
        for (failingMethod in listOf("contains", "getInt", "getString")) {
            preferences = Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, _ ->
                if (method.name == failingMethod) error("preference read failed")
                when (method.name) {
                    "contains" -> true
                    "getInt" -> LogLevelPolicy.LEVEL_DEBUG
                    "getString" -> LogLevelPolicy.buildKind(BuildConfig.DEBUG)
                    else -> error("Unexpected preferences call: ${method.name}")
                }
            } as SharedPreferences
            // Reset the logger's cached preference handle between failure boundaries.
            HookLogger.module = HookLogger.module
            entries.clear()
            logAllLevels()
            val expected = if (LogLevelPolicy.defaultLevel(BuildConfig.DEBUG) > 0) 4 else 3
            assertEquals("failure at $failingMethod", expected, entries.size)
        }
    }

    @Test
    fun `all severities use the framework module tag and preserve the throwable`() {
        val failure = IllegalStateException("registration unavailable")
        HookLogger.d("Lifecycle", "installed")
        HookLogger.i("Lifecycle", "callback")
        HookLogger.w("Connection", "unavailable", failure)
        HookLogger.e("Connection", "failed", failure)

        assertEquals(listOf(Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR), entries.map { it.priority })
        entries.forEach { assertNull(it.tag) }
        assertEquals("[Lifecycle] callback", entries[1].message)
        assertSame(failure, entries[2].error)
        assertSame(failure, entries[3].error)
    }

    @Test
    fun `Vector module stream survives app export with lifecycle identity and failure details`() {
        HookLogger.i("LyricRuntime", "stage=application_on_create_hit pid=13343 uid=10225")
        HookLogger.e("HookEntry", "系统环境初始化失败", IllegalStateException("init failed"))

        // Vector 2.2 (88f8e1fa) chooses VectorContext for a null tag and persists only
        // its fixed module tags. Model that boundary, then run the actual HLE exporter.
        val moduleTags = setOf("VectorContext", "VectorLegacyBridge", "VectorModuleManager", "XSharedPreferences")
        val persisted = buildString {
            entries.forEachIndexed { index, entry ->
                val tag = entry.tag ?: "VectorContext"
                if (tag !in moduleTags) return@forEachIndexed
                val level = if (entry.priority == Log.ERROR) "E" else "I"
                appendLine("[ 2026-10-03T17:53:3$index.050 10225: 13343: 13343 $level/$tag ] " +
                    "com.juren233.hyperlyricsenhanced: ${entry.message}")
                entry.error?.let { appendLine(it.stackTraceToString()) }
            }
        }
        val directory = Files.createTempDirectory("vector-log-export").toFile()
        try {
            val exported = StringWriter()
            val count = LogExportStream.copyXposedLogs(
                StringReader(persisted).buffered(), LOG_EXPORT_LEVEL_DEBUG, directory, exported,
            )
            assertEquals(2, count)
            assertTrue(exported.toString().contains("stage=application_on_create_hit pid=13343 uid=10225"))
            assertTrue(exported.toString().contains("IllegalStateException: init failed"))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
