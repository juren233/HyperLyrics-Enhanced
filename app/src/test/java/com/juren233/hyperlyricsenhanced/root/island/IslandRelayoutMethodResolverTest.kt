/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("UNUSED_PARAMETER")
class IslandRelayoutMethodResolverTest {
    @Test
    fun `selects zero argument calculate overload instead of inherited two argument overload`() {
        val resolved = IslandRelayoutMethodResolver.resolve(ContentView::class.java)!!

        assertEquals(IslandRelayoutEntry.CALCULATE_BIG_ISLAND_WIDTH, resolved.entry)
        assertEquals(0, resolved.method.parameterCount)
        assertEquals(ContentView::class.java, resolved.method.declaringClass)
    }

    @Test
    fun `prefers update entry over calculate entry`() {
        val resolved = IslandRelayoutMethodResolver.resolve(UpdateAndCalculate::class.java)!!

        assertEquals(IslandRelayoutEntry.UPDATE_BIG_ISLAND_VIEW_WIDTH, resolved.entry)
        assertEquals("updateBigIslandViewWidth", resolved.method.name)
    }

    @Test
    fun `calculate fallback keeps legacy return type compatibility`() {
        val resolved = IslandRelayoutMethodResolver.resolve(CalculateReturningBoolean::class.java)!!

        assertEquals(Boolean::class.javaPrimitiveType, resolved.method.returnType)
    }

    @Test
    fun `rejects host with only two argument calculate overload`() {
        assertNull(IslandRelayoutMethodResolver.resolve(OnlyTwoArgument::class.java))
    }

    @Test
    fun `rejects static zero argument entry`() {
        assertNull(IslandRelayoutMethodResolver.resolve(StaticUpdate::class.java))
    }

    @Test
    fun `reuses the selected method without scanning a loaded class again`() {
        val expected = IslandRelayoutMethodResolver.resolve(UpdateAndCalculate::class.java)!!
        var scans = 0
        val cache = IslandRelayoutMethodCache {
            scans++
            expected
        }

        repeat(100) {
            assertSame(expected, cache.resolve(UpdateAndCalculate::class.java))
        }
        assertEquals(1, scans)
    }

    @Test
    fun `ordinary parent classes and incompatible overloads are cached misses`() {
        val scans = mutableMapOf<Class<*>, Int>()
        val cache = IslandRelayoutMethodCache {
            scans[it] = scans.getOrDefault(it, 0) + 1
            IslandRelayoutMethodResolver.resolve(it)
        }
        val parents = listOf(Any::class.java, OnlyTwoArgument::class.java, StaticUpdate::class.java)

        repeat(100) {
            parents.forEach { assertNull(cache.resolve(it)) }
            assertEquals(
                IslandRelayoutEntry.CALCULATE_BIG_ISLAND_WIDTH,
                cache.resolve(ContentView::class.java)?.entry,
            )
        }
        assertEquals((parents + ContentView::class.java).associateWith { 1 }, scans)
    }

    @Test
    fun `lookup failure can retry without poisoning the cache`() {
        var scans = 0
        val cache = IslandRelayoutMethodCache {
            scans++
            if (scans == 1) throw SecurityException("temporary lookup failure")
            IslandRelayoutMethodResolver.resolve(it)
        }

        assertThrows(SecurityException::class.java) { cache.resolve(ContentView::class.java) }
        val recovered = cache.resolve(ContentView::class.java)!!
        assertSame(recovered, cache.resolve(ContentView::class.java))
        assertEquals(2, scans)
    }

    @Test
    fun `concurrent lookups share one successful scan`() {
        val expected = IslandRelayoutMethodResolver.resolve(ContentView::class.java)!!
        val scans = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val cache = IslandRelayoutMethodCache {
            scans.incrementAndGet()
            expected
        }
        try {
            val tasks = List(4) {
                pool.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    cache.resolve(ContentView::class.java)
                })
            }
            start.countDown()
            tasks.forEach { assertSame(expected, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, scans.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same named classes from separate plugin loaders keep their own methods`() {
        val fixture = UpdateAndCalculate::class.java
        val resourceName = "/${fixture.name.replace('.', '/')}.class"
        val bytes = requireNotNull(fixture.getResourceAsStream(resourceName)).use { it.readBytes() }
        val first = FixtureClassLoader(fixture.classLoader).defineFixture(fixture.name, bytes)
        val second = FixtureClassLoader(fixture.classLoader).defineFixture(fixture.name, bytes)

        assertEquals(first.name, second.name)
        assertNotSame(first, second)
        val firstResult = IslandRelayoutMethodResolver.resolve(first)!!
        val secondResult = IslandRelayoutMethodResolver.resolve(second)!!
        assertSame(first, firstResult.method.declaringClass)
        assertSame(second, secondResult.method.declaringClass)
        assertSame(firstResult, IslandRelayoutMethodResolver.resolve(first))
        assertSame(secondResult, IslandRelayoutMethodResolver.resolve(second))
    }

    private class FixtureClassLoader(parent: ClassLoader?) : ClassLoader(parent) {
        fun defineFixture(name: String, bytes: ByteArray): Class<*> =
            defineClass(name, bytes, 0, bytes.size)
    }

    open class OnlyTwoArgument {
        fun calculateBigIslandWidth(left: Any, right: Any) = Unit
    }

    class ContentView : OnlyTwoArgument() {
        fun calculateBigIslandWidth() = Unit
    }

    class UpdateAndCalculate {
        fun updateBigIslandViewWidth() = Unit
        fun calculateBigIslandWidth() = Unit
    }

    class CalculateReturningBoolean {
        fun calculateBigIslandWidth(): Boolean = true
    }

    class StaticUpdate private constructor() {
        companion object {
            @JvmStatic
            fun updateBigIslandViewWidth() = Unit
        }
    }
}
