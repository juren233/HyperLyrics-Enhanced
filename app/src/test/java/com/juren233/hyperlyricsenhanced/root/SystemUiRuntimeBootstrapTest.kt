package com.juren233.hyperlyricsenhanced.root

import org.junit.Assert.*
import org.junit.Test

class SystemUiRuntimeBootstrapTest {
    private class Fixture {
        data class Task(val at: Long, val runnable: Runnable)
        var now = 0L
        val tasks = mutableListOf<Task>()
        var app: String? = "systemui"
        var lookupFailure: Throwable? = null
        var ready = false
        var initialized = 0
        var rolledBack = 0
        var failedAttempts = 0
        var rollbackFails = false
        var markReady = true
        var reportingFails = false
        var onInitialize: () -> Unit = {}
        var onRollback: () -> Unit = {}
        val events = mutableListOf<String>()
        val bootstrap = SystemUiRuntimeBootstrap(
            post = { runnable, delay -> tasks += Task(now + delay, runnable) },
            remove = { runnable -> tasks.removeAll { it.runnable === runnable }; Unit },
            findApplication = { lookupFailure?.let { throw it }; app },
            isUsableApplication = { it == "systemui" },
            initialize = {
                initialized++
                onInitialize()
                if (initialized <= failedAttempts) error("core failure")
                ready = markReady
            },
            isRuntimeReady = { ready },
            rollback = {
                rolledBack++
                ready = false
                onRollback()
                if (rollbackFails) error("registration still owned")
            },
            report = { state, _, _, _ ->
                if (reportingFails) error("diagnostics unavailable")
                events += state
            },
        )
        fun next() {
            val task = tasks.minBy { it.at }
            tasks.remove(task)
            now = task.at
            task.runnable.run()
        }
        fun drain() { repeat(50) { if (tasks.isEmpty()) return else next() }; error("unbounded work") }
    }

    @Test fun lifecycleOnlyStartsOnce() = Fixture().run {
        bootstrap.signal("systemui", "lifecycle")
        bootstrap.signal("systemui", "duplicate")
        bootstrap.arm()
        drain()
        assertEquals(1, initialized)
        assertEquals(SystemUiRuntimeBootstrap.State.READY, bootstrap.state)
    }

    @Test fun fallbackOnlyStartsOnce() = Fixture().run {
        bootstrap.arm()
        bootstrap.arm()
        drain()
        bootstrap.signal("systemui", "late_lifecycle")
        assertEquals(1, initialized)
        assertTrue(tasks.isEmpty())
    }

    @Test fun lifecycleCancelsPendingFallback() = Fixture().run {
        bootstrap.arm()
        bootstrap.signal("systemui", "lifecycle")
        drain()
        assertEquals(1, initialized)
        assertTrue(tasks.isEmpty())
    }

    @Test fun applicationCanBecomeAvailableLater() = Fixture().run {
        app = null
        bootstrap.arm()
        next()
        assertEquals(0, initialized)
        app = "systemui"
        next()
        assertEquals(250L, now)
        assertEquals(1, initialized)
    }

    @Test fun acquisitionIsBoundedButLateLifecycleStillWorks() = Fixture().run {
        app = null
        bootstrap.arm()
        drain()
        assertEquals(5_000L, now)
        assertEquals(1, events.count { it == "application_unavailable" })
        assertEquals(0, initialized)
        bootstrap.arm()
        assertTrue(tasks.isEmpty())
        bootstrap.signal("systemui", "late_lifecycle")
        assertEquals(1, initialized)
    }

    @Test fun wrongPackageNeverInitializes() = Fixture().run {
        app = "other"
        bootstrap.arm()
        bootstrap.signal("other", "lifecycle")
        drain()
        assertEquals(0, initialized)
    }

    @Test fun reentrantSignalDuringInitializationIsCoalesced() = Fixture().run {
        onInitialize = { bootstrap.signal("systemui", "reentrant") }
        bootstrap.signal("systemui", "lifecycle")
        assertEquals(1, initialized)
    }

    @Test fun successfulRollbackPrecedesSingleRetry() = Fixture().run {
        failedAttempts = 1
        bootstrap.signal("systemui", "lifecycle")
        assertEquals(SystemUiRuntimeBootstrap.State.RETRY_WAIT, bootstrap.state)
        assertEquals(1, rolledBack)
        bootstrap.signal("systemui", "duplicate")
        assertEquals(1, tasks.size)
        next()
        assertEquals(250L, now)
        assertEquals(2, initialized)
        assertTrue(ready)
    }

    @Test fun allCoreBoundaryFailuresUseSameRollbackContract() {
        // Model resources acquired before each successive initialization boundary fails.
        for (boundary in 1..7) {
            val f = Fixture()
            val resources = linkedSetOf<Int>()
            f.failedAttempts = 1
            f.onInitialize = { for (resource in 1..boundary) resources += resource }
            f.onRollback = { resources.clear() }
            f.bootstrap.signal("systemui", "boundary_$boundary")
            assertEquals(1, f.rolledBack)
            assertTrue(resources.isEmpty())
            assertEquals(SystemUiRuntimeBootstrap.State.RETRY_WAIT, f.bootstrap.state)
        }
    }

    @Test fun uncertainCleanupBlocksRetry() = Fixture().run {
        failedAttempts = 9
        rollbackFails = true
        bootstrap.signal("systemui", "lifecycle")
        drain()
        bootstrap.signal("systemui", "late_lifecycle")
        assertEquals(1, initialized)
        assertEquals(SystemUiRuntimeBootstrap.State.FAILED, bootstrap.state)
        assertTrue("rollback_failed" in events)
    }

    @Test fun failedCoreAttemptsAreBounded() = Fixture().run {
        failedAttempts = 9
        bootstrap.arm()
        drain()
        assertEquals(3, initialized)
        assertEquals(3, rolledBack)
        assertEquals(1_250L, now)
        assertEquals(SystemUiRuntimeBootstrap.State.FAILED, bootstrap.state)
    }

    @Test fun retirementCancelsLookupAndEvenAlreadyDequeuedWork() = Fixture().run {
        bootstrap.arm()
        val task = tasks.single().runnable
        bootstrap.retire()
        task.run()
        assertEquals(0, initialized)
        assertTrue(tasks.isEmpty())
    }

    @Test fun retirementCancelsRetryAndLifecycle() = Fixture().run {
        failedAttempts = 1
        bootstrap.signal("systemui", "lifecycle")
        val task = tasks.single().runnable
        bootstrap.retire()
        task.run()
        bootstrap.signal("systemui", "late_lifecycle")
        assertEquals(1, initialized)
        assertEquals(SystemUiRuntimeBootstrap.State.RETIRED, bootstrap.state)
    }

    @Test fun retirementInsideInitializerDoesNotBecomeReady() = Fixture().run {
        onInitialize = { bootstrap.retire() }
        bootstrap.signal("systemui", "lifecycle")
        assertEquals(SystemUiRuntimeBootstrap.State.RETIRED, bootstrap.state)
    }

    @Test fun hotReloadIsSynchronousAndInstallerCannotDuplicateIt() = Fixture().run {
        assertTrue(bootstrap.initializeNow("systemui"))
        assertEquals(1, initialized)
        bootstrap.arm()
        bootstrap.signal("systemui", "lifecycle")
        drain()
        assertEquals(1, initialized)
    }

    @Test fun failedHotReloadRollsBackWithoutSchedulingRetry() = Fixture().run {
        failedAttempts = 1
        assertFalse(bootstrap.initializeNow("systemui"))
        bootstrap.arm()
        assertEquals(1, rolledBack)
        assertTrue(tasks.isEmpty())
        assertEquals(SystemUiRuntimeBootstrap.State.FAILED, bootstrap.state)
    }

    @Test fun returningWithoutRuntimeReadyIsFailure() = Fixture().run {
        markReady = false
        bootstrap.signal("systemui", "lifecycle")
        drain()
        assertEquals(3, rolledBack)
        assertEquals(SystemUiRuntimeBootstrap.State.FAILED, bootstrap.state)
    }

    @Test fun optionalDiagnosticsCannotBreakStartup() = Fixture().run {
        reportingFails = true
        bootstrap.arm()
        drain()
        assertEquals(1, initialized)
        assertEquals(SystemUiRuntimeBootstrap.State.READY, bootstrap.state)
    }

    @Test fun reflectionFailureIsReportedSeparatelyFromApplicationNotCreated() {
        val failed = Fixture().apply { lookupFailure = IllegalAccessException("hidden API") }
        failed.bootstrap.arm()
        failed.drain()
        assertEquals(5, failed.events.count { it == "application_lookup_failed" })
        assertFalse("application_pending" in failed.events)
        assertEquals(0, failed.initialized)
        val pending = Fixture().apply { app = null }
        pending.bootstrap.arm()
        pending.drain()
        assertEquals(5, pending.events.count { it == "application_pending" })
        assertFalse("application_lookup_failed" in pending.events)
    }
}
