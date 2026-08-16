/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.lyric

/** A compact, process-independent description of the sources queried for a lyric supplement. */
data class AppleMissingLyricsSourceStatus(
    val source: String,
    val searched: Boolean,
    val found: Boolean = false,
    val wordTimed: Boolean = false,
    val lineCount: Int = 0,
)

data class AppleMissingLyricsSourceInfo(
    val selectedSource: String?,
    val statuses: List<AppleMissingLyricsSourceStatus>,
)

/**
 * The lyric payload crosses an AIDL boundary as a serialized Song. Keep this metadata
 * deliberately boring and delimiter based so older Apple Music processes can ignore it.
 */
object AppleMissingLyricsSourceMetadata {
    private const val FIELD_SEPARATOR = "|"
    private const val SOURCE_SEPARATOR = ";"

    fun encodeSelectedSource(source: String?): String? = source?.takeIf(String::isNotBlank)

    fun encodeStatuses(statuses: List<AppleMissingLyricsSourceStatus>): String? {
        if (statuses.isEmpty()) return null
        return statuses.joinToString(SOURCE_SEPARATOR) { status ->
            listOf(
                status.source,
                status.searched.toString(),
                status.found.toString(),
                status.wordTimed.toString(),
                status.lineCount.toString(),
            ).joinToString(FIELD_SEPARATOR)
        }
    }

    fun mergeStatuses(
        previous: List<AppleMissingLyricsSourceStatus>,
        incoming: List<AppleMissingLyricsSourceStatus>,
    ): List<AppleMissingLyricsSourceStatus> {
        val merged = linkedMapOf<String, AppleMissingLyricsSourceStatus>()
        previous.forEach { status ->
            if (status.source.isNotBlank()) merged[status.source] = status
        }
        incoming.forEach { status ->
            if (status.source.isNotBlank()) merged[status.source] = status
        }
        return merged.values.toList()
    }

    fun decode(
        selectedSource: String?,
        encodedStatuses: String?,
    ): AppleMissingLyricsSourceInfo? {
        val statuses = encodedStatuses.orEmpty()
            .split(SOURCE_SEPARATOR)
            .mapNotNull { token ->
                val fields = token.split(FIELD_SEPARATOR)
                if (fields.size != 5 || fields[0].isBlank()) return@mapNotNull null
                AppleMissingLyricsSourceStatus(
                    source = fields[0],
                    searched = fields[1].toBoolean(),
                    found = fields[2].toBoolean(),
                    wordTimed = fields[3].toBoolean(),
                    lineCount = fields[4].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                )
            }
        if (statuses.isEmpty() && selectedSource.isNullOrBlank()) return null
        return AppleMissingLyricsSourceInfo(
            selectedSource = selectedSource?.takeIf(String::isNotBlank),
            statuses = statuses,
        )
    }
}
