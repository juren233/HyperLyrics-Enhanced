/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils

import com.juren233.hyperlyricsenhanced.common.LogLevelPolicy.LEVEL_DEBUG
import com.juren233.hyperlyricsenhanced.common.LogLevelPolicy.LEVEL_NORMAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogExportGuidePolicyTest {
    private fun prompt(source: LogExportSource, debugBuild: Boolean, level: Int) =
        LogExportGuidePolicy.promptFor(source, debugBuild, level)

    @Test
    fun `release build always asks to install the debug build from both entries`() {
        for (source in LogExportSource.entries) {
            for (level in listOf(LEVEL_NORMAL, LEVEL_DEBUG)) {
                assertEquals(LogExportPrompt.RELEASE_BUILD, prompt(source, debugBuild = false, level))
            }
        }
    }

    @Test
    fun `debug build with normal level asks to enable debug logging from both entries`() {
        for (source in LogExportSource.entries) {
            assertEquals(LogExportPrompt.ENABLE_DEBUG_LEVEL, prompt(source, debugBuild = true, LEVEL_NORMAL))
        }
    }

    @Test
    fun `debug build with debug level points the log page to export all and lets export all through`() {
        assertEquals(
            LogExportPrompt.USE_EXPORT_ALL,
            prompt(LogExportSource.LOG_PAGE, debugBuild = true, LEVEL_DEBUG),
        )
        assertNull(prompt(LogExportSource.EXPORT_ALL, debugBuild = true, LEVEL_DEBUG))
    }
}
