package com.juren233.hyperlyricsenhanced.utils

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.Writer
import java.util.regex.Pattern

internal object LogExportStream {
    private const val MAX_PREFIX_MEMORY_CHARS = 128 * 1024
    private const val MODULE_IDENTIFIER = "hyperlyricsenhanced"
    private val appHeaderRegex = Regex(
        """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([DIWEC])/(\S+): (.*)$"""
    )
    private val xposedTimeRegex = Pattern.compile(
        "^(?:\\[\\s*)?(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3})"
    )
    private val xposedLevelRegex = Pattern.compile("\\s+([VDIWEC])/")

    fun copyAppLogs(reader: BufferedReader, selectedLevel: String, writer: Writer): Int {
        var includeCurrentEntry = false
        var exportedEntries = 0
        reader.lineSequence().forEach { line ->
            val match = appHeaderRegex.find(line)
            if (match != null) {
                val level = match.groupValues[2]
                includeCurrentEntry = selectedLevel == "ALL" || selectedLevel == level
                if (includeCurrentEntry) exportedEntries++
            }
            if (includeCurrentEntry) writer.appendLine(line)
        }
        return exportedEntries
    }

    fun copyXposedLogs(
        reader: BufferedReader,
        selectedLevel: String,
        cacheDir: File,
        writer: Writer
    ): Int {
        var pendingBlock: PendingBlock? = null
        var blockStarted = false
        var blockEligible = false
        var writingMatchedBlock = false
        var exportedEntries = 0

        fun finishBlock() {
            if (writingMatchedBlock) {
                writer.appendLine()
                exportedEntries++
            }
            pendingBlock?.discard()
            pendingBlock = null
            blockStarted = false
            blockEligible = false
            writingMatchedBlock = false
        }

        fun startBlock(firstLine: String) {
            blockStarted = true
            val levelMatcher = xposedLevelRegex.matcher(firstLine)
            val level = if (levelMatcher.find()) levelMatcher.group(1) ?: "I" else "I"
            blockEligible = level != "V" && (selectedLevel == "ALL" || selectedLevel == level)
            if (blockEligible) pendingBlock = PendingBlock(cacheDir)
        }

        fun consumeLine(line: String) {
            if (!blockEligible) return
            if (writingMatchedBlock) {
                writer.appendLine(line)
                return
            }
            val pending = requireNotNull(pendingBlock)
            pending.appendLine(line)
            if (line.contains(MODULE_IDENTIFIER, ignoreCase = true)) {
                pending.writeTo(writer)
                pendingBlock = null
                writingMatchedBlock = true
            }
        }

        try {
            reader.lineSequence().forEach { line ->
                if (xposedTimeRegex.matcher(line).find() && blockStarted) finishBlock()
                if (!blockStarted) startBlock(line)
                consumeLine(line)
            }
            if (blockStarted) finishBlock()
        } finally {
            pendingBlock?.discard()
        }
        return exportedEntries
    }

    private class PendingBlock(cacheDir: File) {
        private val memory = StringBuilder()
        private val cacheDir = cacheDir
        private var spillFile: File? = null
        private var spillWriter: BufferedWriter? = null

        fun appendLine(line: String) {
            if (spillWriter == null && memory.length + line.length + 1 <= MAX_PREFIX_MEMORY_CHARS) {
                memory.appendLine(line)
                return
            }
            ensureSpillWriter().appendLine(line)
        }

        fun writeTo(destination: Writer) {
            try {
                spillWriter?.close()
                spillWriter = null
                val file = spillFile
                if (file != null) {
                    file.bufferedReader(Charsets.UTF_8).use { it.copyTo(destination) }
                } else {
                    destination.append(memory)
                }
            } finally {
                memory.clear()
                spillFile?.delete()
                spillFile = null
            }
        }

        fun discard() {
            runCatching { spillWriter?.close() }
            spillWriter = null
            memory.clear()
            spillFile?.delete()
            spillFile = null
        }

        private fun ensureSpillWriter(): BufferedWriter {
            spillWriter?.let { return it }
            val file = File.createTempFile("hyperlyrics-log-export-", ".tmp", cacheDir)
            spillFile = file
            val writer = file.outputStream().bufferedWriter(Charsets.UTF_8)
            try {
                writer.append(memory)
                memory.clear()
                spillWriter = writer
                return writer
            } catch (e: Exception) {
                runCatching { writer.close() }
                file.delete()
                spillFile = null
                throw e
            }
        }
    }
}
