/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyriconPublicationRevokeTest {
    @Test
    fun `revoking the supplement drops the catalog selection of that song only`() {
        val publication = LyriconPublication()
        publication.acceptAppleInput(Song(id = "1645308331"), hasNativeLyrics = false)
        publication.confirmLyricsSource(ConfirmedLyricsSourceSelection("1645308331", "AMLL"))

        publication.revokeLyricsSelection("other")
        assertEquals("AMLL", publication.confirmedLyricsSourceSelection?.source)

        publication.revokeLyricsSelection("1645308331")
        assertNull(publication.confirmedLyricsSourceSelection)
    }
}
