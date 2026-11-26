package com.juren233.hyperlyricsenhanced.utils.loganalysis

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 常显自动分析：进设置页就跑，10 秒内重复进出不重跑，分析失败显示警告而不是「一切正常」。 */
class LogAnalysisAutoRunTest {
    private val min = LogAnalysisAutoRun.MIN_INTERVAL_MILLIS

    @Test
    fun firstEntry_starts() {
        assertTrue(LogAnalysisAutoRun.shouldStart(running = false, lastCompletedAtMillis = null, nowMillis = 5_000, force = false))
    }

    @Test
    fun whileRunning_neverStartsAgain_evenForced() {
        assertFalse(LogAnalysisAutoRun.shouldStart(running = true, lastCompletedAtMillis = null, nowMillis = 5_000, force = false))
        assertFalse(LogAnalysisAutoRun.shouldStart(running = true, lastCompletedAtMillis = 0, nowMillis = 5_000, force = true))
    }

    @Test
    fun reenteringWithinInterval_skipped() {
        assertFalse(LogAnalysisAutoRun.shouldStart(false, lastCompletedAtMillis = 100_000, nowMillis = 100_000 + min - 1, force = false))
    }

    @Test
    fun reenteringAfterInterval_starts() {
        assertTrue(LogAnalysisAutoRun.shouldStart(false, lastCompletedAtMillis = 100_000, nowMillis = 100_000 + min, force = false))
    }

    @Test
    fun manualRerun_ignoresInterval() {
        assertTrue(LogAnalysisAutoRun.shouldStart(false, lastCompletedAtMillis = 100_000, nowMillis = 100_001, force = true))
    }

    @Test
    fun reportOf_success_passesThrough() {
        val report = LogAnalysisReport(LogCheck.entries.map { LogCheckResult(it, LogCheckStatus.PASS) })
        assertSame(report, LogAnalysisAutoRun.reportOf(Result.success(report)))
    }

    @Test
    fun reportOf_failure_becomesLogsWarning() {
        val report = LogAnalysisAutoRun.reportOf(Result.failure(IllegalStateException("su 超时")))
        assertEquals(LogAnalysisHealth.WARNINGS_ONLY, report.health)
        assertEquals(LogCheckStatus.WARNING, report[LogCheck.LOGS].status)
    }

    @Test(expected = CancellationException::class)
    fun reportOf_cancellation_rethrown() {
        LogAnalysisAutoRun.reportOf(Result.failure(CancellationException("left page")))
    }
}
