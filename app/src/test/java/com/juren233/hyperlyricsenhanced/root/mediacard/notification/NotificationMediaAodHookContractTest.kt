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
        val workingDirectory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val candidates = listOf(
            File(workingDirectory, relative),
            File("/home/cavan/项目/HyperLyrics-Enhanced-agent-c-round7", relative),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("source is unavailable for contract audit: $relative")
        return file.readText()
    }

    @Test
    fun `native terminal events use scoped complete and cancel and no legacy finish`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAodLyricHooker.kt",
        )
        assertTrue(source.contains("MediaCardLyricOverlayController.completeNotificationFullAodTransition("))
        assertTrue(source.contains("MediaCardLyricOverlayController.cancelNotificationFullAodTransition("))
        assertTrue(source.contains("transitionToken = token"))
        assertTrue(source.contains("detail = \"complete\""))
        assertTrue(source.contains("detail = \"cancel\""))
        assertFalse(source.contains("finishNotificationFullAodTransition"))
        assertFalse(source.contains("completeNotificationCardToAodTransition"))
        assertFalse(source.contains("complete_blocked"))
        assertFalse(source.contains("onCancel -> finish"))
    }

    @Test
    fun `strict host binding is the only notification media bind and begin path`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAodLyricHooker.kt",
        )
        assertTrue(source.contains("bindStrictNotificationMediaCard"))
        assertTrue(source.contains("SystemUiMediaHostAdapter.forBuild"))
        assertTrue(source.contains("adapter.capability"))
        assertTrue(source.contains("MediaCardLyricOverlayController.createHostBinding"))
        assertTrue(source.contains("controller = controller"))
        assertTrue(source.contains("listener = listener"))
        assertTrue(source.contains("nativeHeightIndex = null"))
        assertTrue(source.contains("nativeHeightIndex=unverified"))
        assertTrue(source.contains("native_owner_to_player_mapping_unavailable"))
        assertTrue(source.contains("native_owner_to_player_mapping_ambiguous"))
        assertFalse(source.contains("B_API_requires_scoped_complete"))
        assertFalse(source.contains("heightCommit=blocked_until_round6"))
    }

    @Test
    fun `reload and stale identity boundaries are explicit`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAodLyricHooker.kt",
        )
        assertTrue(source.contains("retireStaleSystemUiSessions"))
        assertTrue(source.contains("systemui_reload"))
        assertTrue(source.contains("controller_detach"))
        assertTrue(source.contains("player_rebound"))
        assertTrue(source.contains("foreignOwnerActive"))
        assertTrue(source.contains("another_native_owner_transition_active"))
        assertTrue(source.contains("target_changed_within_listener_context"))
    }

    @Test
    fun `screen monitor publishes desired state without direct root hiding`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/SystemUiScreenStateMonitor.kt",
        )
        assertTrue(source.contains("onScreenInteractiveChanged(true)"))
        assertTrue(source.contains("onScreenInteractiveChanged(false)"))
        assertFalse(source.contains("hideLockScreenOverlays()"))
    }

    @Test
    fun `ambient flow consumes strict helper instead of deprecated player-only bind`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAmbientFlowHooker.kt",
        )
        assertTrue(source.contains("NotificationMediaAodLyricHooker.bindStrictNotificationMediaCard("))
        assertTrue(source.contains("if (hostBinding == null)"))
        assertFalse(source.contains("MediaCardLyricOverlayController.bindNotificationCard("))
    }
}
