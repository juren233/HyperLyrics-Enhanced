/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.utils

import android.os.Process
import com.juren233.hyperlyricsenhanced.BuildConfig

/** Low-frequency lifecycle markers; inline gating removes detail evaluation from Release. */
internal object LyricRuntimeDiagnostics {
    inline fun record(stage: String, details: () -> String = { "" }) {
        if (BuildConfig.DEBUG) {
            runCatching {
                HookLogger.i(
                    "LyricRuntime",
                    "stage=$stage pid=${Process.myPid()} uid=${Process.myUid()} " + details(),
                )
            }
        }
    }
}
