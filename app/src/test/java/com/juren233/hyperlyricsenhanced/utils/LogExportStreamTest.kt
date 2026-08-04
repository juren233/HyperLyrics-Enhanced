package com.juren233.hyperlyricsenhanced.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files

class LogExportStreamTest {
    @Test
    fun `xposed source reads only the newest bounded bytes in stable file order`() {
        val command = LogManager.buildXposedSourceCommand(
            listOf("/data/adb/lspd/log/modules_2.log", "/data/adb/lspd/log/modules_1.log")
        )

        assertEquals(
            "cat '/data/adb/lspd/log/modules_1.log' " +
                "'/data/adb/lspd/log/modules_2.log' 2>/dev/null | tail -c 16777216",
            command
        )
    }

    @Test
    fun `app export keeps complete multiline entries for selected level`() {
        val input = """
            07-27 12:00:00.001 I/App: info
            info continuation
            07-27 12:00:01.002 E/App: error
            error continuation
        """.trimIndent() + "\n"
        val output = StringWriter()

        val count = LogExportStream.copyAppLogs(
            reader = StringReader(input).buffered(),
            selectedLevel = "E",
            writer = output
        )

        assertEquals(1, count)
        assertEquals(
            "07-27 12:00:01.002 E/App: error\nerror continuation\n",
            output.toString()
        )
    }

    @Test
    fun `xposed export spills a large prefix and writes the complete matching block`() {
        val largeLine = "x".repeat(160 * 1024)
        val matchingBlock = buildString {
            appendLine("2026-07-27T12:00:01.002 1000 E/Xposed: failure")
            appendLine(largeLine)
            appendLine("at com.juren233.hyperlyricsenhanced.SomeHook.run(SomeHook.kt:1)")
            appendLine("tail")
        }
        val input = buildString {
            appendLine("2026-07-27T12:00:00.001 1000 I/Xposed: unrelated")
            appendLine("com.example.Other")
            append(matchingBlock)
            appendLine("2026-07-27T12:00:02.003 1000 W/Xposed: unrelated")
            appendLine("com.example.Other")
        }
        val cacheDir = Files.createTempDirectory("log-export-test").toFile()
        val output = StringWriter()

        try {
            val count = LogExportStream.copyXposedLogs(
                reader = StringReader(input).buffered(),
                selectedLevel = "E",
                cacheDir = cacheDir,
                writer = output
            )

            assertEquals(1, count)
            assertEquals(matchingBlock + "\n", output.toString())
            assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
        } finally {
            cacheDir.listFiles().orEmpty().forEach { it.delete() }
            cacheDir.delete()
        }
    }

    @Test
    fun `xposed export discards unmatched and verbose blocks`() {
        val input = buildString {
            appendLine("2026-07-27T12:00:00.001 1000 I/Xposed: unrelated")
            appendLine("com.example.Other")
            appendLine("2026-07-27T12:00:01.002 1000 V/Xposed: verbose")
            appendLine("com.juren233.hyperlyricsenhanced.VerboseHook")
        }
        val cacheDir = Files.createTempDirectory("log-export-test").toFile()
        val output = StringWriter()

        try {
            val count = LogExportStream.copyXposedLogs(
                reader = StringReader(input).buffered(),
                selectedLevel = "ALL",
                cacheDir = cacheDir,
                writer = output
            )

            assertEquals(0, count)
            assertTrue(output.toString().isEmpty())
            assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
        } finally {
            cacheDir.listFiles().orEmpty().forEach { it.delete() }
            cacheDir.delete()
        }
    }
}
