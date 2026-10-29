/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionLyricsPlanTest {
    @Test
    fun `catalogs before the first search source lead and later ones trail`() {
        val plan = MediaSessionLyricsPlan.of(listOf(Source.AMLL, Source.NE, Source.LB, Source.QM))
        assertEquals(listOf(Source.AMLL), plan.leadingCatalogs)
        assertEquals(listOf(Source.NE, Source.QM), plan.searchSources)
        assertEquals(listOf(Source.LB), plan.trailingCatalogs)
    }

    @Test
    fun `plan without catalogs only searches`() {
        val plan = MediaSessionLyricsPlan.of(listOf(Source.NE, Source.QM))
        assertEquals(emptyList<Source>(), plan.leadingCatalogs)
        assertEquals(listOf(Source.NE, Source.QM), plan.searchSources)
        assertEquals(emptyList<Source>(), plan.trailingCatalogs)
    }

    @Test
    fun `only verified players map to a catalog platform`() {
        assertEquals(CatalogPlatform.NETEASE, CatalogPlatform.ofPlayerPackage("com.netease.cloudmusic"))
        assertEquals(CatalogPlatform.QQ_MUSIC, CatalogPlatform.ofPlayerPackage("com.tencent.qqmusic"))
        assertEquals(CatalogPlatform.APPLE_MUSIC, CatalogPlatform.ofPlayerPackage("com.apple.android.music"))
        assertEquals(CatalogPlatform.SPOTIFY, CatalogPlatform.ofPlayerPackage("com.spotify.music"))
        assertNull(CatalogPlatform.ofPlayerPackage("com.example.unverified"))
        assertNull(CatalogPlatform.ofPlayerPackage(null))
    }
}
