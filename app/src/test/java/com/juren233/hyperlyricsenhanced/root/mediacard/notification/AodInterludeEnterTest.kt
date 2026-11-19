/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodInterludeEnterTest {

    private fun start(
        enabled: Boolean = true,
        wasInterlude: Boolean = false,
        nowInterlude: Boolean = true,
        hadMainText: Boolean = true,
        attached: Boolean = true,
    ) = AodInterludeEnter.shouldStart(enabled, wasInterlude, nowInterlude, hadMainText, attached)

    @Test
    fun `lyric line turning into interlude fades`() {
        assertTrue(start())
    }

    @Test
    fun `switch off never fades`() {
        assertFalse(start(enabled = false))
    }

    @Test
    fun `already interlude or not interlude does not fade`() {
        assertFalse(start(wasInterlude = true))
        assertFalse(start(nowInterlude = false))
    }

    @Test
    fun `first show and detached overlay do not fade`() {
        assertFalse(start(hadMainText = false))
        assertFalse(start(attached = false))
    }
}
