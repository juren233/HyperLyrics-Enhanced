package com.juren233.hyperlyricsenhanced.lyric.view.line

import com.juren233.hyperlyricsenhanced.utils.LOG_EXPORT_LEVEL_DEBUG
import com.juren233.hyperlyricsenhanced.utils.LogExportStream
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files

class GeometryAutomaticLoggingTest {
    @Test fun firstUseStartsWithoutPropertyAndNextWindowNeedsNoToggle() {
        val gate = GeometryWindowGate()
        assertEquals(1, gate.start(0))
        assertNull(gate.start(GeometrySampleWindow.WINDOW_NS - 1))
        assertEquals(2, gate.start(GeometrySampleWindow.WINDOW_NS))
        assertEquals(3, gate.start(10 * GeometrySampleWindow.WINDOW_NS))
    }

    @Test fun exhaustedQueryBudgetCannotCauseRapidNewWindows() {
        val gate = GeometryWindowGate()
        assertEquals(1, gate.start(0))
        val window = GeometrySampleWindow(0)
        var samples = 0
        repeat(2048) { index ->
            window.begin(Any(), 5, 1, index.toLong())
            if (window.end(index + 1L, false) != null) samples++
        }
        assertEquals(1024, window.queries)
        assertEquals(64, samples)
        assertEquals(64, window.close().size)
        assertNull(gate.start(2049))
    }

    @Test fun completedSamplesAreAvailableBeforeWindowSummary() {
        val window = GeometrySampleWindow(0)
        assertTrue(window.begin(Any(), 2, 1, 3))
        val sample = window.end(4, false)
        assertNotNull(sample)
        assertFalse(window.closed)
        assertNull(window.end(5, false)) // no duplicate immediate output
        assertEquals(1, window.close().size)
    }

    @Test fun appExportIncludesImmediateInfoSampleWithoutSummary() {
        val row = GeometrySampleWindow.Sample(1, 2, 3, false)
        val message = GeometryDiagnosticLog.sample(220053, 123, 1, row, 4, intArrayOf(0, 0, 4, 0, 0, 0, 0, 0))
        val input = "10-05 01:00:00.000 I/HLEGeomS0: $message\n"
        val output = StringWriter()
        assertEquals(1, LogExportStream.copyAppLogs(StringReader(input).buffered(), LOG_EXPORT_LEVEL_DEBUG, output))
        assertEquals(input, output.toString())
        assertTrue(output.toString().contains("kind=sample"))
        assertTrue(output.toString().contains("queriesThroughSample=4 reasonsThroughSample=0,0,4,0,0,0,0,0"))
        assertFalse(output.toString().contains("kind=summary"))
    }

    @Test fun frameworkTaggedInfoSummarySurvivesExistingModuleExportFilter() {
        val message = GeometryDiagnosticLog.summary(220053, 123, 1, 32, 2, intArrayOf(0, 0, 30, 0, 2, 0, 0, 0))
        val input = "2026-10-05T01:00:00.000 1000 I/LSPosed: [com.juren233.hyperlyricsenhanced] [HLEGeomS0] $message\n"
        val output = StringWriter()
        val dir = Files.createTempDirectory("geometry-export-test").toFile()
        try {
            assertEquals(1, LogExportStream.copyXposedLogs(StringReader(input).buffered(), LOG_EXPORT_LEVEL_DEBUG, dir, output))
            assertTrue(output.toString().contains("[HLEGeomS0] v=2"))
            assertTrue(output.toString().contains("kind=summary queries=32 samples=2 stride=16"))
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            dir.listFiles().orEmpty().forEach { it.delete() }
            dir.delete()
        }
    }
}
