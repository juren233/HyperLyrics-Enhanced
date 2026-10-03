/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.navigation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HyperIslandRouteCompatibilityTest {
    @Test fun `saved touch page from previous builds survives the terminology correction`() {
        // Verified with javap against the original 220006 Route$SuperIslandTouchSettings serializer.
        val saved = """{"type":"com.juren233.hyperlyricsenhanced.ui.navigation.Route.SuperIslandTouchSettings"}"""
        val restored = Json.decodeFromString<Route>(saved)
        assertSame(Route.HyperIslandTouchSettings, restored)
        assertEquals(saved, Json.encodeToString<Route>(Route.HyperIslandTouchSettings))
    }

    @Test fun `saved settings page from previous builds survives the terminology correction`() {
        // Verified with javap against the original 220007 compiled serializer.
        val saved = """{"type":"com.juren233.hyperlyricsenhanced.ui.navigation.Route.SuperIslandSettings"}"""
        val restored = Json.decodeFromString<Route>(saved)
        assertSame(Route.HyperIslandSettings, restored)
        assertEquals(saved, Json.encodeToString<Route>(Route.HyperIslandSettings))
    }

    @Test fun `saved album cover whitelist from previous builds survives the terminology correction`() {
        // Verified with javap against the original 220007 compiled serializer.
        val saved = """{"type":"com.juren233.hyperlyricsenhanced.ui.navigation.Route.SuperIslandAlbumCoverWhitelist"}"""
        val restored = Json.decodeFromString<Route>(saved)
        assertSame(Route.HyperIslandAlbumCoverWhitelist, restored)
        assertEquals(saved, Json.encodeToString<Route>(Route.HyperIslandAlbumCoverWhitelist))
    }
}
