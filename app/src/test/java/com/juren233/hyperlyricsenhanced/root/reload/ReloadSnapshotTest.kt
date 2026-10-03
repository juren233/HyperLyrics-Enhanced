/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import org.junit.Assert.*
import org.junit.Test

class ReloadSnapshotTest {
    @Test fun `legacy boolean state and unknown versions cannot opt into full restoration`() {
        assertNull(ReloadSnapshot.read(null))
        assertNull(ReloadSnapshot.read(mapOf("runtimeReady" to true)))
        assertNull(ReloadSnapshot.read(mapOf("hleSystemUiReloadVersion" to 200)))
    }

    @Test fun `envelope preserves host identity without carrying a module wrapper`() {
        val host = Any()
        val state = ReloadSnapshot.create().apply {
            put("islands", arrayOf(arrayOf(host, "music.package")))
        }
        val restored = ReloadSnapshot.read(state)!!
        assertSame(host, ReloadSnapshot.rows(restored, "islands").single()[0])
        assertEquals("music.package", ReloadSnapshot.rows(restored, "islands").single()[1])
        assertEquals(java.util.HashMap::class.java, state.javaClass)
        assertTrue(ReloadSnapshot.items(restored, "missing").isEmpty())
    }
}
