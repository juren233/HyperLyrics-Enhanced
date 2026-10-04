package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.*
import org.junit.Test

class LyricRuntimeDiagnosticFormatTest {
    @Test fun `markers carry repair revision generation monotonic time and full UID`() {
        val prefix = LyricRuntimeDiagnosticFormat.prefix("ready", 10, 1_010_225, "reload-2", 9_000)
        assertTrue(prefix.contains("repair=issue44-recovery-20261004-r2"))
        assertTrue(prefix.contains("generation=reload-2"))
        assertTrue(prefix.contains("elapsedMs=9000"))
        assertTrue(prefix.contains("pid=10 uid=1010225"))
    }
}
