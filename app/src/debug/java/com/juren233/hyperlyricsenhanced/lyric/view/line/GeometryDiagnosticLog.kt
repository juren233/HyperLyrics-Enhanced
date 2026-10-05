package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Typed numeric whitelist shared by the two existing export sinks. No model or text input. */
internal object GeometryDiagnosticLog {
    fun sample(
        build: Int, pid: Int, window: Int, row: GeometrySampleWindow.Sample,
        queriesThroughSample: Int, reasonsThroughSample: IntArray,
    ): String =
        "v=2 build=$build pid=$pid window=$window kind=sample " +
            "queriesThroughSample=$queriesThroughSample reasonsThroughSample=${reasonsThroughSample.joinToString(",")} " +
            "g=${row.generation} reason=${row.reason} words=${row.words} preparedWhole=${row.preparedWhole} utf16=${row.utf16} " +
            "plain=${row.plainCalls} mixed=${row.mixedCalls} wholeNs=${row.wholeNs} " +
            "measureNs=${row.measurementNs} positionNs=${row.positionNs} totalNs=${row.totalNs} " +
            "setupNs=${row.setupNs} failed=${row.failed} reentrant=${row.reentrant}"

    fun summary(build: Int, pid: Int, window: Int, queries: Int, samples: Int, reasons: IntArray): String =
        "v=2 build=$build pid=$pid window=$window kind=summary queries=$queries samples=$samples " +
            "stride=16 reasons=${reasons.joinToString(",")}"
}
