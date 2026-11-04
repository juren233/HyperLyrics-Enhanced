/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.utils

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TargetCrashRecorderTest {
    private data class Entry(val priority: Int, val tag: String?, val message: String, val error: Throwable?)

    private val entries = mutableListOf<Entry>()
    private var previousModule: XposedModule? = null
    private val context = TargetCrashRecorder.CrashContext("com.apple.android.music", "com.apple.android.music")

    @Before
    fun attachFramework() {
        TargetCrashRecorder.resetForTest()
        previousModule = HookLogger.module
        val framework = Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
        ) { _, method, args ->
            when (method.name) {
                "log" -> {
                    val values = requireNotNull(args)
                    entries += Entry(
                        values[0] as Int, values[1] as String?, values[2] as String,
                        values.getOrNull(3) as Throwable?,
                    )
                    null
                }
                "getRemotePreferences" -> null
                else -> error("Unexpected framework call: ${method.name}")
            }
        } as XposedInterface
        HookLogger.module = object : XposedModule() {}.apply { attachFramework(framework) {} }
    }

    @After
    fun restore() {
        HookLogger.module = previousModule
        TargetCrashRecorder.resetForTest()
    }

    @Test
    fun `crash is written to the persisted module log with its stack`() {
        val crash = IllegalStateException("startup crash")

        TargetCrashRecorder.record(context, Thread.currentThread(), crash)

        val entry = entries.single()
        assertEquals(Log.ERROR, entry.priority)
        assertNull(entry.tag)
        assertTrue(entry.message.startsWith("[CrashRecorder] 目标进程未捕获异常，即将崩溃: package=com.apple.android.music"))
        assertSame(crash, entry.error)
    }

    @Test
    fun `only the first crash of a process is recorded`() {
        TargetCrashRecorder.record(context, Thread.currentThread(), IllegalStateException("first"))
        TargetCrashRecorder.record(context, Thread.currentThread(), IllegalStateException("cascade"))

        assertEquals("first", entries.single().error?.message)
    }

    @Test
    fun `installed handler records the crash then hands it to the previous handler`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val delegated = mutableListOf<Throwable>()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error -> delegated += error }
            TargetCrashRecorder.install("com.apple.android.music", "com.apple.android.music")
            TargetCrashRecorder.install("com.apple.android.music", "com.apple.android.music")

            val crash = IllegalStateException("startup crash")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

            assertSame(crash, entries.single().error)
            // 崩溃仍交回原处理器，且重复 install 不会叠出第二层包装。
            assertEquals(listOf<Throwable>(crash), delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `description identifies process and thread`() {
        val thread = Thread({}, "main")
        assertEquals(
            "目标进程未捕获异常，即将崩溃: package=com.apple.android.music " +
                "process=com.apple.android.music pid=26269 thread=main",
            TargetCrashRecorder.describe(context, thread, 26269),
        )
        assertTrue(TargetCrashRecorder.describe(context, null, 1).endsWith("thread=unknown"))
    }
}
