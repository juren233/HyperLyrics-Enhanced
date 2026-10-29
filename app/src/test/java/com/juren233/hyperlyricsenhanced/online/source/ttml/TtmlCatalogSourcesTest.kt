/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.ttml

import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlCatalogSourcesTest {
    @Test
    fun `every catalog source is registered and nothing else is`() {
        Source.entries.forEach { source ->
            val registered = TtmlCatalogSources.of(source)
            if (source.isTtmlCatalog) {
                assertEquals(source, registered?.source)
            } else {
                assertNull(registered)
            }
        }
    }

    @Test
    fun `spotify media id is unwrapped to the bare track id`() {
        assertEquals(CatalogPlatform.SPOTIFY, CatalogPlatform.ofPlayerPackage("com.spotify.music"))
        assertEquals(
            "3JBRqy1L1tegajBj9OUHbB",
            CatalogPlatform.songIdOf(CatalogPlatform.SPOTIFY, "spotify:track:3JBRqy1L1tegajBj9OUHbB"),
        )
        assertNull(CatalogPlatform.songIdOf(CatalogPlatform.SPOTIFY, "spotify:album:1sztqv3sxcpjmpHaISF8jx"))
        assertNull(CatalogPlatform.songIdOf(CatalogPlatform.SPOTIFY, "spotify:track:"))
        assertNull(CatalogPlatform.songIdOf(CatalogPlatform.SPOTIFY, null))
        assertEquals("7168586", CatalogPlatform.songIdOf(CatalogPlatform.QQ_MUSIC, " 7168586 "))
        assertNull(CatalogPlatform.songIdOf(CatalogPlatform.NETEASE, "  "))
    }

    @Test
    fun `catalog names are recognised from persisted strings`() {
        assertTrue(Source.isTtmlCatalogName("LB"))
        assertTrue(Source.isTtmlCatalogName("AMLL"))
        assertEquals(false, Source.isTtmlCatalogName("NE"))
        assertEquals(false, Source.isTtmlCatalogName(null))
        assertEquals(false, Source.isTtmlCatalogName("unknown"))
    }

    @Test
    fun `catalog sources lead the default order and stay in saved custom orders`() {
        assertEquals(listOf(Source.AMLL, Source.LB), OnlineTranslationSourcePreferences.defaultOrder.take(2))
        val saved = OnlineTranslationSourcePreferences.normalizeOrder("QM,NE,KUWO,KUGOU")
        assertEquals(listOf(Source.QM, Source.NE, Source.KUWO, Source.KUGOU, Source.AMLL, Source.LB), saved)
    }

    @Test
    fun `catalog sources are enabled by default and use their own keys`() {
        assertTrue(OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.AMLL))
        assertTrue(OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.LB))
        assertEquals("key_hook_online_source_amll", OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.AMLL))
        assertEquals("key_hook_online_source_lunabeat", OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.LB))
        assertTrue(OnlineTranslationSourcePreferences.isSourcePreference("key_hook_online_source_amll"))
    }
}
