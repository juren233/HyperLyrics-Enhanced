/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.transition

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardRound6ContractTest {
    @Test
    fun `controller identity is never synthesized from a null controller`() {
        val controller = Any()
        val player = Any()
        val identity = MediaCardControllerIdentity.of(controller, player)
        assertTrue(identity.controllerIdentity != 0)
        assertTrue(identity.playerIdentity != 0)
        assertNotEquals(identity.controllerIdentity, 0)
    }

    @Test
    fun `B controller does not resolve SystemUI identifiers directly`() {
        val source = sourceFile("MediaCardLyricOverlayController.kt").readText()
        assertFalse(source.contains("loadClass("))
        assertFalse(source.contains("getField("))
        assertFalse(source.contains("javaClass.name.endsWith"))
        assertTrue(source.contains("MediaCardHostBinding"))
        assertTrue(source.contains("verifiedNativeTargetHeight"))
    }

    @Test
    fun `transition mutation APIs require a non-null token at the source boundary`() {
        val source = sourceFile("MediaCardLyricOverlayController.kt").readText()
        assertFalse(source.contains("transitionToken: MediaCardTransitionToken? = null,"))
        assertFalse(source.contains("suppliedToken ?:"))
        assertTrue(source.contains("fun completeNotificationFullAodTransition("))
        assertTrue(source.contains("fun cancelNotificationFullAodTransition("))
    }

    @Test
    fun `deprecated global finish is inert rather than cross-session`() {
        val source = sourceFile("MediaCardLyricOverlayController.kt").readText()
        val start = source.indexOf("fun finishNotificationFullAodTransition")
        assertTrue(start >= 0)
        val end = source.indexOf("fun completeNotificationCardToAodTransition", start)
        assertTrue(end > start)
        val block = source.substring(start, end)
        assertFalse(block.contains("states.values.toList()"))
        assertTrue(block.contains("scoped_token_missing"))
    }

    private fun sourceFile(name: String): File = listOf(
        File("app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/$name"),
        File("src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/$name"),
        File("../HyperLyrics-Enhanced/app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/$name"),
    ).firstOrNull(File::exists) ?: error("source not found: $name")
}
