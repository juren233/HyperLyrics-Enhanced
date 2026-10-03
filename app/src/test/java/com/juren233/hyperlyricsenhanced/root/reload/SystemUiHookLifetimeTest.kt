/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import com.juren233.hyperlyricsenhanced.root.HookEntry
import io.github.libxposed.api.XposedInterface.Chain
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class SystemUiHookLifetimeTest {
    @Test fun `retired lifecycle hook only continues native code without touching old runtime`() {
        val calls = mutableListOf<String>()
        val chain = Proxy.newProxyInstance(Chain::class.java.classLoader, arrayOf(Chain::class.java)) { _, method, _ ->
            calls += method.name
            check(method.name == "proceed") { "Retired hook tried to inspect or initialize its old host" }
            "native result"
        } as Chain
        val previous = SystemUiHookLifetime.retired
        try {
            SystemUiHookLifetime.retired = true
            assertEquals("native result", HookEntry.AppCreateHooker().intercept(chain))
            assertEquals(listOf("proceed"), calls)
        } finally {
            SystemUiHookLifetime.retired = previous
        }
    }
}
