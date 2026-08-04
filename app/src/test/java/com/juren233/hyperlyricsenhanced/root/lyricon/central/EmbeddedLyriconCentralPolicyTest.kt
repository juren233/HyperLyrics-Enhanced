/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.lyricon.central

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLyriconCentralPolicyTest {

    @Test
    fun `starts immediately when no standalone central package is installed`() {
        assertTrue(EmbeddedLyriconCentralPolicy.shouldStartImmediately(emptySet()))
    }

    @Test
    fun `waits for standalone core package`() {
        assertFalse(
            EmbeddedLyriconCentralPolicy.shouldStartImmediately(
                setOf("io.github.proify.lyricon.core"),
            ),
        )
    }

    @Test
    fun `waits for full Lyricon application package`() {
        assertFalse(
            EmbeddedLyriconCentralPolicy.shouldStartImmediately(
                setOf("io.github.proify.lyricon.app"),
            ),
        )
    }

    @Test
    fun `ignores unrelated installed packages`() {
        assertTrue(
            EmbeddedLyriconCentralPolicy.shouldStartImmediately(
                setOf("com.example.music", "com.example.provider"),
            ),
        )
    }
}
