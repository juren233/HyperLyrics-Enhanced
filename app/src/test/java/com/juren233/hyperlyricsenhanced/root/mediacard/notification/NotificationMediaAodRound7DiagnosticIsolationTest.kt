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

class NotificationMediaAodRound7DiagnosticIsolationTest {
    private fun sourceText(relative: String): String {
        val workingDirectory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file = listOf(
            File(workingDirectory, relative),
            File("/home/cavan/项目/HyperLyrics-Enhanced-agent-c-round7", relative),
        ).firstOrNull { it.isFile }
            ?: error("source is unavailable: $relative")
        return file.readText()
    }

    @Test
    fun `debug transition audit is guarded and release does not install a trace pipeline`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAodLyricHooker.kt",
        )
        assertTrue(source.contains("private fun debugNativeEvent("))
        assertTrue(source.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(source.contains("event=\$event"))
        assertTrue(source.contains("classLoaderIdentity"))
        assertTrue(source.contains("session=\${token?.sessionId"))
        assertTrue(source.contains("activeNativeContexts"))
        assertTrue(source.contains("leases="))
    }

    @Test
    fun `environment diagnostics remains debug only`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/AodEnvironmentDiagnostics.kt",
        )
        assertTrue(source.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(source.contains("AOD_ENV"))
    }
    @Test
    fun `native height index and owner mapping remain fail closed without evidence`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAodLyricHooker.kt",
        )
        assertTrue(source.contains("nativeHeightIndex = null"))
        assertTrue(source.contains("nativeHeightIndex=unverified"))
        assertTrue(source.contains("lease=skipped"))
        assertTrue(source.contains("native_owner_to_player_mapping_unavailable"))
        assertTrue(source.contains("native_owner_to_player_mapping_ambiguous"))
        assertFalse(source.contains("binding.acquireHeightLease("))
    }

    @Test
    fun `classic AOD remains separate from the media card session`() {
        val source = sourceText(
            "app/src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/notification/NotificationMediaAodLyricHooker.kt",
        )
        assertTrue(source.contains("AOD_PLUGIN_VIEW_CLASS"))
        assertTrue(source.contains("AOD_CLASSIC"))
        assertTrue(source.contains("independent AODView path"))
        assertTrue(source.contains("Classic AOD"))
    }

}
