/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

/** Never stringify a hook result: diagnostics must not include lyric contents or object payloads. */
internal fun appleLyricTextLengthSummary(
    originalText: String?,
    selectedText: String?,
    result: Any?,
): String =
    "originalLength=${originalText?.length ?: 0}, " +
        "selectedLength=${selectedText?.length ?: 0}, " +
        "resultLength=${(result as? String)?.length ?: 0}"

internal fun appleWordTimingDiagnosticSummary(
    wordId: Int,
    begin: Int,
    end: Int,
    sampledText: String?,
): String = "$wordId@$begin-$end:sampleChars=${sampledText?.length ?: 0}"
