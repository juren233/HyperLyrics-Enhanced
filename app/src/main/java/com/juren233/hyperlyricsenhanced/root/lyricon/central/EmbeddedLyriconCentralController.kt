/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.lyricon.central

import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.lyricon.central.BridgeCentral
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the optional in-process Lyricon central running inside SystemUI.
 *
 * A separately installed Lyricon central gets the first chance to answer the normal Subscriber
 * registration. If no standalone package is installed, the embedded bridge starts immediately.
 * When a standalone package exists but does not answer, [onSubscriberConnectTimeout] activates the
 * embedded bridge and broadcasts Central boot completion so existing Providers and Subscribers
 * register again without being restarted.
 */
internal object EmbeddedLyriconCentralController {

    private const val TAG = "EmbeddedLyriconCentral"
    private const val STANDALONE_PROBE_TIMEOUT_MS = 3_500L
    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var standaloneProbeTimeout: Runnable? = null

    fun prepare(app: Application) {
        val installed = installedStandalonePackages(app.packageManager)
        BridgeCentral.initialize(app, startActive = false)
        if (EmbeddedLyriconCentralPolicy.shouldStartImmediately(installed)) {
            ensureStarted(app, reason = "standalone_package_absent")
        } else {
            HookLogger.i(
                TAG,
                "检测到独立 Lyricon Central，优先等待其连接: packages=${installed.sorted()}",
            )
            scheduleStandaloneProbeTimeout(app)
        }
    }

    fun onCentralConnected() {
        standaloneProbeTimeout?.let(mainHandler::removeCallbacks)
        standaloneProbeTimeout = null
        BridgeCentral.discardPendingRegistrations()
    }

    fun onSubscriberConnectTimeout(app: Application) {
        ensureStarted(app, reason = "standalone_connection_timeout")
    }

    private fun ensureStarted(app: Application, reason: String) {
        if (!started.compareAndSet(false, true)) return

        runCatching {
            standaloneProbeTimeout?.let(mainHandler::removeCallbacks)
            standaloneProbeTimeout = null
            BridgeCentral.activate()
            BridgeCentral.sendBootCompleted()
        }.onSuccess {
            HookLogger.i(TAG, "内嵌 Lyricon Central 已启动: reason=$reason")
        }.onFailure { error ->
            started.set(false)
            HookLogger.e(TAG, "内嵌 Lyricon Central 启动失败: reason=$reason", error)
        }
    }

    private fun scheduleStandaloneProbeTimeout(app: Application) {
        standaloneProbeTimeout?.let(mainHandler::removeCallbacks)
        standaloneProbeTimeout = Runnable {
            standaloneProbeTimeout = null
            ensureStarted(app, reason = "standalone_probe_timeout")
        }.also { mainHandler.postDelayed(it, STANDALONE_PROBE_TIMEOUT_MS) }
    }

    private fun installedStandalonePackages(packageManager: PackageManager): Set<String> =
        EmbeddedLyriconCentralPolicy.knownStandalonePackages.filterTo(LinkedHashSet()) { packageName ->
            runCatching {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                    ),
                )
            }.isSuccess
        }
}
