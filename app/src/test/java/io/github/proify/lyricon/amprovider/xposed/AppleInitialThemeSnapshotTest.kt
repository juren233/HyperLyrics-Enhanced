/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleInitialThemeSnapshotTest {
    @Test
    fun `late theme emission completes the snapshot before any foreground return`() {
        for (night in listOf(0x10, 0x20)) {
            val policy = AppleInitialThemeSnapshot()
            val activity = Any()
            policy.onCreated(activity, -100, night)
            val saved = policy.onThemeResolved(activity, -100, -1, night)
            // Original BaseActivity compares the saved mode with the global mode.
            assertEquals(-1, saved)
            assertNull(policy.onThemeResolved(activity, -100, -1, night))
            assertTrue(policy.awaitingActivities().isEmpty())
        }
    }

    @Test
    fun `creation before the first preference value keeps only the unresolved snapshot`() {
        val policy = AppleInitialThemeSnapshot()
        val activity = Any()
        policy.onCreated(activity, -100, 0x10)
        assertNull(policy.onThemeResolved(activity, -100, -100, 0x10))
        assertEquals(-1, policy.onThemeResolved(activity, -100, -1, 0x10))
        assertNull(policy.onThemeResolved(activity, -100, -1, 0x10))
    }

    @Test
    fun `explicit theme modes and real night transitions still reach native handling`() {
        for (global in listOf(-100, 0, 1, 2, 3)) {
            val policy = AppleInitialThemeSnapshot()
            val activity = Any()
            policy.onCreated(activity, -100, 0x10)
            assertNull("global=$global", policy.onThemeResolved(activity, -100, global, 0x10))
        }
        for ((initial, current) in listOf(0x10 to 0x20, 0x20 to 0x10, 0x10 to 0, 0x20 to 0)) {
            val policy = AppleInitialThemeSnapshot()
            val activity = Any()
            policy.onCreated(activity, -100, initial)
            assertNull(policy.onThemeResolved(activity, -100, -1, current))
            assertNull(policy.onThemeResolved(activity, -100, -1, initial))
        }
    }

    @Test
    fun `resolved snapshots missing creation and unknown configurations are never rewritten`() {
        for (saved in listOf(-1, 0, 1, 2, 3)) {
            val policy = AppleInitialThemeSnapshot()
            val activity = Any()
            policy.onCreated(activity, saved, 0x10)
            assertNull(policy.onThemeResolved(activity, saved, -1, 0x10))
        }
        val policy = AppleInitialThemeSnapshot()
        val activity = Any()
        assertNull(policy.onThemeResolved(activity, -100, -1, 0x10))
        policy.onCreated(activity, -100, 0)
        assertNull(policy.onThemeResolved(activity, -100, -1, 0))
        policy.onCreated(activity, -100, 0x10)
        assertNull(policy.onThemeResolved(activity, 2, -1, 0x10))
    }

    @Test
    fun `recreated or equal objects cannot consume another activity snapshot`() {
        data class ActivityKey(val value: Int)
        val first = ActivityKey(1)
        val recreated = ActivityKey(1)
        val policy = AppleInitialThemeSnapshot()
        policy.onCreated(first, -100, 0x10)
        assertNull(policy.onThemeResolved(recreated, -100, -1, 0x10))
        assertEquals(-1, policy.onThemeResolved(first, -100, -1, 0x10))
        policy.onCreated(recreated, -1, 0x10)
        assertNull(policy.onThemeResolved(recreated, -1, -1, 0x10))
    }

    @Test
    fun `one emission can reach every unresolved live instance without mixing equal activities`() {
        data class ActivityKey(val value: Int)
        val first = ActivityKey(1)
        val second = ActivityKey(1)
        val resolvedBeforeCreation = ActivityKey(2)
        val policy = AppleInitialThemeSnapshot()
        policy.onCreated(first, -100, 0x10)
        policy.onCreated(second, -100, 0x20)
        policy.onCreated(resolvedBeforeCreation, -1, 0x10)

        val pending = policy.awaitingActivities()
        assertEquals(2, pending.size)
        assertSame(first, pending[0])
        assertSame(second, pending[1])
        assertEquals(-1, policy.onThemeResolved(first, -100, -1, 0x10))
        assertSame(second, policy.awaitingActivities().single())
        assertEquals(-1, policy.onThemeResolved(second, -100, -1, 0x20))
        assertTrue(policy.awaitingActivities().isEmpty())
    }

    @Test
    fun `destroyed pages and genuine theme selections never receive a later initialization write`() {
        val forgotten = Any()
        val policy = AppleInitialThemeSnapshot()
        policy.onCreated(forgotten, -100, 0x10)
        policy.forget(forgotten)
        assertNull(policy.onThemeResolved(forgotten, -100, -1, 0x10))
        for (mode in listOf(0, 1, 2, 3)) {
            val activity = Any()
            policy.onCreated(activity, -100, 0x10)
            assertNull(policy.onThemeResolved(activity, -100, mode, 0x10))
            assertNull(policy.onThemeResolved(activity, -100, -1, 0x10))
        }
        assertTrue(policy.awaitingActivities().isEmpty())
    }
}
