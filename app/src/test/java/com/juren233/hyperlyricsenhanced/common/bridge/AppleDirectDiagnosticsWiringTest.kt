/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-wiring checks complement pure-policy tests; they do not simulate Android delivery. */
class AppleDirectDiagnosticsWiringTest {
    private val root = listOf(File("app/src/main/java"), File("src/main/java")).first(File::isDirectory)
    private fun source(path: String) = File(root, path).readText()
    private val bridge get() = source("com/juren233/hyperlyricsenhanced/root/source/AppleMusicDirectBridge.kt")
    private val player get() = source("io/github/proify/lyricon/amprovider/xposed/AppleDirectPlayer.kt")

    @Test fun `both endpoints log finite recovery context and final budget as awaiting callback`() {
        for (source in listOf(bridge, player)) {
            assertTrue(source.contains("LyricRuntimeDiagnostics.record(stage)"))
            assertTrue(source.contains("recoverySequence++"))
            assertTrue(source.contains("recoveryReason = reason"))
            assertTrue(source.contains("bridgeInstance=\$bridgeInstance recoverySequence=\$recoverySequence"))
            assertTrue(source.contains(".snapshot(SystemClock.elapsedRealtime()).diagnosticFields()"))
            assertTrue(source.contains("recoveryDiagnostic(\"direct_recovery_scheduled\")"))
            assertTrue(source.contains("recoveryDiagnostic(\"direct_recovery_attempt\", \"state=awaiting_callback\")"))
            assertTrue(source.contains("recoveryDiagnostic(\"direct_recovery_budget_consumed\", \"state=awaiting_callback\")"))
            assertTrue(source.contains("recoveryDiagnostic(\"direct_recovery_connected\", \"receiverRegistered=true\")"))
        }
    }

    @Test fun `identity send logging uses SDK data and leaves API34 calls inside the guarded branch`() {
        val adapter = source("com/juren233/hyperlyricsenhanced/common/bridge/AppleDirectBroadcastIdentity.kt")
        val send = adapter.substringAfter("internal fun Context.sendAppleDirectBroadcast(intent: Intent) {")
            .substringBefore("internal fun BroadcastReceiver.appleDirectSenderIdentity")
        assertTrue(send.contains("LyricRuntimeDiagnostics.record(\"direct_broadcast_sending\")"))
        assertTrue(send.contains("sdk=\${Build.VERSION.SDK_INT} shareIdentityEnabled=\${Build.VERSION.SDK_INT >= 34}"))
        val beforeSdkBranch = send.substringBefore("if (Build.VERSION.SDK_INT >= 34)")
        assertFalse(beforeSdkBranch.contains("BroadcastOptions.makeBasic()"))
        assertFalse(beforeSdkBranch.contains("sentFromUid"))
        assertTrue(send.contains("} else {\n        sendBroadcast(intent)"))
    }

    @Test fun `stale SONG logging is once per bridge and position ticks do not opt in`() {
        assertTrue(bridge.contains("private val staleSongDropLogged = AtomicBoolean(false)"))
        assertTrue(bridge.contains("staleSongDropLogged.compareAndSet(false, true)"))
        assertTrue(bridge.contains("recordStaleSongDrop(\"before_post\""))
        assertTrue(bridge.contains("recordStaleSongDrop(\"queued_execution\""))
        assertEquals(2, Regex("postToSource\\(songPayloadBytes = compressedSong.size\\)").findAll(bridge).count())
        val position = bridge.substringAfter("override fun onPositionChanged(position: Long)")
            .substringBefore("override fun onSeekTo")
        assertTrue(position.contains("postToSource { source.onDirectPositionChanged(position) }"))
        assertFalse(position.contains("songPayloadBytes"))
    }

    @Test fun `decode failures record byte count and error type without throwable or lyric JSON`() {
        val failure = bridge.substringAfter("}.onFailure {\n                // Serialization errors")
            .substringBefore("if (decoded.isFailure)")
        assertTrue(failure.contains("bytes=\${compressedSong.size} error=\${it.javaClass.name}"))
        assertTrue(failure.contains("recoveryDiagnostic(\"direct_song_decode_failed\", details)"))
        assertFalse(failure.contains(", it)"))
        assertFalse(failure.contains("it.message"))
        assertFalse(failure.contains("stackTrace"))
        assertFalse(failure.contains("compressedSong.inflate()"))
    }

    @Test fun `stop clears only captured online translation after revocation and never reconnects`() {
        val stop = bridge.substringAfter("    fun stop() {")
            .substringBefore("    private fun isCurrentConnection")
        val notifyAt = stop.indexOf("translationConnection.clearWithFinalNotification")
        assertTrue(notifyAt > stop.indexOf("registered = false"))
        assertTrue(notifyAt > stop.indexOf("registrationRetry.stop()"))
        assertTrue(notifyAt > stop.indexOf("mainHandler.removeCallbacksAndMessages(null)"))
        assertTrue(stop.contains("receiver.onOnlineTranslationCleared(null)"))
        assertTrue(stop.contains("app.unregisterReceiver(requestReceiver)"))
        assertFalse(stop.contains("onTranslationSendFailure"))
        assertFalse(stop.contains("requestRegistration("))
        assertFalse(stop.contains("onMissingLyricsSupplementCleared"))
    }
}
