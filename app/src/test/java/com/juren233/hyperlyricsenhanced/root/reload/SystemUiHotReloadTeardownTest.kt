package com.juren233.hyperlyricsenhanced.root.reload

import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanupException
import org.junit.Assert.*
import org.junit.Test

class SystemUiHotReloadTeardownTest {
    @Test fun `runtime failure still retires UI Central and diagnostics before reporting failure`() {
        val attempted = mutableListOf<String>()
        val failure = runCatching {
            SystemUiHotReload.runTeardown(listOf(
                "runtime" to { attempted += "runtime"; error("receiver unregister failed") },
                "touch" to { attempted += "touch" },
                "renderers" to { attempted += "renderers" },
                "central" to { attempted += "central"; error("subscriber close failed") },
                "performance" to { attempted += "performance" },
            ))
        }.exceptionOrNull()
        assertEquals(listOf("runtime", "touch", "renderers", "central", "performance"), attempted)
        assertTrue(failure is RuntimeResourceCleanupException)
        assertEquals(1, failure!!.suppressed.size)
        assertTrue(failure.cause!!.message!!.contains("runtime"))
        assertTrue(failure.suppressed.single().message!!.contains("central"))
    }

    @Test fun `successful teardown finishes synchronously in declared order`() {
        val order = mutableListOf<Int>()
        SystemUiHotReload.runTeardown(listOf("first" to { order += 1 }, "last" to { order += 2 }))
        assertEquals(listOf(1, 2), order)
    }
}
