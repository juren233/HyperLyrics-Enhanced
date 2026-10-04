/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises the same finite policy used by SystemUI advertisements; no Android delivery is simulated. */
class AppleDirectRegistrationRetryTest {
    @Test fun `late SystemUI start recovers a lost first advertisement after Apple requests exhausted`() {
        val appleRequests = AppleDirectReconnectPolicy()
        appleRequests.request(0)
        listOf(0L, 500L, 1_500L, 3_500L, 7_500L).forEach {
            assertTrue(appleRequests.takeAttempt(it))
        }
        assertNull(appleRequests.nextAttemptAtMs)
        assertFalse(appleRequests.request(10_000, allowReconnect = false)) // unchanged-song position

        val systemUiAdvertisements = AppleDirectReconnectPolicy()
        assertTrue(systemUiAdvertisements.request(10_000))
        assertTrue(systemUiAdvertisements.takeAttempt(10_000)) // immediate first advertisement is lost
        assertEquals(10_500L, systemUiAdvertisements.nextAttemptAtMs)
        assertTrue(systemUiAdvertisements.takeAttempt(10_500)) // later advertisement reaches Apple
        systemUiAdvertisements.connected() // actual translation callback registration
        assertNull(systemUiAdvertisements.nextAttemptAtMs)
        assertFalse(systemUiAdvertisements.takeAttempt(11_500))
        assertNull(appleRequests.nextAttemptAtMs) // no new position-driven request was needed
    }

    @Test fun `dropped advertisements and coalesced requests cannot exceed the default burst budget`() {
        val policy = AppleDirectReconnectPolicy()
        policy.request(0)
        repeat(1_000) { assertFalse(policy.request(0)) }
        var sends = 0
        while (policy.nextAttemptAtMs != null) {
            assertTrue(policy.takeAttempt(policy.nextAttemptAtMs!!))
            sends++
        }
        assertEquals(5, sends)
        assertFalse(policy.takeAttempt(60_000))
        assertTrue(policy.request(8_000)) // a real new REQUEST, not automatic self-renewal
        assertEquals(30_000L, policy.nextAttemptAtMs)
        assertFalse(policy.takeAttempt(29_999))
    }

    @Test fun `callback registration cancels advertisements and callback loss retains cooldown budget`() {
        val policy = AppleDirectReconnectPolicy()
        policy.request(0)
        assertTrue(policy.takeAttempt(0))
        policy.connected()
        assertNull(policy.nextAttemptAtMs)
        assertFalse(policy.takeAttempt(500))
        assertTrue(policy.request(100)) // current callback Binder dies or sending fails
        assertEquals(600L, policy.nextAttemptAtMs)
        assertTrue(policy.takeAttempt(600))
        policy.connected()
        assertNull(policy.nextAttemptAtMs)
    }

    @Test fun `stop invalidates an already queued advertisement deadline`() {
        val policy = AppleDirectReconnectPolicy()
        policy.request(0)
        policy.takeAttempt(0)
        val queuedDeadline = policy.nextAttemptAtMs!!
        policy.stop()
        assertNull(policy.nextAttemptAtMs)
        assertFalse(policy.takeAttempt(queuedDeadline))
        assertFalse(policy.takeAttempt(60_000))
    }

    @Test fun `SystemUI wiring waits for callback ownership and invalidates retry state on stop`() {
        val root = listOf(File("app/src/main/java"), File("src/main/java")).first(File::isDirectory)
        val source = File(root, "com/juren233/hyperlyricsenhanced/root/source/AppleMusicDirectBridge.kt").readText()
        assertTrue(source.contains("requestRegistration(\"initial\", immediately = true)"))
        assertTrue(source.contains("requestRegistration(\"player_request\", immediately = true)"))
        assertTrue(source.contains("translationConnection.current === entry && entry.binder.isBinderAlive"))
        assertTrue(source.contains("registrationRetry.connected()"))
        assertTrue(source.contains("runCatching { sendRegistration() }"))
        assertTrue(source.contains("requestRegistration(\"translation_receiver_died\", onlyWhenDisconnected = true)"))
        assertTrue(source.contains("requestRegistration(\"translation_send_failed\", onlyWhenDisconnected = true)"))
        val stop = source.substringAfter("    fun stop() {").substringBefore("    private fun isCurrentConnection")
        assertTrue(stop.indexOf("registered = false") < stop.indexOf("registrationRetry.stop()"))
        assertTrue(stop.contains("mainHandler.removeCallbacksAndMessages(null)"))
    }
}
