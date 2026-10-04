/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.os.Trace
import com.juren233.hyperlyricsenhanced.BuildConfig

/** Debug-only atrace sections; inactive tracing adds no clocks, payloads or log output. */
internal inline fun <T> traceIslandPerformance(section: String, block: () -> T): T {
    if (!BuildConfig.DEBUG || !Trace.isEnabled()) return block()
    Trace.beginSection(section)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
