/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online

import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineTranslationSourcePreferencesTest {
    @Test
    fun `default order puts NetEase first`() {
        assertEquals(
            listOf(Source.NE, Source.QM, Source.KUWO, Source.KUGOU),
            OnlineTranslationSourcePreferences.defaultOrder,
        )
    }

    @Test
    fun `automatic best source selection is enabled by default`() {
        assertEquals(
            true,
            OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(null),
        )
    }

    @Test
    fun `automatic selection ignores stored priority order`() {
        assertEquals(
            OnlineTranslationSourcePreferences.defaultOrder,
            OnlineTranslationSourcePreferences.resolveOrder(
                rawOrder = "KUGOU,KUWO,QM,NE",
                automaticSelection = true,
            ),
        )
    }

    @Test
    fun `manual selection restores stored priority order`() {
        assertEquals(
            listOf(Source.KUGOU, Source.KUWO, Source.QM, Source.NE),
            OnlineTranslationSourcePreferences.resolveOrder(
                rawOrder = "KUGOU,KUWO,QM,NE",
                automaticSelection = false,
            ),
        )
    }

    @Test
    fun `NetEase and QQ are enabled by default while added sources are opt in`() {
        assertEquals(true, OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.NE))
        assertEquals(true, OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.QM))
        assertEquals(false, OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.KUWO))
        assertEquals(false, OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.KUGOU))
    }

    @Test
    fun `target apps keep explicit opt in defaults`() {
        assertEquals(
            false,
            OnlineTranslationSourcePreferences.appDefaultEnabled(
                OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE,
            ),
        )
        assertEquals(
            false,
            OnlineTranslationSourcePreferences.appDefaultEnabled(
                OnlineTranslationSourcePreferences.QISHUI_PACKAGE,
            ),
        )
        assertEquals(
            false,
            OnlineTranslationSourcePreferences.appDefaultEnabled(
                OnlineTranslationSourcePreferences.SPOTIFY_PACKAGE,
            ),
        )
    }

    @Test
    fun `stored order is deduplicated and missing sources are appended`() {
        assertEquals(
            listOf(Source.KUGOU, Source.NE, Source.QM, Source.KUWO),
            OnlineTranslationSourcePreferences.normalizeOrder("KUGOU,NE,KUGOU"),
        )
    }

    @Test
    fun `unknown source values do not corrupt the configured order`() {
        assertEquals(
            listOf(Source.QM, Source.NE, Source.KUWO, Source.KUGOU),
            OnlineTranslationSourcePreferences.normalizeOrder("unknown,QM"),
        )
    }
}
