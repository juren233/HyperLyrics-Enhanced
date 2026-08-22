/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.lunabeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LunaBeatTtmlRepositoryPolicyTest {
    @Test
    fun `cached hit does not force a catalog refresh`() {
        var refreshCalls = 0

        val result = resolveLunaBeatCatalogEntry(
            initialEntry = "cached",
            forceRefreshRequested = false,
            networkChecked = false,
        ) {
            refreshCalls += 1
            "refreshed"
        }

        assertEquals("cached", result)
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `cached miss refreshes once and can find a newly indexed song`() {
        var refreshCalls = 0

        val result = resolveLunaBeatCatalogEntry<String>(
            initialEntry = null,
            forceRefreshRequested = false,
            networkChecked = false,
        ) {
            refreshCalls += 1
            "new-entry"
        }

        assertEquals("new-entry", result)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `miss after a network check does not issue a second request in the same lookup`() {
        var refreshCalls = 0

        val result = resolveLunaBeatCatalogEntry<String>(
            initialEntry = null,
            forceRefreshRequested = false,
            networkChecked = true,
        ) {
            refreshCalls += 1
            "unexpected"
        }

        assertNull(result)
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `explicit force refresh does not recursively refresh after a miss`() {
        var refreshCalls = 0

        val result = resolveLunaBeatCatalogEntry<String>(
            initialEntry = null,
            forceRefreshRequested = true,
            networkChecked = true,
        ) {
            refreshCalls += 1
            "unexpected"
        }

        assertNull(result)
        assertEquals(0, refreshCalls)
    }
}
