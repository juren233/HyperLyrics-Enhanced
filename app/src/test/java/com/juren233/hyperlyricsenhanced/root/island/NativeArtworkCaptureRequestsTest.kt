/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import java.lang.ref.WeakReference

class NativeArtworkCaptureRequestsTest {
    @Test fun `capture action runs outside request monitor`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        var invoked = false
        requests.callback(WeakReference(host), requests.begin(host)) {
            assertFalse(Thread.holdsLock(requests))
            invoked = true
        }.run()
        assertTrue(invoked)
    }

    @Test fun `queued callback acquires host at execution rather than creation`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        val reference = WeakReference(host)
        var calls = 0
        val callback = requests.callback(reference, requests.begin(host)) { calls++ }
        reference.clear() // Deterministic: do not rely on GC timing.
        callback.run()
        assertEquals(0, calls)
    }

    @Test fun `live current callback receives exact host`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        var received: Any? = null
        val callback = requests.callback(WeakReference(host), requests.begin(host)) { received = it }
        callback.run()
        assertSame(host, received)
    }

    @Test fun `queued callback rejects clear followed by same host reuse`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        var calls = 0
        val callback = requests.callback(WeakReference(host), requests.begin(host)) { calls++ }
        requests.clear()
        requests.begin(host)
        callback.run()
        assertEquals(0, calls)
    }

    @Test fun `replacement rejects old queued callback and permits new one`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        val results = mutableListOf<String>()
        val old = requests.callback(WeakReference(host), requests.begin(host)) { results.add("old") }
        val current = requests.callback(WeakReference(host), requests.begin(host)) { results.add("new") }
        old.run()
        current.run()
        assertEquals(listOf("new"), results)
    }

    @Test fun `successful attempt invalidates other queued attempts`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        val request = requests.begin(host)
        var calls = 0
        val first = requests.callback(WeakReference(host), request) {
            calls++
            requests.complete(it, request)
        }
        val second = requests.callback(WeakReference(host), request) { calls++ }
        first.run()
        second.run()
        assertEquals(1, calls)
    }

    @Test fun `unsuccessful attempt leaves later retry valid`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        val request = requests.begin(host)
        var calls = 0
        val first = requests.callback(WeakReference(host), request) { calls++ }
        val second = requests.callback(WeakReference(host), request) { calls++ }
        first.run()
        second.run()
        assertEquals(2, calls)
        assertTrue(requests.isCurrent(host, request))
    }

    @Test fun `clear then reuse same host rejects already dequeued old callback`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        val old = requests.begin(host)
        var published = false
        val queued = Runnable { if (requests.isCurrent(host, old)) published = true }
        requests.clear()
        val current = requests.begin(host)
        queued.run()
        assertFalse(published)
        assertTrue(requests.isCurrent(host, current))
    }

    @Test fun `old completion after clear cannot invalidate replacement`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        val old = requests.begin(host)
        requests.clear()
        val current = requests.begin(host)
        requests.complete(host, old)
        assertTrue(requests.isCurrent(host, current))
    }

    @Test fun `rescheduling one host does not invalidate another`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val a = Any()
        val b = Any()
        val oldA = requests.begin(a)
        val currentB = requests.begin(b)
        val currentA = requests.begin(a)
        assertFalse(requests.isCurrent(a, oldA))
        assertTrue(requests.isCurrent(a, currentA))
        assertTrue(requests.isCurrent(b, currentB))
    }

    @Test fun `successful capture invalidates all remaining attempts of that request`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        val current = requests.begin(host)
        assertTrue(requests.isCurrent(host, current))
        requests.complete(host, current)
        assertFalse(requests.isCurrent(host, current))
        requests.complete(host, current)
        assertFalse(requests.isCurrent(host, current))
        val next = requests.begin(host)
        assertTrue(requests.isCurrent(host, next))
    }

    @Test fun `old completion cannot invalidate newer request without clear`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val host = Any()
        val old = requests.begin(host)
        val current = requests.begin(host)
        requests.complete(host, old)
        assertTrue(requests.isCurrent(host, current))
    }

    @Test fun `repeated clear invalidates every host and permits a fresh request`() {
        val requests = NativeArtworkCaptureRequests<Any>()
        val a = Any()
        val b = Any()
        val oldA = requests.begin(a)
        val oldB = requests.begin(b)
        requests.clear()
        requests.clear()
        assertFalse(requests.isCurrent(a, oldA))
        assertFalse(requests.isCurrent(b, oldB))
        val current = requests.begin(a)
        assertTrue(requests.isCurrent(a, current))
        assertFalse(requests.isCurrent(a, oldA))
    }
}
