/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

import org.junit.Assert.*
import org.junit.Test

class AppleDirectReconnectPolicyTest {
    private fun policy() = AppleDirectReconnectPolicy(listOf(0, 10, 20), cooldownMs = 100)

    @Test fun `initial request is immediate and duplicate triggers coalesce into one finite burst`() {
        val policy = policy()
        assertTrue(policy.request(0))
        repeat(1_000) { assertFalse(policy.request(0)) }
        assertTrue(policy.takeAttempt(0))
        assertEquals(10L, policy.nextAttemptAtMs)
        assertFalse(policy.takeAttempt(9))
        assertTrue(policy.takeAttempt(10))
        assertEquals(30L, policy.nextAttemptAtMs)
        assertTrue(policy.takeAttempt(30))
        assertNull(policy.nextAttemptAtMs)
        assertFalse(policy.takeAttempt(1_000))
    }

    @Test fun `a trigger after exhaustion waits for cooldown and still creates only one burst`() {
        val policy = policy()
        policy.request(0)
        listOf(0L, 10L, 30L).forEach { assertTrue(policy.takeAttempt(it)) }
        assertTrue(policy.request(31))
        assertEquals(100L, policy.nextAttemptAtMs)
        repeat(1_000) { assertFalse(policy.request(32)) }
        assertFalse(policy.takeAttempt(99))
        assertTrue(policy.takeAttempt(100))
        assertEquals(110L, policy.nextAttemptAtMs)
        assertTrue(policy.takeAttempt(110))
        assertTrue(policy.takeAttempt(130))
        assertNull(policy.nextAttemptAtMs)
    }

    @Test fun `successful reconnect cancels retries without resetting the anti storm budget`() {
        val policy = policy()
        policy.request(0)
        assertTrue(policy.takeAttempt(0))
        policy.connected()
        assertNull(policy.nextAttemptAtMs)
        assertFalse(policy.takeAttempt(10))
        policy.request(1)
        assertEquals(11L, policy.nextAttemptAtMs)
        assertTrue(policy.takeAttempt(11))
        policy.connected()
        policy.request(12)
        assertEquals(32L, policy.nextAttemptAtMs)
        assertTrue(policy.takeAttempt(32))
        policy.connected()
        policy.request(33)
        assertEquals(100L, policy.nextAttemptAtMs)
    }

    @Test fun `a stalled Handler never renews the active burst indefinitely`() {
        val policy = policy()
        policy.request(0)
        assertTrue(policy.takeAttempt(0))
        assertTrue(policy.takeAttempt(200))
        assertTrue(policy.takeAttempt(400))
        assertNull(policy.nextAttemptAtMs)
        assertFalse(policy.takeAttempt(600))
    }

    @Test fun `position ticks cannot renew an exhausted burst but a meaningful send can`() {
        val policy = policy()
        policy.request(0)
        listOf(0L, 10L, 30L).forEach { policy.takeAttempt(it) }
        repeat(1_000) { assertFalse(policy.request(31L + it, allowReconnect = false)) }
        assertNull(policy.nextAttemptAtMs)
        assertTrue(policy.request(1_100, allowReconnect = true))
        assertEquals(1_100L, policy.nextAttemptAtMs)
        listOf(1_100L, 1_110L, 1_130L).forEach { assertTrue(policy.takeAttempt(it)) }
        assertNull(policy.nextAttemptAtMs)
        assertFalse(policy.request(1_200, allowReconnect = false))
    }

    @Test fun `stop cancels pending work and a fresh start gets a fresh budget`() {
        val policy = policy()
        policy.request(0)
        policy.takeAttempt(0)
        policy.stop()
        assertNull(policy.nextAttemptAtMs)
        assertFalse(policy.takeAttempt(50))
        assertTrue(policy.request(50))
        assertEquals(50L, policy.nextAttemptAtMs)
    }

    @Test fun `snapshots are immutable observations and expose remaining delay without consuming attempts`() {
        val policy = policy()
        val idle = policy.snapshot(0)
        assertEquals(0, idle.attemptsConsumed)
        assertEquals(3, idle.maxAttempts)
        assertNull(idle.nextAttemptAtMs)
        assertNull(idle.nextDelayMs)
        assertEquals(0L, idle.cooldownRemainingMs)
        assertFalse(idle.budgetConsumed)
        assertFalse(idle.waitingForCooldown)

        policy.request(0)
        assertTrue(policy.takeAttempt(0))
        val pending = policy.snapshot(4)
        assertEquals(1, pending.attemptsConsumed)
        assertEquals(10L, pending.nextAttemptAtMs)
        assertEquals(6L, pending.nextDelayMs)
        assertEquals(96L, pending.cooldownRemainingMs)
        repeat(1_000) { assertEquals(pending, policy.snapshot(4)) }
        assertTrue(policy.takeAttempt(10))
        assertEquals(1, pending.attemptsConsumed) // Later mutations cannot change a captured snapshot.
        assertEquals(0, idle.attemptsConsumed)
    }

    @Test fun `budget consumption and a separately triggered cooldown wait are distinct diagnostics`() {
        val policy = policy()
        policy.request(0)
        listOf(0L, 10L, 30L).forEach { policy.takeAttempt(it) }
        val exhausted = policy.snapshot(30)
        assertTrue(exhausted.budgetConsumed)
        assertFalse(exhausted.waitingForCooldown)
        assertEquals(
            "attempt=3 maxAttempts=3 nextDelayMs=none cooldownRemainingMs=70 " +
                "budgetConsumed=true waitingForCooldown=false",
            exhausted.diagnosticFields(),
        )
        policy.request(31)
        val cooldown = policy.snapshot(31)
        assertTrue(cooldown.budgetConsumed)
        assertTrue(cooldown.waitingForCooldown)
        assertEquals(69L, cooldown.nextDelayMs)
        assertEquals(69L, cooldown.cooldownRemainingMs)
        assertEquals(100L, cooldown.nextAttemptAtMs)
    }

    @Test fun `observing overdue deadlines never resets a budget or produces negative delays`() {
        val policy = policy()
        policy.request(0)
        listOf(0L, 10L, 30L).forEach { policy.takeAttempt(it) }
        repeat(1_000) { assertEquals(3, policy.snapshot(1_000L + it).attemptsConsumed) }
        assertFalse(policy.takeAttempt(2_000)) // Observation never schedules a new burst.
        policy.request(31)
        val overdue = policy.snapshot(200)
        assertEquals(3, overdue.attemptsConsumed)
        assertEquals(0L, overdue.nextDelayMs)
        assertEquals(0L, overdue.cooldownRemainingMs)
        assertFalse(overdue.waitingForCooldown)
        assertTrue(policy.takeAttempt(200))
        assertEquals(1, policy.snapshot(200).attemptsConsumed)
    }

    @Test fun `connected snapshots retain anti storm budget while stop clears all diagnostics`() {
        val policy = policy()
        val initial = policy.snapshot(0)
        policy.request(0)
        policy.takeAttempt(0)
        policy.connected()
        val connected = policy.snapshot(1)
        assertEquals(1, connected.attemptsConsumed)
        assertNull(connected.nextDelayMs)
        assertEquals(99L, connected.cooldownRemainingMs)
        policy.stop()
        assertEquals(initial, policy.snapshot(100))
    }
}
