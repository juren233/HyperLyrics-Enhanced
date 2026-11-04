/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OfficialProviderModuleLogTest {
    private data class Entry(val priority: Int, val tag: String?, val message: String, val error: Throwable?)

    private val entries = mutableListOf<Entry>()

    private val module: XposedModule = run {
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
                else -> error("Unexpected framework call: ${method.name}")
            }
        } as XposedInterface
        object : XposedModule() {}.apply { attachFramework(framework) {} }
    }

    @Test
    fun `provider logs use the framework tag so Vector persists them`() {
        val failure = IllegalStateException("boom")

        module.persistentLog(Log.INFO, "OfficialProviderRuntime", "开始加载官方 Provider: id=applemusic")
        module.persistentLog(Log.ERROR, " OfficialProviderHookHost ", " 加载失败 ", failure)

        assertEquals(2, entries.size)
        // Vector 2.2 只落盘框架自己的 tag：必须传 null，原 tag 并进消息前缀。
        entries.forEach { assertNull(it.tag) }
        assertEquals("[OfficialProviderRuntime] 开始加载官方 Provider: id=applemusic", entries[0].message)
        assertEquals(Log.INFO, entries[0].priority)
        assertNull(entries[0].error)
        assertEquals("[OfficialProviderHookHost] 加载失败", entries[1].message)
        assertSame(failure, entries[1].error)
    }

    @Test
    fun `previous abnormal exit description names reason and signal`() {
        assertEquals(
            "reason=crash_native status=11 pid=26269 timestamp=1793802283948 description=none",
            ProviderProcessExit.describeExit(
                reason = android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                status = 11,
                description = "  ",
                timestamp = 1793802283948,
                pid = 26269,
            ),
        )
        assertEquals(true, ProviderProcessExit.isAbnormalReason(android.app.ApplicationExitInfo.REASON_ANR))
        assertEquals(false, ProviderProcessExit.isAbnormalReason(android.app.ApplicationExitInfo.REASON_USER_REQUESTED))
    }
}
