/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakIdentityMapTest {

    @Test
    fun `equal but distinct keys keep isolated values`() {
        val map = WeakIdentityMap<Any, String>()
        val first: Any = String(charArrayOf('i', 'd'))
        val second: Any = String(charArrayOf('i', 'd'))

        assertEquals(first, second)
        assertTrue(first !== second)

        map[first] = "first"
        map[second] = "second"

        assertEquals("first", map[first])
        assertEquals("second", map[second])
    }

    @Test
    fun `remove and clear affect only the requested identities`() {
        val map = WeakIdentityMap<Any, String>()
        val first = Any()
        val second = Any()

        map[first] = "first"
        map[second] = "second"
        map.remove(first)

        assertNull(map[first])
        assertEquals("second", map[second])

        map.clear()
        assertNull(map[second])
    }
}
