/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LogExportEnvironmentTest {
    @Test
    fun `installing package takes precedence as the install source`() {
        assertEquals(
            "installer",
            selectInstallSourcePackage(
                installingPackageName = "installer",
                initiatingPackageName = "initiator",
                originatingPackageName = "originator",
            ),
        )
    }

    @Test
    fun `install source falls back through initiating and originating packages`() {
        assertEquals(
            "initiator",
            selectInstallSourcePackage(null, "initiator", "originator"),
        )
        assertEquals(
            "originator",
            selectInstallSourcePackage(null, " ", "originator"),
        )
        assertEquals(null, selectInstallSourcePackage(null, null, null))
    }

    @Test
    fun `plugin version shows name with code and falls back to code`() {
        assertEquals("1.0.14 (15)", formatPluginVersion("1.0.14", 15))
        assertEquals("15", formatPluginVersion(null, 15))
        assertEquals("15", formatPluginVersion("  ", 15))
    }
}
