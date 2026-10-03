/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central

import android.os.Binder
import android.os.Process
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.lyricon.provider.ProviderInfo

/** Low-frequency lifecycle/song events only; never collect payload text or position ticks. */
internal object ProviderFlowDiagnostics {
    inline fun log(event: String, info: ProviderInfo? = null, details: () -> String) {
        if (!BuildConfig.DEBUG) return
        // Diagnostics must not alter Binder delivery, including when logging is unavailable.
        runCatching {
            HookLogger.i(
                "ProviderFlowDiag",
                "[ProviderFlowDiag] event=$event, hostPid=${Process.myPid()}, " +
                    "hostUid=${Process.myUid()}, callerPid=${Binder.getCallingPid()}, " +
                    "callerUid=${Binder.getCallingUid()}, elapsedMs=${SystemClock.elapsedRealtime()}, " +
                    "provider=${info?.providerPackageName}, player=${info?.playerPackageName}, " +
                    "process=${info?.processName}, ${details()}",
            )
        }
    }

    fun id(value: Any?): String = value?.let {
        Integer.toHexString(System.identityHashCode(it))
    } ?: "null"

    fun closePath(): String = Throwable().stackTrace
        .drop(1).take(10).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
}
