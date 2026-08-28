/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMediaAodHookContractTest {
    private fun sourceText(relative: String): String {
        val candidates = listOf(
            File("/home/cavan/项目/HyperLyrics-Enhanced-agent-c-round5/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(relative),
            File("../HyperLyrics-Enhanced/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("source is unavailable for contract audit: $relative")
    }

    @Test
    fun `cancel path never invokes global finish and always carries explicit token`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAodLyricHooker.kt",
        )
        assertFalse(source.contains("finishNotificationFullAodTransition"))
        assertTrue(source.contains("cancelNotificationFullAodTransition("))
        assertTrue(source.contains("transitionToken = token"))
        assertTrue(source.contains("SystemUiMediaHostAdapter.forBuild"))
        assertTrue(source.contains("binding.readTransitionFrame"))
    }

    @Test
    fun `screen monitor publishes desired state instead of hiding a root`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/SystemUiScreenStateMonitor.kt",
        )
        assertTrue(source.contains("onScreenInteractiveChanged(true)"))
        assertTrue(source.contains("onScreenInteractiveChanged(false)"))
        assertFalse(source.contains("hideLockScreenOverlays()"))
    }

    @Test
    fun `legacy metric height is not passed into the unified frame plan`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAodLyricHooker.kt",
        )
        assertTrue(source.contains("targetCardHeight = null"))
        assertTrue(source.contains("heightCommit=blocked_until_round6"))
        assertTrue(source.contains("B_API_requires_scoped_complete"))
    }
}
