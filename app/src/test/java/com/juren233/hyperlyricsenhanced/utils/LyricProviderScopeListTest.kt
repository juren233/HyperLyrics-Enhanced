/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricProviderScopeListTest {
    @Test fun `scope list keeps package lines and skips blanks and comments`() {
        assertEquals(
            listOf("com.netease.cloudmusic", "com.hihonor.cloudmusic"),
            LyricProviderManager.parseScopeList("com.netease.cloudmusic\n\n  # comment\r\ncom.hihonor.cloudmusic  \n"),
        )
    }

    @Test fun `app bundled provider targets its own package`() {
        assertEquals(
            listOf("com.md3music.md3music"),
            LyricProviderManager.providerTargets("com.md3music.md3music", isXposedModule = false, declaredScope = emptyList()),
        )
    }

    @Test fun `xposed provider module targets its declared scope only`() {
        assertEquals(
            listOf("com.maxmpz.audioplayer"),
            LyricProviderManager.providerTargets(
                "io.github.proify.lyricon.poweramp",
                isXposedModule = true,
                declaredScope = listOf("com.maxmpz.audioplayer"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            LyricProviderManager.providerTargets("io.github.proify.lyricon.car", isXposedModule = true, declaredScope = emptyList()),
        )
    }
}
