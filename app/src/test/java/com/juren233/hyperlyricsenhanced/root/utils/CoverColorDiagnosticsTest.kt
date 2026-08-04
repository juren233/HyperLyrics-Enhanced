package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverColorDiagnosticsTest {

    @Test
    fun `cover fallback after media key change is classified explicitly`() {
        assertEquals(
            "cover_to_default_after_key_change",
            CoverColorDiagnostics.classifyTransition(
                previousUsesDefault = false,
                usesDefault = true,
                previousMediaKey = "old",
                mediaKey = "new"
            )
        )
    }

    @Test
    fun `cover fallback on the same media key is classified explicitly`() {
        assertEquals(
            "cover_to_default_same_key",
            CoverColorDiagnostics.classifyTransition(
                previousUsesDefault = false,
                usesDefault = true,
                previousMediaKey = "same",
                mediaKey = "same"
            )
        )
    }

    @Test
    fun `default palette recovery is classified`() {
        assertEquals(
            "default_to_cover",
            CoverColorDiagnostics.classifyTransition(
                previousUsesDefault = true,
                usesDefault = false,
                previousMediaKey = "same",
                mediaKey = "same"
            )
        )
    }

    @Test
    fun `stable palette state does not emit a transition`() {
        assertNull(
            CoverColorDiagnostics.classifyTransition(
                previousUsesDefault = false,
                usesDefault = false,
                previousMediaKey = "same",
                mediaKey = "same"
            )
        )
    }

    @Test
    fun `missing media key can still expose a same-key fallback`() {
        assertEquals(
            "cover_to_default_same_key",
            CoverColorDiagnostics.classifyTransition(
                previousUsesDefault = false,
                usesDefault = true,
                previousMediaKey = null,
                mediaKey = null
            )
        )
    }
}
