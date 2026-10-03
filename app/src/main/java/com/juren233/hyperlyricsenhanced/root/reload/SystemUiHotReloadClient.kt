/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import com.juren233.hyperlyricsenhanced.root.RootApplication
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** API 102 hot reload loads an APK update. Preferences continue to use live notifications. */
internal object SystemUiHotReloadClient {
    enum class Result { SUCCEEDED, UNAVAILABLE, REJECTED, FAILED, IN_PROGRESS, PROCESS_DIED, TIMED_OUT }

    suspend fun hasPendingUpdate(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val service = RootApplication.xposedService ?: return@runCatching false
            if (service.apiVersion < 102) return@runCatching false
            service.runningTargets.count {
                it.processName == "com.android.systemui" && it.state == HookedTarget.State.STALE
            } == 1
        }.getOrDefault(false)
    }

    suspend fun reload(): Result = withContext(Dispatchers.IO) {
        try {
            val service = RootApplication.xposedService ?: return@withContext Result.UNAVAILABLE
            if (service.apiVersion < 102) return@withContext Result.UNAVAILABLE
            // Refresh the opaque framework handle at submission time; never fabricate it from a PID.
            val target = service.runningTargets.singleOrNull {
                it.processName == "com.android.systemui" && it.state == HookedTarget.State.STALE
            } ?: return@withContext Result.UNAVAILABLE
            withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine { continuation ->
                    service.hotReloadModule(target, null) { _, result ->
                        val outcome = when (result.status()) {
                            HotReloadResult.Status.SUCCEEDED -> Result.SUCCEEDED
                            HotReloadResult.Status.IN_PROGRESS -> Result.IN_PROGRESS
                            HotReloadResult.Status.PROCESS_DIED -> Result.PROCESS_DIED
                            HotReloadResult.Status.UNSUPPORTED -> Result.UNAVAILABLE
                            HotReloadResult.Status.FAILED -> if (result.message() == null) Result.REJECTED else Result.FAILED
                        }
                        if (continuation.isActive) continuation.resume(outcome)
                    }
                }
            } ?: Result.TIMED_OUT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.FAILED
        }
    }
}
