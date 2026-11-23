package com.juren233.hyperlyricsenhanced.common.coroutines

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The global background dispatchers belong to one module generation in SystemUI: their core
 * workers never exit, and shutting them down crashed SystemUI (250042). Module code uses
 * [ModuleDispatchers] only, and every scope it creates names its dispatcher, because a scope
 * without one silently runs on `Dispatchers.Default`.
 *
 * Robolectric: module threads take the main thread's context loader, which needs a main Looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ModuleDispatchersUsageTest {
    private val globalDispatcher = Regex("""(?<![\w.])(?:kotlinx\.coroutines\.)?Dispatchers\.(IO|Default)\b""")
    private val globalImport = Regex("""^import kotlinx\.coroutines\.(Dispatchers\.(IO|Default)|GlobalScope)\b""")
    private val scopeConstruction = Regex("""(?<![\w.])CoroutineScope\(""")

    private fun sourceLines(): Sequence<Pair<String, String>> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { file ->
                file.readLines().withIndex().map { (i, line) -> "${file.path}:${i + 1}" to line }.asSequence()
            }
            .filterNot { (_, line) -> line.trimStart().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") } }
    }

    @Test fun `module code never uses the global background dispatchers`() {
        val offenders = sourceLines()
            .filter { (_, line) -> globalDispatcher.containsMatchIn(line) || globalImport.containsMatchIn(line) || "GlobalScope" in line }
            .map { (at, line) -> "$at: ${line.trim()}" }
            .toList()
        assertEquals("Use ModuleDispatchers instead:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test fun `every scope the module creates names its dispatcher`() {
        val offenders = sourceLines()
            .filter { (_, line) -> scopeConstruction.containsMatchIn(line) && !line.contains("dispatcher", ignoreCase = true) }
            .map { (at, line) -> "$at: ${line.trim()}" }
            .toList()
        assertEquals("A scope without a dispatcher runs on Dispatchers.Default:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test fun `module code creates threads only through ModuleThreads`() {
        val rawThread = Regex("""(?<![\w.])(Thread|HandlerThread|Timer)\(|(?<![\w.])thread\s*[({]|Executors\.new\w+\(\s*\)""")
        val offenders = sourceLines()
            .filter { (at, line) -> !at.contains("ModuleThreads.kt") && rawThread.containsMatchIn(line) }
            .map { (at, line) -> "$at: ${line.trim()}" }
            .toList()
        assertEquals("A thread inherits its creator's context loader; use ModuleThreads:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test fun `module dispatchers run work and report each finished task`() {
        val finished = CountDownLatch(1)
        ModuleDispatchers.afterEveryTask { finished.countDown() }
        try {
            val thread = runBlocking { withContext(ModuleDispatchers.IO) { Thread.currentThread() } }
            assertTrue(thread.name, thread.name.startsWith("HyperLyrics Enhanced-IO-"))
            assertTrue(thread.isDaemon)
            assertTrue(thread.contextClassLoader === android.os.Looper.getMainLooper().thread.contextClassLoader)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        } finally {
            ModuleDispatchers.afterEveryTask { }
        }
    }
}
