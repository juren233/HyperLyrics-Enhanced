package com.juren233.hyperlyricsenhanced.root.reload

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import com.juren233.hyperlyricsenhanced.root.utils.shutdownNowAndRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.juren233.hyperlyricsenhanced.common.net.ModuleHttpClients
import java.util.logging.ConsoleHandler
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ModuleGenerationReleaseTest {
    private class RecordingExecutor : AbstractExecutorService() {
        val calls = mutableListOf<String>()
        override fun shutdown() { calls += "shutdown" }
        override fun shutdownNow(): MutableList<Runnable> { calls += "shutdownNow"; return mutableListOf() }
        override fun isShutdown() = calls.isNotEmpty()
        override fun isTerminated() = calls.isNotEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        override fun execute(command: Runnable) = Unit
    }

    @Test fun `shutdownNow release also calls shutdown which unregisters the libcore cleaner`() {
        val executor = RecordingExecutor()
        executor.shutdownNowAndRelease()
        assertEquals(listOf("shutdownNow", "shutdown"), executor.calls)
    }

    @Test fun `undispatched main-thread work leaves a thread-local event loop until released`() {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        CoroutineScope(Dispatchers.Unconfined).launch { }
        assertNotNull(currentEventLoop())

        ModuleGenerationRelease.releaseMainThreadEventLoop()

        assertNull(currentEventLoop())
    }

    @Test fun `lyricon process-wide receivers are unregistered from the application context`() {
        val app = RuntimeEnvironment.getApplication()
        val owners = ModuleGenerationRelease.LYRICON_RECEIVER_HOLDERS.map { Class.forName(it) }
        owners.forEach { owner ->
            val instance = owner.getField("INSTANCE").get(null)
            owner.getMethod("initialize", Context::class.java).invoke(instance, app)
            assertTrue(ModuleGenerationRelease.isInitialized(owner))
        }
        val receivers = owners.map { ModuleGenerationRelease.staticReceiverOf(it) }
        assertTrue(receivers.all { it.isRegisteredOn(app) })

        ModuleGenerationRelease.unregisterLyriconReceivers(app)

        assertFalse(receivers.any { it.isRegisteredOn(app) })
    }

    private fun BroadcastReceiver.isRegisteredOn(context: Context): Boolean =
        shadowOf(context as Application).registeredReceivers.any { it.broadcastReceiver === this }

    /** kotlinx internal API, reachable in JVM tests because nothing is renamed here. */
    private fun currentEventLoop(): Any? {
        val holder = Class.forName("kotlinx.coroutines.ThreadLocalEventLoop")
        val instance = holder.getField("INSTANCE").get(null)
        return holder.getMethod("currentOrNull\$kotlinx_coroutines_core").invoke(instance)
    }

    private class OwnHandler : Handler() {
        override fun publish(record: LogRecord?) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    @Test fun `log handler release removes only this generation's handlers from global loggers`() {
        val logger = Logger.getLogger("hle.test.retention")
        val own = OwnHandler()
        val foreign = ConsoleHandler()
        logger.addHandler(own)
        logger.addHandler(foreign)
        try {
            ModuleHttpClients.removeOwnLogHandlers(OwnHandler::class.java.classLoader)
            assertEquals(listOf<Handler>(foreign), logger.handlers.toList())
        } finally {
            logger.removeHandler(own)
            logger.removeHandler(foreign)
        }
    }

    @Test fun `animator cache release removes only classes this loader defined`() {
        val map = HashMap<Class<*>, String>().apply {
            put(OwnHandler::class.java, "own")
            put(String::class.java, "boot")
        }
        val removed = ModuleGenerationRelease.removeClassesOf(map, OwnHandler::class.java.classLoader)
        assertEquals(1, removed)
        assertEquals(setOf<Class<*>>(String::class.java), map.keys)
    }
}
