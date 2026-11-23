/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.online.source.ttml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TtmlCatalogIoWriteTest {
    @get:Rule val folder = TemporaryFolder()

    private fun temporaries(dir: File) =
        dir.listFiles().orEmpty().filter { it.name.endsWith(TtmlCatalogIo.TEMPORARY_SUFFIX) }

    @Test fun `writes the file and leaves no temporary behind`() {
        val target = File(folder.root, "cache/song.ttml")
        assertTrue(TtmlCatalogIo.writeAtomically(target, byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertTrue(TtmlCatalogIo.writeAtomically(target, byteArrayOf(4)))
        assertArrayEquals(byteArrayOf(4), target.readBytes())
        assertEquals(emptyList<File>(), temporaries(target.parentFile!!))
    }

    @Test fun `a failed rename returns false and writes nothing in place`() {
        // A non-empty directory where the file should go: rename(2) cannot replace it.
        val target = File(folder.root, "song.ttml").apply { mkdirs() }
        File(target, "keep").writeText("x")
        assertFalse(TtmlCatalogIo.writeAtomically(target, byteArrayOf(1)))
        assertTrue(target.isDirectory)
        assertEquals("x", File(target, "keep").readText())
        assertEquals(emptyList<File>(), temporaries(folder.root))
    }

    @Test fun `concurrent writers of one file never leave a mixed or partial file`() {
        val target = File(folder.root, "song.ttml")
        val payloads = (0 until 8).map { writer -> ByteArray(64 * 1024) { writer.toByte() } }
        val pool = Executors.newFixedThreadPool(payloads.size)
        val start = CountDownLatch(1)
        val failedWrites = java.util.concurrent.atomic.AtomicInteger()
        val badReads = java.util.concurrent.atomic.AtomicInteger()
        payloads.forEach { payload ->
            pool.execute {
                start.await()
                repeat(50) {
                    if (!TtmlCatalogIo.writeAtomically(target, payload)) failedWrites.incrementAndGet()
                    // Read through one open descriptor: File.readBytes() sizes its buffer from the
                    // path, which a concurrent rename may already point at another file.
                    val seen = java.io.FileInputStream(target).use { it.readBytes() }
                    if (seen.size != payload.size || seen.any { it != seen[0] }) badReads.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals("failed writes", 0, failedWrites.get())
        assertEquals("partial or mixed reads", 0, badReads.get())
        assertEquals(emptyList<File>(), temporaries(folder.root))
    }
}
