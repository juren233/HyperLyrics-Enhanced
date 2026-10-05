package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.*
import org.junit.Test

class GeometrySampleWindowTest {
    @Test fun fixedStrideAndHardQueryCap() {
        val window = GeometrySampleWindow(0)
        val model = Any()
        repeat(2048) { i ->
            window.begin(model, 2, 3, i.toLong())
            window.end(i + 1L, false)
        }
        assertEquals(1024, window.queries)
        assertEquals(1024, window.reasonCounts[2])
        val rows = window.close()
        assertEquals(64, rows.size)
        assertTrue(rows.all { it.generation == 1 && it.reason == 2 && it.words == 3 })
        assertTrue(window.close().isEmpty())
    }

    @Test fun samplingDoesNotAliasAlternatingResetAndBind() {
        val window = GeometrySampleWindow(0)
        repeat(1024) { i ->
            window.begin(Any(), if (i % 2 == 0) 4 else 2, 0, i.toLong())
            window.end(i + 1L, false)
        }
        val rows = window.close()
        assertEquals(32, rows.count { it.reason == 4 })
        assertEquals(32, rows.count { it.reason == 2 })
    }

    @Test fun identityDoesNotUseContentEqualityOrHash() {
        class EqualModel {
            override fun equals(other: Any?) = true
            override fun hashCode(): Int = error("Must not inspect model hash")
        }
        val window = GeometrySampleWindow(0)
        val first = EqualModel()
        val second = EqualModel()
        repeat(47) { i ->
            val model = if (i == 23) second else first
            window.begin(model, 0, 0, i.toLong())
            window.end(i + 1L, false)
        }
        assertEquals(listOf(1, 2, 1), window.close().map { it.generation })
    }

    @Test fun fixedDeadlineAndCloseDoNotReopen() {
        val window = GeometrySampleWindow(7)
        assertFalse(window.begin(Any(), 0, 0, 7 + GeometrySampleWindow.WINDOW_NS))
        assertEquals(0, window.queries)
        window.close()
        assertFalse(window.begin(Any(), 0, 0, 8))
    }

    @Test fun nestedSampleDoesNotOverwriteOuterAndFailureIsVisible() {
        val window = GeometrySampleWindow(0)
        assertTrue(window.begin(Any(), 6, 2, 1))
        assertFalse(window.begin(Any(), 7, 5, 2))
        assertNull(window.active)
        // These are the same callback accesses used by word() and paintCall().
        window.active?.let { it.utf16 += 100; it.plainCalls++; it.mixedCalls++ }
        window.end(3, false)
        window.active!!.apply { plainCalls = 3; mixedCalls = 4; utf16 = 7 }
        window.end(10, true)
        val row = window.close().single()
        assertEquals(6, row.reason)
        assertEquals(9L, row.totalNs)
        assertTrue(row.failed)
        assertTrue(row.reentrant)
        assertEquals(3, row.plainCalls)
        assertEquals(4, row.mixedCalls)
        assertEquals(1, window.queries)
    }
    @Test fun admissionSetupIsSeparateFromMeasuredTotal() {
        val window = GeometrySampleWindow(0)
        assertTrue(window.begin(Any(), 2, 1, 10))
        window.startMeasurement(17)
        window.end(30, false)
        val row = window.close().single()
        assertEquals(7L, row.setupNs)
        assertEquals(13L, row.totalNs)
    }

    @Test fun unsampledOuterAndNestedScopeReturnToZero() {
        val window = GeometrySampleWindow(0)
        assertTrue(window.begin(Any(), 0, 0, 0))
        window.end(0, false)
        assertFalse(window.begin(Any(), 2, 1, 1))
        assertTrue(window.inProgress)
        assertFalse(window.begin(Any(), 6, 1, 2))
        assertNull(window.active)
        window.end(3, false)
        assertTrue(window.inProgress)
        window.end(4, false)
        assertFalse(window.inProgress)
        assertEquals(2, window.queries)
        repeat(21) { window.begin(Any(), 0, 0, 5); window.end(6, false) }
        assertTrue(window.begin(Any(), 3, 0, 7))
        window.end(8, false)
        assertFalse(window.close().last().reentrant)
    }

}
