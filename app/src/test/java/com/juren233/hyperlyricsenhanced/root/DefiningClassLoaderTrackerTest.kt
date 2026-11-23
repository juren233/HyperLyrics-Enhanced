/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.net.URLClassLoader

class DefiningClassLoaderTrackerFixture

class DefiningClassLoaderTrackerTest {
    private val fixtureName = DefiningClassLoaderTrackerFixture::class.java.name
    private val testLoader = DefiningClassLoaderTrackerFixture::class.java.classLoader!!

    @Test fun `child loaders that delegate to the same defining loader are recorded once`() {
        val tracker = DefiningClassLoaderTracker(fixtureName)
        val children = List(5) { URLClassLoader(emptyArray(), testLoader) }

        children.forEach { child ->
            assertSame(DefiningClassLoaderTrackerFixture::class.java, tracker.resolve(child))
        }

        assertEquals(listOf(testLoader), tracker.snapshot())
        assertEquals(listOf(DefiningClassLoaderTrackerFixture::class.java), tracker.knownClasses())
    }

    @Test fun `loaders that cannot see the target class are not recorded`() {
        val tracker = DefiningClassLoaderTracker("com.example.DoesNotExist")

        assertNull(tracker.resolve(URLClassLoader(emptyArray(), testLoader)))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test fun `tracked defining loader is released once nothing else references it`() {
        val tracker = DefiningClassLoaderTracker(fixtureName)
        val loaderRef = resolveInIsolatedLoader(tracker)
        assertEquals(1, tracker.snapshot().size)

        repeat(50) {
            if (loaderRef.get() == null) return@repeat
            System.gc()
            @Suppress("UNUSED_VARIABLE") val pressure = ByteArray(1 shl 20)
            Thread.sleep(10)
        }

        assertNull("tracker must not keep the defining loader alive", loaderRef.get())
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test fun `clear drops every record`() {
        val tracker = DefiningClassLoaderTracker(fixtureName)
        tracker.resolve(testLoader)

        tracker.clear()

        assertTrue(tracker.snapshot().isEmpty())
    }

    /** 用不委托给测试 loader 的独立 loader 自己定义 fixture 类，返回它的弱引用。 */
    private fun resolveInIsolatedLoader(tracker: DefiningClassLoaderTracker): WeakReference<ClassLoader> {
        val location = DefiningClassLoaderTrackerFixture::class.java.protectionDomain.codeSource.location
        val isolated = URLClassLoader(arrayOf(location), null)
        val resolved = tracker.resolve(isolated)!!
        assertSame(isolated, resolved.classLoader)
        return WeakReference(isolated)
    }
}
